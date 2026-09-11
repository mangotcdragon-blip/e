# WiFi Mouse

Turn an Android phone into a mouse for your PC over Wi-Fi. The phone sends
pointer, scroll, click and keystroke events to a small server on the computer;
the server replays them as real input.

Two ways to drive it:

- **Trackpad mode** — the phone lies flat and you use it like a laptop
  touchpad. Precise, and the lowest latency.
- **Camera mouse mode** — hold the phone like a mouse and slide it around. The
  rear camera watches the surface go past, the way the sensor in an optical
  mouse does, with left and right buttons under your fingers and a scroll wheel
  between them.

```
  Android app  ── UDP, port 7654 ──▶  wifimouse server  ──▶  SendInput / Quartz / XTEST
```

- **Windows needs no dependencies.** The server drives `SendInput` through
  `ctypes`, so a stock Python install is enough.
- **No cloud, no accounts, no internet.** Packets go straight from the phone to
  the PC on your own network.
- **Auto-discovery.** The app broadcasts, the PC answers, so there is no IP
  address to type in.

## 1. Start the server on your PC

Requires Python 3.9 or newer.

**Windows** — double-click `server/start-windows.bat`, or:

```console
cd server
py -3 wifimouse_server.py
```

**macOS / Linux**:

```console
cd server
./start-unix.sh
```

macOS asks for Accessibility permission the first time (System Settings →
Privacy & Security → Accessibility). On Linux the server needs an X11 session;
on Wayland, `pynput` cannot inject input.

The server prints the address the phone should connect to:

```
  WiFi Mouse server 1.0.0 — DESKTOP-7QK2
  input backend : windows-sendinput
  listening on  : UDP 0.0.0.0:7654
  this PC is at : 192.168.1.24
  pairing code  : (none — any phone on this network may connect)
```

The first run on Windows pops up a firewall prompt: allow it on **private
networks**. If you dismissed it, add an inbound rule for UDP port 7654.

### Useful options

| Option | What it does |
| --- | --- |
| `-t CODE`, `--token CODE` | Require a pairing code; enter the same code in the app |
| `-p PORT`, `--port PORT` | Listen on a different UDP port |
| `--no-discovery` | Stop answering "find my PC" broadcasts |
| `--dry-run -v` | Print events instead of moving the pointer — handy for testing |
| `--bind ADDR` | Listen on one interface only |

## 2. Install the app on your phone

Grab `app-debug.apk` from the latest [build run](../../actions) (the
**wifimouse-debug-apk** artifact), copy it to the phone and open it. Android
asks you to allow installing from unknown sources.

To build it yourself:

```console
cd android
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## 3. Connect

Put the phone on the same Wi-Fi as the PC, open the app and tap **⋮ → Find my
PC**. It fills in the address itself. If discovery is blocked on your network
(common on guest Wi-Fi and some mesh routers), type the address from the server
banner into **Settings → PC address**.

## Camera mouse mode

Open it from **⋮ → Camera mouse**. Hold the phone screen-up like a mouse and
slide it over a surface with some visible pattern — wood grain, fabric, printed
paper. The buttons sit at the top of the screen where your index and middle
fingers land, with a notched scroll wheel between them; pressing the wheel is a
middle click, and a flick sends it spinning.

The **Tracking** bar shows how well the camera can see. When it stays low:

- Turn the **light** on for a dim surface.
- Tilt the phone so the camera sits slightly above the surface. Phone cameras
  cannot focus on something pressed right against the lens, which is the main
  thing separating this from a real mouse — a mouse has a lens designed for
  exactly that distance.
- Tap **Refocus**.
- Move to a surface with more pattern. A bare white desk or glass has nothing
  to track, and the app will hold the pointer still rather than let it drift.

It also works held in the air, pointed at the room, like a laser pointer.

**What to expect.** A phone camera delivers about 30 readings a second; the
sensor in a real mouse manages thousands. Camera mode feels heavier than the
trackpad and always will. It is genuinely useful for a lean-back machine across
the room, and good fun, but trackpad mode is the one to use for real work.

## Gestures

| Gesture | Action |
| --- | --- |
| Drag one finger | Move the pointer |
| Tap | Left click |
| Two-finger tap | Right click |
| Three-finger tap | Middle click |
| Drag two fingers | Scroll |
| Tap, then press and drag | Drag and drop |
| Press and hold, then drag | Drag and drop |

The **Left / Middle / Right** buttons stay held while your finger is on them, so
you can hold one and drag on the pad with another finger. The **Keyboard**
button opens a text field that types onto the PC, with Esc, Tab, arrows, Enter
and Backspace alongside it.

## Settings worth knowing

- **Pointer speed** and **acceleration** — acceleration keeps slow movements
  1:1 for precise aiming while letting a quick swipe cross the screen.
- **Natural scrolling** — content follows your fingers; turn it off for
  wheel-style scrolling.
- **Send clicks twice** — a click is sent as two identical packets, so a single
  dropped datagram cannot swallow it. The PC ignores the duplicate.
- **Pairing code** — must match the server's `--token`.

## How it works

One UDP datagram carries one ASCII command:

```
WM1 <token> <seq> <verb> [args...]

WM1 - 412 m 12.50 -4.25     relative pointer move, in pixels
WM1 - 413 s 0.00 1.50       scroll, in wheel notches
WM1 - 414 c l               left click
WM1 - 415 t hello%20world   type text (percent-encoded UTF-8)
```

UDP rather than TCP: a lost movement packet is worthless anyway — the next one,
8 ms later, already carries a newer position — and there is no retransmission
stall to add lag. The parts that *do* need reliability are handled explicitly:

- **Clicks and keystrokes** are sent twice; the server drops the duplicate by
  sequence number.
- **Reordered movement** is discarded, because a stale position is worse than
  none.
- **A lost "button up"** cannot leave the desktop stuck mid-drag: the server
  releases any held button after two seconds of silence, and the app sends a
  goodbye packet when it goes to the background.
- **Sub-pixel movement accumulates** instead of being rounded away, so slow
  precise dragging works.

Camera mode rides on the same `m` packets: the tracker measures how far the
picture moved between two frames, and the phone moved the opposite way.

**How the tracking works.** Each frame's luma plane is averaged down to 80x60,
and consecutive frames go through block matching — a patch from the middle of
the new frame is slid over the old one until it lines up. A coarse pass on a
half-size copy finds the rough offset, a fine pass refines it, and a parabola
fitted through the neighbouring scores gives sub-pixel precision. That is about
110k byte comparisons per frame, and it tracks movement up to 16 pixels per
frame. Every reading carries a confidence and a texture score; when either is
too low — a blank desk, a defocused blur, a jump too big to match — the frame
is dropped rather than allowed to fling the pointer somewhere random.

Full protocol reference: [`server/wifimouse/protocol.py`](server/wifimouse/protocol.py).

## Security

Anyone who can send UDP to the port can move your pointer, so this is built for
a home or office network you trust. Two things to know:

- Run the server with `--token` (and enter the same code in the app) if you do
  not trust everyone on the network. The code is checked on every packet.
- The traffic is not encrypted. It is plain UDP on your LAN, and typed text
  travels in it — do not use the keyboard panel for passwords on a network you
  do not control.

Stop the server when you are not using it; the pointer cannot be moved while it
is not running.

## Repository layout

```
android/    the Android app (Kotlin, minSdk 24)
  app/src/main/java/com/wifimouse/app/
    net/          protocol, UDP client, LAN discovery, connection controller
    ui/           trackpad view, scroll wheel, pointer maths
    vision/       camera frame handling and the motion tracker
server/     the desktop server (Python, stdlib-only on Windows)
  wifimouse/    protocol, input backends, UDP server, CLI
  tests/        protocol and end-to-end socket tests
```

## Development

```console
python3 -m pytest server/tests -q     # server tests
cd android && ./gradlew test          # app unit tests
cd android && ./gradlew assembleDebug # build the APK
```

Testing without a phone:

```console
python3 server/wifimouse_server.py --dry-run -v     # terminal 1
printf 'WM1 - 1 m 40 0' | nc -u -w1 127.0.0.1 7654  # terminal 2
```

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| "No PC answered" | Both devices on the same Wi-Fi? Firewall allowing inbound UDP 7654? Some guest networks block device-to-device traffic entirely. |
| Connects, but nothing moves | On macOS grant Accessibility permission; on Linux check you are on X11, not Wayland. |
| "Pairing code rejected" | The app's code must match the server's `--token` exactly. |
| Pointer drifts or feels heavy | Adjust pointer speed in Settings; turn acceleration off for a strictly 1:1 feel. |
| Camera mode tracks nothing | Check the Tracking bar. Needs a patterned, lit surface and a camera that can focus on it — see Camera mouse mode above. |
| Camera mode moves the wrong way vertically | Settings → Camera mouse → Invert vertical movement. |
| Scrolling goes the wrong way | Toggle natural scrolling in Settings. |
