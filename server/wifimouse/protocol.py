"""Wire protocol shared by the Android client and the desktop server.

Every packet is a single UDP datagram holding one ASCII line:

    WM1 <token> <seq> <verb> [args...]

``token`` is the shared secret (``-`` when the server runs without one) and
``seq`` is a monotonically increasing counter per client session.  Keeping one
command per datagram means a dropped packet costs a single event, and keeping
it textual means the whole protocol can be debugged with ``nc``.

Verbs
-----
``m <dx> <dy>``     relative pointer move, in pixels (floats allowed)
``a <x> <y>``       absolute move, normalised 0..1 of the virtual desktop
``s <dx> <dy>``     scroll, in wheel notches (x = horizontal, y = vertical)
``d <btn>``         press a button   (``l`` / ``m`` / ``r``)
``u <btn>``         release a button
``c <btn>``         click (press + release)
``k <name>``        tap a named key, e.g. ``enter``, ``f5``, ``volup``
``t <text>``        type text; percent-encoded UTF-8
``h``               hello / heartbeat, answered with ``WM1 ok ...``
``b``               goodbye: release everything held

Discovery lives outside the sequence space: a client broadcasts
``WM1 ? 0 discover`` and every server answers with
``WM1 srv <port> <hostname> <needs_token>``.
"""

from __future__ import annotations

import urllib.parse
from dataclasses import dataclass
from typing import List, Optional

MAGIC = "WM1"
VERSION = "1.0.0"
DEFAULT_PORT = 7654

#: Datagrams are tiny; anything larger is not ours.
MAX_PACKET = 1024

NO_TOKEN = "-"

DISCOVER_VERB = "discover"
DISCOVER_REQUEST = f"{MAGIC} ? 0 {DISCOVER_VERB}"

BUTTONS = ("l", "m", "r")

#: Keys a client may ask for by name.  The server maps these onto whatever the
#: platform backend needs; clients only ever send these spellings.
KEY_NAMES = (
    "enter", "backspace", "tab", "esc", "space", "delete",
    "up", "down", "left", "right",
    "home", "end", "pageup", "pagedown",
    "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12",
    "volup", "voldown", "mute", "play", "next", "prev",
)


class ProtocolError(ValueError):
    """Raised when a datagram is not a well-formed command."""


@dataclass(frozen=True)
class Packet:
    token: str
    seq: int
    verb: str
    args: List[str]

    @property
    def is_discover(self) -> bool:
        return self.verb == DISCOVER_VERB

    @property
    def is_motion(self) -> bool:
        """Motion is resent constantly, so a stale one is better dropped."""
        return self.verb in ("m", "a", "s")


def parse(data: bytes) -> Packet:
    """Parse a datagram, raising :class:`ProtocolError` on anything malformed."""
    if len(data) > MAX_PACKET:
        raise ProtocolError("packet too large")
    try:
        line = data.decode("utf-8").strip()
    except UnicodeDecodeError as exc:
        raise ProtocolError("packet is not UTF-8") from exc
    if not line:
        raise ProtocolError("empty packet")

    parts = line.split(" ")
    if len(parts) < 4 or parts[0] != MAGIC:
        raise ProtocolError("not a WM1 packet")

    token, raw_seq, verb = parts[1], parts[2], parts[3]
    args = parts[4:]

    try:
        seq = int(raw_seq)
    except ValueError as exc:
        raise ProtocolError(f"bad sequence number {raw_seq!r}") from exc
    if seq < 0:
        raise ProtocolError("negative sequence number")
    if not verb:
        raise ProtocolError("missing verb")

    return Packet(token=token, seq=seq, verb=verb, args=args)


def encode(token: Optional[str], seq: int, verb: str, *args: str) -> bytes:
    """Build a datagram.  Used by the tests and by ``--send`` debugging."""
    return " ".join([MAGIC, token or NO_TOKEN, str(seq), verb, *args]).encode("utf-8")


def quote_text(text: str) -> str:
    """Percent-encode text so it survives the space-separated wire format."""
    return urllib.parse.quote(text, safe="")


def unquote_text(value: str) -> str:
    return urllib.parse.unquote(value, errors="replace")


def floats(args: List[str], count: int) -> List[float]:
    """Coerce ``count`` arguments to finite floats."""
    if len(args) < count:
        raise ProtocolError(f"expected {count} numeric arguments, got {len(args)}")
    out = []
    for raw in args[:count]:
        try:
            value = float(raw)
        except ValueError as exc:
            raise ProtocolError(f"bad number {raw!r}") from exc
        if value != value or value in (float("inf"), float("-inf")):
            raise ProtocolError(f"non-finite number {raw!r}")
        out.append(value)
    return out


def button(args: List[str]) -> str:
    if not args:
        raise ProtocolError("missing button")
    name = args[0]
    if name not in BUTTONS:
        raise ProtocolError(f"unknown button {name!r}")
    return name


def key_name(args: List[str]) -> str:
    if not args:
        raise ProtocolError("missing key name")
    name = args[0].lower()
    if name not in KEY_NAMES:
        raise ProtocolError(f"unknown key {name!r}")
    return name


def ok_reply(hostname: str) -> bytes:
    return f"{MAGIC} ok {hostname} {VERSION}".encode("utf-8")


def error_reply(reason: str) -> bytes:
    return f"{MAGIC} err {reason}".encode("utf-8")


def discover_reply(port: int, hostname: str, needs_token: bool) -> bytes:
    safe_host = quote_text(hostname)
    return f"{MAGIC} srv {port} {safe_host} {1 if needs_token else 0}".encode("utf-8")
