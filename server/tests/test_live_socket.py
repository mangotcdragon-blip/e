"""End-to-end test over a real UDP socket on loopback."""

import os
import socket
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from wifimouse import protocol  # noqa: E402
from wifimouse.backends import DummyBackend  # noqa: E402
from wifimouse.server import MouseServer  # noqa: E402


def free_port() -> int:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
    probe.close()
    return port


def test_real_datagrams_reach_the_backend():
    port = free_port()
    backend = DummyBackend()
    server = MouseServer(backend=backend, port=port, bind_host="127.0.0.1", hostname="testpc")
    server.open()
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    client.settimeout(2.0)
    target = ("127.0.0.1", port)

    try:
        client.sendto(protocol.DISCOVER_REQUEST.encode(), target)
        reply, _ = client.recvfrom(protocol.MAX_PACKET)
        fields = reply.decode().split(" ")
        assert fields[0] == "WM1" and fields[1] == "srv"
        assert int(fields[2]) == port
        assert protocol.unquote_text(fields[3]) == "testpc"

        client.sendto(protocol.encode(None, 1, "h"), target)
        reply, _ = client.recvfrom(protocol.MAX_PACKET)
        assert reply.decode().startswith("WM1 ok testpc")

        client.sendto(protocol.encode(None, 2, "m", "12", "-7"), target)
        client.sendto(protocol.encode(None, 3, "c", "r"), target)
        client.sendto(protocol.encode(None, 3, "c", "r"), target)  # the app's retry

        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline and len(backend.events) < 3:
            time.sleep(0.01)

        assert backend.events == [("move", 12, -7), ("down", "r"), ("up", "r")]
    finally:
        client.close()
        server.stop()
        thread.join(timeout=2.0)
