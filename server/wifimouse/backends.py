"""Platform input backends.

On Windows the backend talks straight to ``SendInput`` through ctypes, so the
server needs nothing but a stock Python install.  Elsewhere it uses pynput,
which wraps Quartz on macOS and XTEST on X11.
"""

from __future__ import annotations

import ctypes
import platform
import sys
from typing import Optional, Tuple

from .protocol import ProtocolError


class InputBackend:
    """Everything the server needs from the operating system."""

    name = "none"

    def move_relative(self, dx: int, dy: int) -> None:
        raise NotImplementedError

    def move_absolute(self, nx: float, ny: float) -> None:
        raise NotImplementedError

    def scroll(self, dx: int, dy: int) -> None:
        raise NotImplementedError

    def button_down(self, btn: str) -> None:
        raise NotImplementedError

    def button_up(self, btn: str) -> None:
        raise NotImplementedError

    def tap_key(self, name: str) -> None:
        raise NotImplementedError

    def type_text(self, text: str) -> None:
        raise NotImplementedError

    def close(self) -> None:
        pass


class DummyBackend(InputBackend):
    """Records calls instead of moving the real pointer (``--dry-run``)."""

    name = "dry-run"

    def __init__(self, log=None) -> None:
        self.events: list[Tuple] = []
        self._log = log

    def _record(self, *event) -> None:
        self.events.append(event)
        if self._log:
            self._log("%s" % (" ".join(str(part) for part in event),))

    def move_relative(self, dx: int, dy: int) -> None:
        self._record("move", dx, dy)

    def move_absolute(self, nx: float, ny: float) -> None:
        self._record("moveabs", round(nx, 4), round(ny, 4))

    def scroll(self, dx: int, dy: int) -> None:
        self._record("scroll", dx, dy)

    def button_down(self, btn: str) -> None:
        self._record("down", btn)

    def button_up(self, btn: str) -> None:
        self._record("up", btn)

    def tap_key(self, name: str) -> None:
        self._record("key", name)

    def type_text(self, text: str) -> None:
        self._record("text", text)


# --------------------------------------------------------------------------- #
# Windows
# --------------------------------------------------------------------------- #

_WIN_VK = {
    "enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B,
    "space": 0x20, "delete": 0x2E,
    "up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27,
    "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22,
    "volup": 0xAF, "voldown": 0xAE, "mute": 0xAD,
    "play": 0xB3, "next": 0xB0, "prev": 0xB1,
}
_WIN_VK.update({f"f{i}": 0x6F + i for i in range(1, 13)})

#: Keys that live on the extended part of the keyboard and need the flag set,
#: otherwise numpad equivalents fire instead.
_WIN_EXTENDED = {
    "up", "down", "left", "right", "home", "end", "pageup", "pagedown",
    "delete", "volup", "voldown", "mute", "play", "next", "prev",
}


class WindowsBackend(InputBackend):
    name = "windows-sendinput"

    INPUT_MOUSE = 0
    INPUT_KEYBOARD = 1

    MOUSEEVENTF_MOVE = 0x0001
    MOUSEEVENTF_LEFTDOWN = 0x0002
    MOUSEEVENTF_LEFTUP = 0x0004
    MOUSEEVENTF_RIGHTDOWN = 0x0008
    MOUSEEVENTF_RIGHTUP = 0x0010
    MOUSEEVENTF_MIDDLEDOWN = 0x0020
    MOUSEEVENTF_MIDDLEUP = 0x0040
    MOUSEEVENTF_WHEEL = 0x0800
    MOUSEEVENTF_HWHEEL = 0x1000
    MOUSEEVENTF_ABSOLUTE = 0x8000
    MOUSEEVENTF_VIRTUALDESK = 0x4000

    KEYEVENTF_EXTENDEDKEY = 0x0001
    KEYEVENTF_KEYUP = 0x0002
    KEYEVENTF_UNICODE = 0x0004

    WHEEL_DELTA = 120

    def __init__(self) -> None:
        from ctypes import wintypes

        pointer_sized = ctypes.c_ulonglong if ctypes.sizeof(ctypes.c_void_p) == 8 else ctypes.c_ulong

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [
                ("dx", wintypes.LONG),
                ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD),
                ("dwExtraInfo", pointer_sized),
            ]

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [
                ("wVk", wintypes.WORD),
                ("wScan", wintypes.WORD),
                ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD),
                ("dwExtraInfo", pointer_sized),
            ]

        class _INPUTUNION(ctypes.Union):
            _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]

        class INPUT(ctypes.Structure):
            _anonymous_ = ("u",)
            _fields_ = [("type", wintypes.DWORD), ("u", _INPUTUNION)]

        self._MOUSEINPUT = MOUSEINPUT
        self._KEYBDINPUT = KEYBDINPUT
        self._INPUT = INPUT
        self._user32 = ctypes.WinDLL("user32", use_last_error=True)
        self._user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
        self._user32.SendInput.restype = wintypes.UINT

        # Without this, absolute moves and pointer coordinates are reported in
        # scaled units on high-DPI displays.
        try:
            ctypes.WinDLL("shcore").SetProcessDpiAwareness(2)
        except Exception:
            try:
                self._user32.SetProcessDPIAware()
            except Exception:
                pass

    def _send(self, *inputs) -> None:
        count = len(inputs)
        array = (self._INPUT * count)(*inputs)
        sent = self._user32.SendInput(count, array, ctypes.sizeof(self._INPUT))
        if sent != count:
            raise OSError(ctypes.get_last_error(), "SendInput was blocked")

    def _mouse(self, flags: int, dx: int = 0, dy: int = 0, data: int = 0):
        event = self._INPUT()
        event.type = self.INPUT_MOUSE
        event.mi = self._MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
        return event

    def _key(self, vk: int, scan: int, flags: int):
        event = self._INPUT()
        event.type = self.INPUT_KEYBOARD
        event.ki = self._KEYBDINPUT(vk, scan, flags, 0, 0)
        return event

    def move_relative(self, dx: int, dy: int) -> None:
        self._send(self._mouse(self.MOUSEEVENTF_MOVE, dx, dy))

    def move_absolute(self, nx: float, ny: float) -> None:
        flags = self.MOUSEEVENTF_MOVE | self.MOUSEEVENTF_ABSOLUTE | self.MOUSEEVENTF_VIRTUALDESK
        self._send(self._mouse(flags, int(nx * 65535), int(ny * 65535)))

    def scroll(self, dx: int, dy: int) -> None:
        events = []
        if dy:
            events.append(self._mouse(self.MOUSEEVENTF_WHEEL, data=dy * self.WHEEL_DELTA))
        if dx:
            events.append(self._mouse(self.MOUSEEVENTF_HWHEEL, data=dx * self.WHEEL_DELTA))
        if events:
            self._send(*events)

    _DOWN = {"l": MOUSEEVENTF_LEFTDOWN, "m": MOUSEEVENTF_MIDDLEDOWN, "r": MOUSEEVENTF_RIGHTDOWN}
    _UP = {"l": MOUSEEVENTF_LEFTUP, "m": MOUSEEVENTF_MIDDLEUP, "r": MOUSEEVENTF_RIGHTUP}

    def button_down(self, btn: str) -> None:
        self._send(self._mouse(self._DOWN[btn]))

    def button_up(self, btn: str) -> None:
        self._send(self._mouse(self._UP[btn]))

    def tap_key(self, name: str) -> None:
        vk = _WIN_VK.get(name)
        if vk is None:
            raise ProtocolError(f"unsupported key {name!r}")
        flags = self.KEYEVENTF_EXTENDEDKEY if name in _WIN_EXTENDED else 0
        self._send(self._key(vk, 0, flags), self._key(vk, 0, flags | self.KEYEVENTF_KEYUP))

    def type_text(self, text: str) -> None:
        # KEYEVENTF_UNICODE takes one UTF-16 code unit per event, so characters
        # outside the BMP go out as their two surrogates.
        raw = text.encode("utf-16-le")
        events = []
        for index in range(0, len(raw), 2):
            code = raw[index] | (raw[index + 1] << 8)
            events.append(self._key(0, code, self.KEYEVENTF_UNICODE))
            events.append(self._key(0, code, self.KEYEVENTF_UNICODE | self.KEYEVENTF_KEYUP))
        # SendInput takes a bounded array; chunk long strings.
        for start in range(0, len(events), 64):
            self._send(*events[start:start + 64])


# --------------------------------------------------------------------------- #
# macOS / Linux via pynput
# --------------------------------------------------------------------------- #

class PynputBackend(InputBackend):
    name = "pynput"

    def __init__(self) -> None:
        from pynput.keyboard import Controller as KeyboardController, Key
        from pynput.mouse import Button, Controller as MouseController

        self._mouse = MouseController()
        self._keyboard = KeyboardController()
        self._buttons = {"l": Button.left, "m": Button.middle, "r": Button.right}
        self._keys = {
            "enter": Key.enter, "backspace": Key.backspace, "tab": Key.tab,
            "esc": Key.esc, "space": Key.space, "delete": Key.delete,
            "up": Key.up, "down": Key.down, "left": Key.left, "right": Key.right,
            "home": Key.home, "end": Key.end,
            "pageup": Key.page_up, "pagedown": Key.page_down,
        }
        for index in range(1, 13):
            self._keys[f"f{index}"] = getattr(Key, f"f{index}")
        # Media keys only exist on some platforms; skip the ones that don't.
        for wire, attr in (
            ("volup", "media_volume_up"), ("voldown", "media_volume_down"),
            ("mute", "media_volume_mute"), ("play", "media_play_pause"),
            ("next", "media_next"), ("prev", "media_previous"),
        ):
            key = getattr(Key, attr, None)
            if key is not None:
                self._keys[wire] = key

    def move_relative(self, dx: int, dy: int) -> None:
        self._mouse.move(dx, dy)

    def move_absolute(self, nx: float, ny: float) -> None:
        width, height = self._screen_size()
        self._mouse.position = (int(nx * width), int(ny * height))

    def _screen_size(self) -> Tuple[int, int]:
        cached = getattr(self, "_size", None)
        if cached:
            return cached
        size = (1920, 1080)
        try:
            if sys.platform == "darwin":
                from AppKit import NSScreen  # type: ignore

                frame = NSScreen.mainScreen().frame()
                size = (int(frame.size.width), int(frame.size.height))
            else:
                from Xlib.display import Display  # type: ignore

                screen = Display().screen()
                size = (screen.width_in_pixels, screen.height_in_pixels)
        except Exception:
            pass
        self._size = size
        return size

    def scroll(self, dx: int, dy: int) -> None:
        # pynput uses the same sign convention as a physical wheel, which is
        # what the protocol carries, so the deltas pass straight through.
        self._mouse.scroll(dx, dy)

    def button_down(self, btn: str) -> None:
        self._mouse.press(self._buttons[btn])

    def button_up(self, btn: str) -> None:
        self._mouse.release(self._buttons[btn])

    def tap_key(self, name: str) -> None:
        key = self._keys.get(name)
        if key is None:
            raise ProtocolError(f"unsupported key {name!r} on this platform")
        self._keyboard.tap(key)

    def type_text(self, text: str) -> None:
        self._keyboard.type(text)


def create_backend(dry_run: bool = False, log=None) -> InputBackend:
    """Pick the best backend for this machine."""
    if dry_run:
        return DummyBackend(log=log)
    if platform.system() == "Windows":
        return WindowsBackend()
    try:
        return PynputBackend()
    except ImportError as exc:
        raise SystemExit(
            "This platform needs pynput for input control.\n"
            "    python3 -m pip install -r requirements.txt\n"
            f"(import failed: {exc})"
        )
