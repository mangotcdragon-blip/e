"""Command line entry point for the WiFi Mouse server."""

from __future__ import annotations

import argparse
import logging
import socket
import sys
from typing import List

from . import protocol
from .backends import create_backend
from .server import MouseServer

log = logging.getLogger("wifimouse")


def local_addresses() -> List[str]:
    """Best-effort list of this machine's LAN IPv4 addresses."""
    found: List[str] = []

    # The address a packet to the internet would leave from is almost always
    # the one the phone should talk to.
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 53))  # no traffic is sent for UDP
        found.append(probe.getsockname()[0])
    except OSError:
        pass
    finally:
        probe.close()

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass

    seen = []
    for address in found:
        if address not in seen and not address.startswith("127."):
            seen.append(address)
    return seen


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="wifimouse",
        description="Receive pointer and keyboard events from the WiFi Mouse Android app.",
    )
    parser.add_argument("-p", "--port", type=int, default=protocol.DEFAULT_PORT,
                        help=f"UDP port to listen on (default {protocol.DEFAULT_PORT})")
    parser.add_argument("--bind", default="0.0.0.0", metavar="ADDR",
                        help="interface address to bind (default all interfaces)")
    parser.add_argument("-t", "--token", default=None, metavar="CODE",
                        help="pairing code; the phone must send the same one")
    parser.add_argument("--no-discovery", action="store_true",
                        help="do not answer the app's 'find my PC' broadcasts")
    parser.add_argument("--dry-run", action="store_true",
                        help="log events instead of controlling the real pointer")
    parser.add_argument("-v", "--verbose", action="store_true", help="log every event")
    parser.add_argument("--max-step", type=int, default=400, metavar="PX",
                        help="largest pointer jump a single packet may cause (default 400)")
    parser.add_argument("--version", action="version", version=f"wifimouse {protocol.VERSION}")
    return parser


def banner(server: MouseServer) -> str:
    addresses = local_addresses()
    lines = [
        "",
        f"  WiFi Mouse server {protocol.VERSION} — {server.hostname}",
        f"  input backend : {server.backend.name}",
        f"  listening on  : UDP {server.bind_host}:{server.port}",
    ]
    if addresses:
        lines.append("  this PC is at : " + ", ".join(addresses))
    else:
        lines.append("  this PC is at : (could not detect a LAN address)")
    lines.append(
        "  pairing code  : " + (server.token if server.token else "(none — any phone on this network may connect)")
    )
    lines.append(
        "  discovery     : " + ("on — tap Find PC in the app" if server.allow_discovery else "off")
    )
    lines += [
        "",
        "  If the phone cannot connect, allow inbound UDP "
        f"{server.port} through the firewall.",
        "  Press Ctrl+C to stop.",
        "",
    ]
    return "\n".join(lines)


def main(argv: List[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s  %(message)s",
        datefmt="%H:%M:%S",
    )

    backend = create_backend(dry_run=args.dry_run, log=log.debug)
    server = MouseServer(
        backend=backend,
        port=args.port,
        bind_host=args.bind,
        token=args.token,
        allow_discovery=not args.no_discovery,
        max_step=args.max_step,
    )

    try:
        server.open()
    except OSError as exc:
        print(f"Could not listen on {args.bind}:{args.port} — {exc}", file=sys.stderr)
        if getattr(exc, "errno", None) in (13, 10013):
            print("Try a port above 1024, or run with the privileges that port needs.", file=sys.stderr)
        return 1

    print(banner(server))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
        server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
