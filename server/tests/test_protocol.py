"""Protocol parsing and dispatch tests.  Run with: python3 -m pytest server/tests"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from wifimouse import protocol  # noqa: E402
from wifimouse.backends import DummyBackend  # noqa: E402
from wifimouse.protocol import ProtocolError  # noqa: E402
from wifimouse.server import IDLE_RELEASE_SECONDS, MouseServer  # noqa: E402

ADDR = ("192.168.1.50", 41000)
OTHER = ("192.168.1.51", 41000)


def make_server(**kwargs):
    backend = DummyBackend()
    server = MouseServer(backend=backend, hostname="testpc", **kwargs)
    return server, backend


def send(server, verb, *args, seq=1, token=None, addr=ADDR, now=0.0):
    server.handle(protocol.encode(token, seq, verb, *args), addr, now=now)


# -- parsing ------------------------------------------------------------- #

def test_roundtrip():
    packet = protocol.parse(protocol.encode("abc", 12, "m", "1.5", "-2"))
    assert (packet.token, packet.seq, packet.verb, packet.args) == ("abc", 12, "m", ["1.5", "-2"])


@pytest.mark.parametrize("raw", [
    b"", b"   ", b"HTTP/1.1 200 OK", b"WM1 - x m", b"WM1 - -3 m", b"WM1 - 1",
    b"\xff\xfe\x00", b"WM1 - 1 " + b"x" * protocol.MAX_PACKET,
])
def test_garbage_is_rejected(raw):
    with pytest.raises(ProtocolError):
        protocol.parse(raw)


def test_text_survives_spaces_and_unicode():
    text = "hello world — ünïcode 😀"
    quoted = protocol.quote_text(text)
    assert " " not in quoted
    packet = protocol.parse(protocol.encode(None, 1, "t", quoted))
    assert protocol.unquote_text(packet.args[0]) == text


def test_floats_rejects_nonfinite():
    with pytest.raises(ProtocolError):
        protocol.floats(["nan", "1"], 2)
    with pytest.raises(ProtocolError):
        protocol.floats(["inf", "1"], 2)
    assert protocol.floats(["1.5", "-2.5"], 2) == [1.5, -2.5]


def test_unknown_button_and_key():
    with pytest.raises(ProtocolError):
        protocol.button(["x"])
    with pytest.raises(ProtocolError):
        protocol.key_name(["launch_missiles"])
    assert protocol.key_name(["ENTER"]) == "enter"


# -- movement ------------------------------------------------------------ #

def test_relative_move_applied():
    server, backend = make_server()
    send(server, "m", "10", "-5")
    assert backend.events == [("move", 10, -5)]


def test_subpixel_movement_accumulates():
    server, backend = make_server()
    for seq in range(1, 5):
        send(server, "m", "0.3", "0", seq=seq)
    # Four 0.3px steps must produce exactly one whole pixel, not zero and not four.
    assert backend.events == [("move", 1, 0)]


def test_absolute_move_is_clamped_to_screen():
    server, backend = make_server()
    send(server, "a", "1.4", "-0.2")
    assert backend.events == [("moveabs", 1.0, 0.0)]


def test_single_packet_cannot_fling_the_pointer():
    server, backend = make_server(max_step=400)
    send(server, "m", "999999", "0")
    assert backend.events == [("move", 400, 0)]


def test_scroll_axes():
    server, backend = make_server()
    send(server, "s", "0", "3")
    assert backend.events == [("scroll", 0, 3)]


# -- duplicates, ordering, reliability ----------------------------------- #

def test_duplicate_click_is_applied_once():
    """The app sends discrete events twice; only one click may land."""
    server, backend = make_server()
    payload = protocol.encode(None, 9, "c", "l")
    server.handle(payload, ADDR)
    server.handle(payload, ADDR)
    assert backend.events == [("down", "l"), ("up", "l")]


def test_reordered_motion_is_dropped_but_clicks_are_not():
    server, backend = make_server()
    send(server, "m", "5", "0", seq=10)
    send(server, "m", "5", "0", seq=4)   # arrived late: stale
    send(server, "c", "l", seq=5)        # late click still has to happen
    assert backend.events == [("move", 5, 0), ("down", "l"), ("up", "l")]


def test_sessions_are_independent():
    server, backend = make_server()
    send(server, "m", "4", "0", seq=1, addr=ADDR)
    send(server, "m", "4", "0", seq=1, addr=OTHER)
    assert backend.events == [("move", 4, 0), ("move", 4, 0)]


def test_seq_memory_is_bounded():
    server, _ = make_server()
    for seq in range(1, 2000):
        send(server, "m", "1", "0", seq=seq)
    assert len(server.sessions[ADDR].seen_seqs) <= 256


# -- buttons ------------------------------------------------------------- #

def test_button_down_is_not_repeated():
    server, backend = make_server()
    send(server, "d", "l", seq=1)
    send(server, "d", "l", seq=2)
    send(server, "u", "l", seq=3)
    send(server, "u", "l", seq=4)
    assert backend.events == [("down", "l"), ("up", "l")]


def test_held_button_is_released_when_phone_goes_quiet():
    server, backend = make_server()
    send(server, "d", "l", seq=1, now=100.0)
    server.housekeeping(now=100.0 + IDLE_RELEASE_SECONDS + 0.1)
    assert backend.events == [("down", "l"), ("up", "l")]
    assert not server.sessions[ADDR].held


def test_click_while_dragging_releases_first():
    server, backend = make_server()
    send(server, "d", "l", seq=1)
    send(server, "c", "l", seq=2)
    assert backend.events == [("down", "l"), ("up", "l"), ("down", "l"), ("up", "l")]


def test_bye_releases_everything():
    server, backend = make_server()
    send(server, "d", "r", seq=1)
    send(server, "b", seq=2)
    assert backend.events == [("down", "r"), ("up", "r")]


# -- keyboard ------------------------------------------------------------ #

def test_typing_and_keys():
    server, backend = make_server()
    send(server, "t", protocol.quote_text("hi there"), seq=1)
    send(server, "k", "enter", seq=2)
    assert backend.events == [("text", "hi there"), ("key", "enter")]


def test_unknown_key_is_ignored_not_fatal():
    server, backend = make_server()
    send(server, "k", "nope", seq=1)
    send(server, "k", "tab", seq=2)
    assert backend.events == [("key", "tab")]


# -- auth and discovery -------------------------------------------------- #

def test_wrong_token_is_rejected():
    server, backend = make_server(token="secret")
    send(server, "m", "10", "0", token="guess", seq=1)
    assert backend.events == []
    send(server, "m", "10", "0", token="secret", seq=2)
    assert backend.events == [("move", 10, 0)]


def test_discovery_needs_no_token():
    server, backend = make_server(token="secret")
    replies = []
    server._send = lambda payload, addr: replies.append(payload)
    server.handle(protocol.DISCOVER_REQUEST.encode(), ADDR)
    assert replies == [protocol.discover_reply(server.port, "testpc", True)]
    assert backend.events == []


def test_discovery_can_be_disabled():
    server, _ = make_server(allow_discovery=False)
    replies = []
    server._send = lambda payload, addr: replies.append(payload)
    server.handle(protocol.DISCOVER_REQUEST.encode(), ADDR)
    assert replies == []


def test_heartbeat_is_answered():
    server, _ = make_server()
    replies = []
    server._send = lambda payload, addr: replies.append(payload)
    send(server, "h", seq=1)
    assert replies == [protocol.ok_reply("testpc")]


def test_malformed_packets_never_raise():
    server, backend = make_server()
    for raw in [b"", b"junk", b"WM1 - 1 m nan 3", b"WM1 - 1 zzz", b"WM1 - 1 t", b"WM1 - 1 d q"]:
        server.handle(raw, ADDR)
    assert backend.events == []
