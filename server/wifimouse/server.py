"""The UDP server that turns phone packets into real pointer movement."""

from __future__ import annotations

import logging
import socket
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Dict, Optional, Tuple

from . import protocol
from .backends import InputBackend
from .protocol import Packet, ProtocolError

log = logging.getLogger("wifimouse")

Address = Tuple[str, int]

#: How many sequence numbers to remember per client when filtering duplicates.
#: Discrete events (clicks, keys) are deliberately sent more than once by the
#: app so a single lost datagram cannot swallow a click.
SEQ_MEMORY = 256

#: A held button whose owner has gone quiet for this long is released, so a
#: lost "button up" can never leave the desktop stuck in a drag.
IDLE_RELEASE_SECONDS = 2.0

#: Clients are forgotten after this long, purely to keep the table small.
SESSION_TTL_SECONDS = 300.0


@dataclass
class ClientSession:
    """Per-phone state: what it has sent and what it is still holding down."""

    address: Address
    last_seen: float
    max_seq: int = -1
    seen_seqs: "OrderedDict[int, None]" = field(default_factory=OrderedDict)
    held: set = field(default_factory=set)
    greeted: bool = False
    accum: Dict[str, float] = field(default_factory=lambda: {"x": 0.0, "y": 0.0, "sx": 0.0, "sy": 0.0})
    last_auth_warning: float = 0.0

    def remember(self, seq: int) -> bool:
        """Record ``seq``; return False when it was already seen."""
        if seq in self.seen_seqs:
            return False
        self.seen_seqs[seq] = None
        while len(self.seen_seqs) > SEQ_MEMORY:
            self.seen_seqs.popitem(last=False)
        return True

    def take(self, axis: str, value: float) -> int:
        """Accumulate a fractional delta and hand back whole units.

        Sub-pixel movement would otherwise be rounded away, which makes slow,
        careful dragging impossible.
        """
        total = self.accum[axis] + value
        whole = int(total)  # truncates toward zero, so the sign is preserved
        self.accum[axis] = total - whole
        return whole


class MouseServer:
    def __init__(
        self,
        backend: InputBackend,
        port: int = protocol.DEFAULT_PORT,
        bind_host: str = "0.0.0.0",
        token: Optional[str] = None,
        allow_discovery: bool = True,
        hostname: Optional[str] = None,
        max_step: int = 400,
    ) -> None:
        self.backend = backend
        self.port = port
        self.bind_host = bind_host
        self.token = token or None
        self.allow_discovery = allow_discovery
        self.hostname = hostname or socket.gethostname()
        # A single packet should never fling the pointer across the desktop;
        # this bounds the damage from a corrupt or hostile datagram.
        self.max_step = max_step
        self.sessions: Dict[Address, ClientSession] = {}
        self._sock: Optional[socket.socket] = None
        self._running = False

    # -- lifecycle -------------------------------------------------------- #

    def open(self) -> socket.socket:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        except OSError:
            pass
        sock.bind((self.bind_host, self.port))
        sock.settimeout(0.5)
        self._sock = sock
        return sock

    def serve_forever(self) -> None:
        sock = self._sock or self.open()
        self._running = True
        log.info("listening on %s:%d via %s", self.bind_host, self.port, self.backend.name)
        try:
            while self._running:
                try:
                    data, addr = sock.recvfrom(protocol.MAX_PACKET + 1)
                except socket.timeout:
                    self.housekeeping()
                    continue
                except OSError as exc:
                    if not self._running:
                        break
                    log.warning("receive failed: %s", exc)
                    continue
                self.handle(data, addr)
                self.housekeeping()
        finally:
            self.shutdown()

    def stop(self) -> None:
        self._running = False

    def shutdown(self) -> None:
        for session in list(self.sessions.values()):
            self._release_all(session, reason="shutdown")
        if self._sock is not None:
            self._sock.close()
            self._sock = None
        self.backend.close()

    # -- housekeeping ----------------------------------------------------- #

    def housekeeping(self, now: Optional[float] = None) -> None:
        now = now if now is not None else time.monotonic()
        for addr, session in list(self.sessions.items()):
            idle = now - session.last_seen
            if session.held and idle > IDLE_RELEASE_SECONDS:
                self._release_all(session, reason="client went quiet")
            if idle > SESSION_TTL_SECONDS:
                del self.sessions[addr]

    def _release_all(self, session: ClientSession, reason: str) -> None:
        for btn in sorted(session.held):
            log.info("releasing %s button held by %s (%s)", btn, session.address[0], reason)
            try:
                self.backend.button_up(btn)
            except Exception as exc:  # a stuck button is worse than a log line
                log.warning("could not release %s: %s", btn, exc)
        session.held.clear()

    # -- packet handling -------------------------------------------------- #

    def _send(self, payload: bytes, addr: Address) -> None:
        if self._sock is None:
            return
        try:
            self._sock.sendto(payload, addr)
        except OSError as exc:
            log.debug("reply to %s failed: %s", addr, exc)

    def handle(self, data: bytes, addr: Address, now: Optional[float] = None) -> None:
        now = now if now is not None else time.monotonic()
        try:
            packet = protocol.parse(data)
        except ProtocolError as exc:
            log.debug("ignoring packet from %s: %s", addr[0], exc)
            return

        if packet.is_discover:
            if self.allow_discovery:
                log.info("discovery request from %s", addr[0])
                self._send(
                    protocol.discover_reply(self.port, self.hostname, bool(self.token)), addr
                )
            return

        session = self.sessions.get(addr)
        if session is None:
            session = ClientSession(address=addr, last_seen=now)
            self.sessions[addr] = session

        if self.token and packet.token != self.token:
            # Rate-limited so a misconfigured phone cannot flood the log.
            if now - session.last_auth_warning > 1.0:
                session.last_auth_warning = now
                log.warning("rejected packet from %s: wrong pairing code", addr[0])
                self._send(protocol.error_reply("auth"), addr)
            return

        if not session.remember(packet.seq):
            return  # duplicate, already applied
        if packet.is_motion and packet.seq < session.max_seq:
            return  # reordered motion: the newer position already won
        session.max_seq = max(session.max_seq, packet.seq)

        session.last_seen = now

        try:
            self._dispatch(session, packet, addr)
        except ProtocolError as exc:
            log.debug("bad command from %s: %s", addr[0], exc)
        except Exception as exc:
            log.error("failed to apply %s from %s: %s", packet.verb, addr[0], exc)

    def _clamp(self, value: int) -> int:
        return max(-self.max_step, min(self.max_step, value))

    def _dispatch(self, session: ClientSession, packet: Packet, addr: Address) -> None:
        verb, args = packet.verb, packet.args

        if verb == "m":
            dx, dy = protocol.floats(args, 2)
            sx = self._clamp(session.take("x", dx))
            sy = self._clamp(session.take("y", dy))
            if sx or sy:
                self.backend.move_relative(sx, sy)

        elif verb == "a":
            nx, ny = protocol.floats(args, 2)
            self.backend.move_absolute(min(max(nx, 0.0), 1.0), min(max(ny, 0.0), 1.0))

        elif verb == "s":
            dx, dy = protocol.floats(args, 2)
            sx = self._clamp(session.take("sx", dx))
            sy = self._clamp(session.take("sy", dy))
            if sx or sy:
                self.backend.scroll(sx, sy)

        elif verb == "d":
            btn = protocol.button(args)
            if btn not in session.held:
                session.held.add(btn)
                self.backend.button_down(btn)

        elif verb == "u":
            btn = protocol.button(args)
            if btn in session.held:
                session.held.discard(btn)
                self.backend.button_up(btn)

        elif verb == "c":
            btn = protocol.button(args)
            if btn in session.held:
                session.held.discard(btn)
                self.backend.button_up(btn)
            self.backend.button_down(btn)
            self.backend.button_up(btn)

        elif verb == "k":
            self.backend.tap_key(protocol.key_name(args))

        elif verb == "t":
            if not args:
                raise ProtocolError("missing text")
            text = protocol.unquote_text(args[0])
            if text:
                self.backend.type_text(text)

        elif verb == "h":
            self._send(protocol.ok_reply(self.hostname), addr)
            if not session.greeted:
                session.greeted = True
                log.info("phone connected from %s", addr[0])

        elif verb == "b":
            self._release_all(session, reason="client disconnected")
            session.greeted = False
            log.info("phone disconnected from %s", addr[0])

        else:
            raise ProtocolError(f"unknown verb {verb!r}")
