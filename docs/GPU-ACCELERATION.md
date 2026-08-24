# GPU acceleration

WorldPainter's export is dominated by one kind of work: deciding, for every block below the surface, what that block is
made of. The Resources layer walks a list of a dozen materials for each one, evaluating a three-dimensional Perlin noise
field per material until one of them comes out above its threshold. The Stone Mix subsurface evaluates three more. On a
map of any size that is billions of noise evaluations, all of them independent of each other and all of them running the
same code — which is exactly the kind of work a graphics card exists to do.

This fork moves that work to the GPU, through OpenCL.

## What runs on the GPU

| Work | Kernel | Roughly how much of an export |
| --- | --- | --- |
| The Resources layer (ores, dirt and gravel pockets) | `wp_resources_layer` | most of it |
| The Stone Mix subsurface (stone, granite, diorite, andesite, deepslate, tuff) | `wp_stone_mix` | a large part of the rest |
| Terrain blending | `wp_blend_terrain` | all of it |
| Height smoothing | `wp_blend_height` | all of it |

Everything else — assembling chunks, writing NBT, compressing region files — stays on the CPU, where it belongs. Those
are serial, I/O-bound jobs that a GPU would make no faster.

## The map does not change

This is the part that mattered most while building it. If an accelerated export placed even one different block, the
same map would depend on the machine that exported it, and nobody could reproduce anybody else's world. So:

- `perlin.cl` is a **transliteration** of `org.pepsoft.util.FastPerlin`, not a re-implementation. The lattice
  coordinates are floored in double precision, because the Java code samples at double precision. Every multiply-add
  the Java code performs with `Math.fma` is performed with `fma()` here. `FP_CONTRACT` is switched off so the compiler
  cannot fuse or refuse to fuse any of the others. `-cl-fast-relaxed-math` and `-cl-mad-enable` are never passed.
- Before WorldPainter trusts a device, it **verifies** it: `GpuPerlinNoise.selfTest()` samples the noise at 32,768
  pseudo-random coordinates across eight seeds and compares the raw bits against `org.pepsoft.util.PerlinNoise`. A
  device that disagrees anywhere is not used for anything.
- The tests check the same property from both ends: `PerlinNoiseKernelTest` compares the noise bit for bit,
  `TerrainKernelTest` compares the kernels' output against `Terrain.STONE_MIX` and against a transcription of the
  `ResourcesExporter` loop, and `ResourcesExporterAccelerationTest` and `SubsurfaceAccelerationTest` export a chunk
  with and without the GPU and compare every block.

There is exactly one documented exception. In `Terrain.STONE_MIX`, the four blocks between y = -4 and y = -1 are chosen
by drawing from a `java.util.Random` shared between every chunk and every export thread. That is not reproducible on the
CPU either — export the same map twice and those blocks differ — so there is nothing for a kernel to reproduce. The
kernel marks that band and the CPU generates it exactly as it always has.

## Choosing a device

By default WorldPainter looks for a **dedicated** graphics card: a device that reports itself as a GPU and that does
*not* share its memory with the host. That is the standard way to tell a real card from the integrated GPU in a laptop's
processor, and it means that on a machine with both, the discrete card wins. Some drivers report
`CL_DEVICE_HOST_UNIFIED_MEMORY` incorrectly, so a GPU with at least a gigabyte of its own memory also counts.

If there is no dedicated GPU, an integrated one is used; it is still much faster than the CPU for this. If there is no
GPU at all, or no OpenCL runtime, or the device fails verification, the export runs on the CPU exactly as before.

To see what is on a machine and what WorldPainter would pick:

```
worldpainter gpu
```

```
OpenCL devices (2):
  NVIDIA GeForce RTX 4070 (NVIDIA Corporation, NVIDIA CUDA) - 46 compute units @ 2475 MHz, 12281 MB dedicated memory  [dedicated]
  gfx1036 (Advanced Micro Devices, Inc., AMD Accelerated Parallel Processing) - 2 compute units @ 2200 MHz, 512 MB shared memory

Selected: NVIDIA GeForce RTX 4070 (NVIDIA Corporation, NVIDIA CUDA) - 46 compute units @ 2475 MHz, 12281 MB dedicated memory
Verifying that it reproduces WorldPainter's noise exactly... yes (41 ms)
Exports and blending will use this device.
```

## Settings

Every setting can be given as a system property, so a user with a misbehaving driver can change it without a new build,
and as an option to the command line.

| System property | Command line | Values | Default |
| --- | --- | --- | --- |
| `org.pepsoft.worldpainter.gpu.mode` | `--gpu` | `auto`, `off`, `force` | `auto` |
| `org.pepsoft.worldpainter.gpu.devicePreference` | `--gpu-preference` | `dedicated-gpu`, `any-gpu`, `any-device` | `dedicated-gpu` |
| `org.pepsoft.worldpainter.gpu.deviceName` | `--gpu-device` | part of a device's name | — |
| `org.pepsoft.worldpainter.gpu.requireFp64` | — | `true`, `false` | `true` |
| `org.pepsoft.worldpainter.gpu.minimumBatchSize` | — | blocks | 8192 |

- **`mode`** — `auto` uses a GPU when there is a usable one. `off` never does. `force` fails loudly instead of falling
  back, which is what you want when you are trying to work out why acceleration is not happening.
- **`devicePreference`** — `any-device` includes OpenCL implementations that run on the CPU, such as PoCL or Intel's
  runtime. Those are not faster than WorldPainter's own threaded CPU path, but they are useful for testing kernels on a
  machine with no graphics card, which is how this fork's tests run on a build server.
- **`requireFp64`** — the noise is sampled in double precision, so a device without `cl_khr_fp64` cannot reproduce it
  exactly and is rejected. Turning this off would let such a device be used, at the cost of the guarantee that the map
  is identical. There is no reason to.
- **`minimumBatchSize`** — below this many blocks a chunk is generated on the CPU, because the transfer and the kernel
  launch cost more than the arithmetic saved.

Run with `-Dorg.slf4j.simpleLogger.log.org.pepsoft.worldpainter.gpu=debug` (or the equivalent in your logging
configuration) to see the device discovery decisions.

## Installing an OpenCL runtime

OpenCL comes with the graphics driver, so on a machine with a current NVIDIA, AMD or Intel driver there is usually
nothing to install.

On Linux the *ICD loader* — the small library that finds the driver — is packaged separately, as `ocl-icd-libopencl1`
on Debian and Ubuntu, `ocl-icd` on Fedora and Arch. Note that it installs `libOpenCL.so.1`; the unversioned
`libOpenCL.so` is in the development package and is frequently absent. WorldPainter probes for both, so the development
package is not needed.

To test on a machine with no graphics card at all, install PoCL (`pocl-opencl-icd` on Debian and Ubuntu), which is a
complete OpenCL implementation that runs on the processor, and pass `--gpu-preference any-device`.

## How it works

`GpuContext` finds a device, creates one context on it, and hands out a command queue and a set of kernel objects per
thread — contexts, programs and buffers are safe to share between threads, but command queues and kernels are not.

For each chunk, the exporter describes its 256 columns to the device as a small array of integers: world coordinates,
the range of y values to fill, and (for the Resources layer) the value of the layer there. One work item handles one
block. What comes back is one byte per block: an index into a palette the host assembled. The host turns those indices
into blocks, which keeps all the version-dependent detail — deepslate ore variants, nether gold — in Java where it
already lives, and keeps the kernel to pure arithmetic.

Nothing on this path can fail an export. Every entry point catches everything, including errors thrown from the native
layer by a half-installed driver. A failure tears the context down, logs it once, and puts the rest of the session back
on the CPU.

## What is not accelerated, and why

- **Writing region files.** Compression and disk I/O; a GPU cannot help.
- **Chunk assembly and NBT serialisation.** Pointer-chasing over an object graph, inherently serial.
- **Trees, caves, tunnels and the other second-pass layers.** These place objects with irregular, data-dependent
  extents; they are not a good fit for the model above and are a much smaller share of the time. Caves and caverns are
  the most promising candidate for a future kernel.
- **The map preview and the editor's rendering.** A separate problem, and already fast enough to be interactive.

## Where the GUI would hook in

The acceleration is entirely inside WPCore and needs no user interface to work: it turns itself on when there is a
device. A preference in the GUI would set `GpuSettings.setMode` and `GpuSettings.setDevicePreference` from a
`Configuration` field, and a settings panel could list `GpuContext.enumerateDevices()`. That change is not in this fork
— WPGUI depends on commercially licensed libraries that are not redistributable, so it cannot be built or tested here.
