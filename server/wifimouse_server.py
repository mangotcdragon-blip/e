#!/usr/bin/env python3
"""Start the WiFi Mouse server.

    python3 wifimouse_server.py              # listen on UDP 7654
    python3 wifimouse_server.py -t 1234      # require the pairing code 1234
    python3 wifimouse_server.py --dry-run -v # show events without moving the pointer
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from wifimouse.cli import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
