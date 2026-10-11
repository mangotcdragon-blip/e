#!/usr/bin/env python3
"""
Generates the per-program entry files of the shader pack.

OptiFine and Iris look up programs by file name (gbuffers_terrain.fsh, ...), and
per dimension in world-1/ (Nether) and world1/ (End). All real code lives in
shaders/program/; each entry file only sets #version, the dimension and program
macros, and includes the shared source.
"""
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "Solstice", "shaders")

# name: (vertex source, fragment source, [defines])
PROGRAMS = {
    "shadow":                ("shadow.vsh", "shadow.fsh", []),
    "gbuffers_basic":        ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_BASIC"]),
    "gbuffers_textured":     ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_TEXTURED"]),
    "gbuffers_textured_lit": ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_TEXTURED"]),
    "gbuffers_skybasic":     ("gbuffers_sky.vsh", "gbuffers_sky.fsh", ["SKY_BASIC"]),
    "gbuffers_skytextured":  ("gbuffers_sky.vsh", "gbuffers_sky.fsh", ["SKY_TEXTURED"]),
    "gbuffers_clouds":       ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_CLOUDS"]),
    "gbuffers_terrain":      ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_TERRAIN"]),
    "gbuffers_block":        ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_BLOCK"]),
    "gbuffers_beaconbeam":   ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_BEACON"]),
    "gbuffers_entities":     ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_ENTITIES"]),
    "gbuffers_armor_glint":  ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_OVERLAY"]),
    "gbuffers_spidereyes":   ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_SPIDEREYES"]),
    "gbuffers_hand":         ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_HAND"]),
    "gbuffers_damagedblock": ("gbuffers_main.vsh", "gbuffers_main.fsh", ["PROGRAM_OVERLAY"]),
    "gbuffers_water":        ("gbuffers_translucent.vsh", "gbuffers_translucent.fsh", ["PROGRAM_WATER"]),
    "gbuffers_hand_water":   ("gbuffers_translucent.vsh", "gbuffers_translucent.fsh", ["PROGRAM_HAND_WATER"]),
    "gbuffers_weather":      ("gbuffers_weather.vsh", "gbuffers_weather.fsh", []),
    "deferred":              ("fullscreen.vsh", "deferred.fsh", []),
    "deferred1":             ("fullscreen.vsh", "deferred1.fsh", []),
    "composite":             ("fullscreen.vsh", "composite.fsh", []),
    "composite1":            ("fullscreen.vsh", "composite1.fsh", []),
    "composite2":            ("fullscreen.vsh", "composite2.fsh", []),
    "composite3":            ("fullscreen.vsh", "composite3.fsh", []),
    "final":                 ("fullscreen.vsh", "final.fsh", []),
}

DIMENSIONS = {
    "": "WORLD_OVERWORLD",
    "world-1": "WORLD_NETHER",
    "world1": "WORLD_END",
}


def entry(dimension_macro, defines, stage_macro, source):
    lines = ["#version 330 compatibility", "", f"#define {dimension_macro}", f"#define {stage_macro}"]
    lines += [f"#define {d}" for d in defines]
    lines += ["", f'#include "/program/{source}"', ""]
    return "\n".join(lines)


def main():
    count = 0
    for folder, dim in DIMENSIONS.items():
        out_dir = os.path.join(ROOT, folder)
        os.makedirs(out_dir, exist_ok=True)
        for name, (vsh, fsh, defines) in PROGRAMS.items():
            for ext, stage, src in (("vsh", "VERTEX_SHADER", vsh), ("fsh", "FRAGMENT_SHADER", fsh)):
                with open(os.path.join(out_dir, f"{name}.{ext}"), "w", newline="\n") as f:
                    f.write(entry(dim, defines, stage, src))
                count += 1
    print(f"wrote {count} entry files")


if __name__ == "__main__":
    main()
