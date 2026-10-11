#!/usr/bin/env python3
"""
Builds an installable zip: <out>/Solstice.zip with shaders/ at the zip root,
which is the layout OptiFine and Iris expect in the shaderpacks folder.

usage: python3 tools/package.py [OUT_DIR]   (default: minecraft-shaders/dist)
"""
import os, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.normpath(os.path.join(HERE, "..", "Solstice"))


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "dist")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "Solstice.zip")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(PACK):
            for name in sorted(files):
                path = os.path.join(root, name)
                z.write(path, os.path.relpath(path, PACK))
    print("wrote", os.path.normpath(out))


if __name__ == "__main__":
    main()
