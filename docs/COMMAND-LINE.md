# The command line

WorldPainter is a paint program, and painting needs a window. Plenty of what people do with it does not: looking at
what is in a map, getting one out into another tool, applying the same treatment to a folder full of maps, or doing any
of that on a build server or over ssh where there is no display at all.

`org.pepsoft.worldpainter.cli.WorldPainterCli` is a headless entry point for those things. It is part of WPCore and
needs no graphical environment.

## Building and running it

```
mvn -pl WPCore -am -P cli package -DskipTests
java -jar WPCore/target/WPCore-2.27.1-SNAPSHOT-jar-with-dependencies.jar help
```

The `cli` profile builds a single jar with everything in it. It is off by default because it is a large artifact and
only the command line needs it.

Without the profile, run it from the class path:

```
mvn -pl WPCore -am install -DskipTests
mvn -q -pl WPCore dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "WPCore/target/classes:$(cat cp.txt)" org.pepsoft.worldpainter.cli.WorldPainterCli help
```

Throughout this page, `worldpainter` stands for whichever of those you are using.

## Commands

### `info` — what is in a map

```
$ worldpainter info MyMap.world
World:      MyMap
Platform:   Minecraft 1.2 - 1.12
Dimensions: 3

  Surface (0 DETAIL)
    seed:      -6894864525167107490
    tiles:     25
    extent:    640 x 640 blocks, from x -256, z -256
    heights:   0 to 256
    surface:   59.3 to 74.9, mean 67.5
    water:     1.6% of the map
    terrains:  Grass 58%, Custom 1 15%, Custom 2 8%, Netherlike 7%, Mesa 6% and 2 more
```

Every dimension, sampled coarsely enough to stay quick on a large map.

### `describe` — a JSON description

```
worldpainter describe MyMap.world               # to standard output
worldpainter describe MyMap.world --output map.json
```

See [Readable exports](READABLE-EXPORTS.md) for what is in it. `--interval` and `--summary-columns` control how much
detail; `--dimension` picks a dimension.

### `export` — OBJ, JSON, ASCII and CSV

```
worldpainter export MyMap.world --directory out
worldpainter export MyMap.world --directory out --interval 4 --exaggeration 2 --formats obj
worldpainter export MyMap.world --directory out --area 0,0,512,512 --geometry blocky
```

Also described in [Readable exports](READABLE-EXPORTS.md).

### `blend` — soften boundaries and steps

```
worldpainter blend MyMap.world --output MyMap-blended.world
worldpainter blend MyMap.world --output out.world --mode combined --radius 12 --height-radius 4
```

Writes a new world file; the original is untouched. Without `--dimension`, every dimension in the world is blended.
Described in [Blending](BLENDING.md).

### `gpu` — what acceleration is available

```
worldpainter gpu
```

Lists the OpenCL devices, says which one WorldPainter would use, and verifies that it reproduces WorldPainter's noise
exactly. If the answer is no, it says so and explains why the device will not be used. Described in
[GPU acceleration](GPU-ACCELERATION.md).

## Options

Options may be written `--name value` or `--name=value`. Names are matched ignoring case, hyphens and underscores, so
`--height-radius`, `--heightRadius` and `--HEIGHT_RADIUS` are the same option. Flags can be turned off with a `no-`
prefix: `--no-water`.

An option the command does not recognise produces a warning naming it. A typo would otherwise quietly do nothing, which
on a command that rewrites a map is not something to discover afterwards.

### Common

| Option | Meaning |
| --- | --- |
| `--dimension <name>` | Which dimension to work on. Defaults to the surface. |
| `--gpu auto\|off\|force` | Whether to use a GPU. |
| `--gpu-device <name>` | Use the device whose name contains this. |
| `--gpu-preference dedicated-gpu\|any-gpu\|any-device` | Which kind of device to prefer. |
| `--verbose` | Print a stack trace when something goes wrong, and turn the log up to info. |

### `export` and `describe`

| Option | Meaning |
| --- | --- |
| `--directory <dir>` | Where to write. Defaults to the current directory. |
| `--name <name>` | Base name for the files. Defaults to the world file's name. |
| `--formats obj,json,txt,csv` | Which formats. `all` for everything. |
| `--output <file>` | For `describe`: write here instead of to standard output. |
| `--interval <n>` | Export every n'th column. |
| `--area x,z,width,length` | Only this part of the map, in block coordinates. |
| `--geometry smooth\|blocky` | Mesh style. |
| `--exaggeration <f>` | Multiply the heights. |
| `--no-water`, `--no-normals`, `--no-centre`, `--no-grids` | Leave things out. |
| `--summary-columns <n>` | How wide the ASCII map and grids in the JSON may be. |

### `blend`

| Option | Meaning |
| --- | --- |
| `--output <file.world>` | Where to write the blended map. Required. |
| `--mode organic\|speckled\|combined\|none` | How to blend terrain. |
| `--radius <n>` | How far a terrain may bleed across its boundary. |
| `--scale <f>` | Size of the features in an organic blend. |
| `--strength <f>` | 0 to 1. |
| `--no-boundaries-only` | Blend everywhere, not only near a boundary. |
| `--height-radius <n>` | Radius of the height smoothing. 0 turns it off. |
| `--height-strength <f>` | 0 to 1. |
| `--slope-threshold <f>` | Only smooth where the ground is steeper than this. |
| `--water-level` | Move water levels along with the terrain. |
| `--seed-offset <n>` | Change this for a different blend of the same map. |

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Success. |
| 1 | Something went wrong: a file that is not there, a map that will not load, a device that failed verification. |
| 2 | The command line itself was wrong. |
| 130 | Cancelled. |

## Scripting

Everything writes its results to standard output and its progress and problems to standard error, so output can be
piped:

```bash
for world in maps/*.world; do
    worldpainter export "$world" --directory "meshes" --interval 8 --formats obj || echo "failed: $world"
done
```

```bash
worldpainter describe MyMap.world | jq '.terrain[] | select(.fraction > 0.05) | .displayName'
```
