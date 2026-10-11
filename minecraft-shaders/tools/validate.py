#!/usr/bin/env python3
"""
Compiles every program of the pack with glslangValidator, the way OptiFine/Iris
would see it: #include "/..." is resolved from the shaders/ root and the loader's
built-in macros are defined. Optionally re-runs with option variants so code behind
#ifdefs is checked too.

usage: python3 tools/validate.py [--variants]
"""
import os, re, subprocess, sys, tempfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "Solstice", "shaders")

LOADER_MACROS = """#define MC_VERSION 12101
#define MC_GL_VERSION 460
#define MC_GLSL_VERSION 460
#define MC_RENDER_STAGE_NONE 0
#define MC_RENDER_STAGE_SKY 1
#define MC_RENDER_STAGE_SUNSET 2
#define MC_RENDER_STAGE_CUSTOM_SKY 3
#define MC_RENDER_STAGE_SUN 4
#define MC_RENDER_STAGE_MOON 5
#define MC_RENDER_STAGE_STARS 6
#define MC_RENDER_STAGE_VOID 7
"""

# Option variants: (description, {regex on settings.glsl: replacement})
VARIANTS = [
    ("defaults", {}),
    ("labPBR + toggles off", {
        r"#define PBR_MODE 0": "#define PBR_MODE 1",
        r"#define TAA\n": "//#define TAA\n",
        r"#define SSAO\n": "//#define SSAO\n",
        r"#define SSR\n": "//#define SSR\n",
        r"#define VOLUMETRIC_CLOUDS\n": "//#define VOLUMETRIC_CLOUDS\n",
        r"#define VOLUMETRIC_LIGHT\n": "//#define VOLUMETRIC_LIGHT\n",
        r"#define BLOOM\n": "//#define BLOOM\n",
        r"#define COLORED_SHADOWS\n": "//#define COLORED_SHADOWS\n",
        r"#define AUTO_EXPOSURE\n": "//#define AUTO_EXPOSURE\n",
        r"#define TONEMAP 1": "#define TONEMAP 0",
    }),
    ("hable", {r"#define TONEMAP 1": "#define TONEMAP 2", r"#define WATER_WAVES\n": "//#define WATER_WAVES\n"}),
]


def expand(path, settings_override, seen=None):
    seen = seen if seen is not None else set()
    out = []
    with open(path) as f:
        text = f.read()
    if os.path.basename(path) == "settings.glsl":
        for pat, rep in settings_override.items():
            text = re.sub(pat, rep, text)
    for line in text.split("\n"):
        m = re.match(r'\s*#include\s+"([^"]+)"', line)
        if m:
            inc = m.group(1)
            inc_path = os.path.join(ROOT, inc.lstrip("/")) if inc.startswith("/") else os.path.join(os.path.dirname(path), inc)
            out.append(expand(os.path.normpath(inc_path), settings_override, seen))
        else:
            out.append(line)
    return "\n".join(out)


def compile_one(path, override):
    src = expand(path, override)
    lines = src.split("\n")
    # loader macros go right after #version
    src = lines[0] + "\n" + LOADER_MACROS + "\n".join(lines[1:])
    stage = "vert" if path.endswith(".vsh") else "frag"
    with tempfile.NamedTemporaryFile("w", suffix="." + stage, delete=False) as tmp:
        tmp.write(src)
        tmp_path = tmp.name
    res = subprocess.run(["glslangValidator", tmp_path], capture_output=True, text=True)
    os.unlink(tmp_path)
    return res.returncode == 0, res.stdout + res.stderr


def main():
    variants = VARIANTS if "--variants" in sys.argv else VARIANTS[:1]
    failures = 0
    total = 0
    for desc, override in variants:
        for folder in ("", "world-1", "world1"):
            d = os.path.join(ROOT, folder)
            for name in sorted(os.listdir(d)):
                if not (name.endswith(".vsh") or name.endswith(".fsh")):
                    continue
                total += 1
                ok, log = compile_one(os.path.join(d, name), override)
                if not ok:
                    failures += 1
                    print(f"[{desc}] FAIL {os.path.join(folder, name)}")
                    print("\n".join(l for l in log.splitlines() if "ERROR" in l or "error" in l)[:3000])
    print(f"{total - failures}/{total} programs compiled")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
