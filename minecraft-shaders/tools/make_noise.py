#!/usr/bin/env python3
"""
Generates Solstice/shaders/tex/noise.png: 256x256 RGBA white noise where
G(x, y) = R(x + 37, y) (wrapping). lib/noise.glsl relies on that relation to
read two z-slices of a 3D value noise with one lookup. B is independent noise.
"""
import os
import numpy as np
from PIL import Image

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "Solstice", "shaders", "tex", "noise.png")
rng = np.random.default_rng(1337)
r = rng.integers(0, 256, (256, 256), dtype=np.uint8)          # indexed [y, x]
g = np.roll(r, -37, axis=1)                                    # g[y, x] = r[y, x + 37]
b = rng.integers(0, 256, (256, 256), dtype=np.uint8)
a = np.full((256, 256), 255, dtype=np.uint8)
# The offset is horizontal only, so the relation holds whichever way the
# loader orders the rows.
img = np.stack([r, g, b, a], axis=-1)
Image.fromarray(img, "RGBA").save(out)
print("wrote", out)
