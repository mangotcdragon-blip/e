# WorldPainter — GPU export, blending and readable exports

WorldPainter is an interactive map generator for Minecraft. It lets you "paint" landscapes using the tools of a paint
program: sculpt and mould the terrain, paint materials, trees, snow and ice onto it, and much more.

This is a fork of [Captain-Chaos/WorldPainter](https://github.com/Captain-Chaos/WorldPainter) that adds four things:

| | |
| --- | --- |
| **[GPU acceleration](docs/GPU-ACCELERATION.md)** | The part of an export that does nearly all of the arithmetic — deciding what every block below the surface is made of — runs on a dedicated graphics card through OpenCL, and produces exactly the same map as the CPU does. |
| **[Blending](docs/BLENDING.md)** | Soften the straight terrain boundaries and stair-stepped cliffs a painted map inevitably has, over a whole map at once. |
| **[Readable exports](docs/READABLE-EXPORTS.md)** | Write a map as a Wavefront OBJ mesh, a JSON description, an ASCII map, or a CSV table: formats that Blender, a spreadsheet, a script, a language model, or Notepad can all read. |
| **[A command line](docs/COMMAND-LINE.md)** | Inspect, export and blend maps headlessly, on a machine with no display. |

Everything is in `WPCore`, the part of WorldPainter that has no user interface, so all of it works from a script or a
build server as well as from the application.

## Quick start

```
mvn -pl WPCore -am -P cli package -DskipTests
alias worldpainter='java -jar WPCore/target/WPCore-2.27.1-SNAPSHOT-jar-with-dependencies.jar'

worldpainter gpu                                        # what acceleration is available here
worldpainter info MyMap.world                           # what is in a map
worldpainter export MyMap.world --directory out         # OBJ, JSON, ASCII and CSV
worldpainter blend MyMap.world --output blended.world   # soften boundaries and steps
```

## GPU acceleration in one paragraph

The Resources layer evaluates a Perlin noise field per candidate material for every block below the surface; the Stone
Mix subsurface evaluates three more. On a large map that is billions of independent evaluations of the same code, which
is what a graphics card is for. WorldPainter now hands each chunk to the GPU as 256 column descriptions and gets back
one byte per block saying what goes there.

The kernels are transliterations of the Java, not re-implementations — the same `fma` discipline, the same double
precision floors, no fast-math — and before WorldPainter trusts a device it samples the noise at 32,768 coordinates on
it and compares the raw bits against the CPU. A device that disagrees anywhere is not used. If there is no OpenCL
runtime, no suitable device, or a driver error mid-export, the export silently carries on as it always did. See
[docs/GPU-ACCELERATION.md](docs/GPU-ACCELERATION.md).

## Licence

WorldPainter is free software under the [GPL version 3](LICENSE), and so is this fork. If you distribute it, in original
or modified form, you have to distribute the source of your changes under the same licence.

The upstream `LICENSE` is unchanged. Upstream is at <https://github.com/Captain-Chaos/WorldPainter> and the project's
home page is at <https://www.worldpainter.net/>.

## Building

See [BUILDING.md](BUILDING.md). In short: `WPCore` builds with nothing but Maven and a JDK, and that is where all the
work described above lives. `WPGUI` additionally needs commercially licensed libraries that cannot be redistributed, so
it is not built or tested here; nothing in this fork changes it.

## Relation to upstream

This fork is based on upstream commit `884c67c` (2.27.1-SNAPSHOT). Changes to files that already existed are small and
deliberate:

- `WorldPainterChunkFactory` and `ResourcesExporter` gained a GPU path alongside the existing CPU one, and fall back to
  it whenever the GPU is unavailable or declines the work.
- `Dimension` gained `getBlockExtent()`, because `getExtent()` returns tile coordinates and the difference is easy to
  get wrong.
- The Lombok version was raised from 1.18.22 to 1.18.34, because 1.18.22 cannot run on a JDK 16 or newer compiler.
- LWJGL was added as a dependency of `WPCore`, for its OpenCL bindings.

Everything else is new: `org.pepsoft.worldpainter.gpu`, `org.pepsoft.worldpainter.blending`,
`org.pepsoft.worldpainter.exporting.readable` and `org.pepsoft.worldpainter.cli`.
