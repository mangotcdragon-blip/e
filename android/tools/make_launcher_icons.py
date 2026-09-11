#!/usr/bin/env python3
"""Generate the launcher icons.

Adaptive icons (API 26+) are vector XML, so they are written once and scale by
themselves.  API 24 and 25 launchers need real bitmaps, which this script
rasterises at 4x and downsamples for clean edges.

    python3 tools/make_launcher_icons.py
"""

from __future__ import annotations

import pathlib

from PIL import Image, ImageDraw

RES = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

BRAND = (62, 99, 221, 255)
WHITE = (255, 255, 255, 255)

#: A classic pointer arrow, in a 17 x 28 space with the tip at the origin.
ARROW = [(0, 0), (0, 24), (6, 18), (10, 28), (14, 26), (10, 16), (17, 16)]

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

SUPERSAMPLE = 4


def placed_arrow(canvas: int, scale: float) -> list[tuple[float, float]]:
    """Centre the arrow on a square canvas at the given scale."""
    width = max(x for x, _ in ARROW) * scale
    height = max(y for _, y in ARROW) * scale
    left = (canvas - width) / 2
    top = (canvas - height) / 2
    return [(left + x * scale, top + y * scale) for x, y in ARROW]


def render(size: int, round_icon: bool) -> Image.Image:
    big = size * SUPERSAMPLE
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    if round_icon:
        draw.ellipse((0, 0, big - 1, big - 1), fill=BRAND)
    else:
        draw.rounded_rectangle((0, 0, big - 1, big - 1), radius=big * 0.22, fill=BRAND)

    draw.polygon(placed_arrow(big, big / 28 * 0.52), fill=WHITE)
    return image.resize((size, size), Image.LANCZOS)


def write_bitmaps() -> None:
    for folder, size in DENSITIES.items():
        target = RES / folder
        target.mkdir(parents=True, exist_ok=True)
        render(size, round_icon=False).save(target / "ic_launcher.png")
        render(size, round_icon=True).save(target / "ic_launcher_round.png")
        print(f"{folder}/ic_launcher.png  {size}x{size}")


def write_vectors() -> None:
    # The adaptive foreground lives in a 108dp viewport whose middle 66dp is the
    # only part guaranteed to be visible, so the arrow is kept well inside it.
    points = placed_arrow(108, 1.9)
    path = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in points) + " Z"

    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "drawable" / "ic_launcher_foreground.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        f'    <path android:fillColor="#FFFFFFFF" android:pathData="{path}" />\n'
        "</vector>\n"
    )

    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        (RES / "mipmap-anydpi-v26" / f"{name}.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/brand" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
            "</adaptive-icon>\n"
        )
    print("adaptive icon + vector foreground written")


if __name__ == "__main__":
    write_vectors()
    write_bitmaps()
