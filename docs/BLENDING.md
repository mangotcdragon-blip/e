# Blending

A painted map gives itself away at its edges. Terrain boundaries follow the brush stroke, so a coastline meets the sea
in a smooth arc no real coastline has, and sand meets grass along a line you can see from orbit. Height brushes leave
stair steps. Fixing either by hand over a whole map is hours of work.

`org.pepsoft.worldpainter.blending` does both in one pass.

## Terrain blending

The idea is one trick applied uniformly: instead of reading the terrain of the column being painted, read the terrain of
a nearby column, displaced by a small amount.

Where the terrain is uniform, that changes nothing — a displaced grass column is still grass. Along a boundary it makes
the two terrains interlock. Where the displacement comes from is what gives each mode its character.

| Mode | Displacement | What it looks like |
| --- | --- | --- |
| `organic` | a smooth (Perlin) field, so neighbouring columns move in nearly the same direction | the edge keeps its shape but grows wandering fingers and inlets, the way a real coastline or forest edge does |
| `speckled` | independent per column, cubed so small displacements are far more likely than large ones | the speckled gradient you would paint by hand with a low-opacity brush; sand thinning into grass |
| `combined` | a smooth displacement with a little per-column jitter on top | organic fingers with softened edges of their own; the most natural, and on a GPU no slower |
| `none` | — | leaves terrain alone, for when you only want the height smoothing |

Options:

- **radius** — how far a terrain may bleed across its boundary, in blocks. 8 by default. This is also the width of the
  transition band.
- **scale** — the size of the features in the noise that drives an organic blend, in blocks. 24 by default. Larger
  values give longer, lazier fingers; smaller values give a busier, more fretted edge.
- **strength** — 0 to 1, how much of the full displacement to apply.
- **boundaries only** — on by default. A column with no different terrain within the radius is left exactly as it was.
  This is not just an optimisation: without it the blend would reshuffle the inside of a large uniform area, which
  cannot change the result but does mark every tile as modified.
- **blend water level** — off by default. When on, a column that takes its terrain from a neighbour takes that
  neighbour's water level too. Leave it off unless you want shorelines to move.

Blending never invents terrain. Every column's new terrain came from a column within the radius that already had it, so
a blend cannot introduce a terrain that is not on the map. There is a test for this.

## Height smoothing

A Gaussian blur of the height map, with two controls that make it usable rather than destructive:

- **strength** — 0 to 1, how far to move each height towards the smoothed value. 0.5 by default: a noticeable softening
  that keeps the shape of the landscape.
- **slope threshold** — only smooth a column where the ground around it rises or falls by more than this many blocks.
  1.5 by default, which confines the smoothing to the cliffs and terrace edges that need it and leaves flat ground
  perfectly flat. Set it to 0 to smooth everything.

The kernel is normalised, so smoothing cannot raise or lower the map as a whole.

## Edges of the map

A column is only blended when every column it would have to read exists. That leaves an untouched border as wide as the
radius, rather than blending against columns that are not there, which would pull the edge of the map inwards. With the
default radius of 8 that border is invisible; with a radius of 64 it is not, which is one reason not to use a radius of
64.

## Reproducibility

Blending is a pure function of the map, the dimension seed, and the settings. Blending the same map twice with the same
settings gives the same result, on the same machine or another one, with a GPU or without. `seedOffset` exists to give
you a different blend of the same map without having to change anything else.

## From the command line

```
worldpainter blend MyMap.world --output MyMap-blended.world
```

blends every dimension with the defaults — an eight-block organic terrain blend and a light height smoothing — and
writes a new world file. The original is not touched.

```
worldpainter blend MyMap.world --output out.world \
    --mode combined --radius 12 --scale 32 --strength 0.8 \
    --height-radius 4 --height-strength 0.75 --slope-threshold 1.0 \
    --dimension Surface
```

`--dimension` restricts it to one dimension; without it, every dimension in the world is blended.

## From code

```java
final BlendSettings settings = BlendSettings.builder()
        .terrainMode(BlendMode.COMBINED)
        .terrainRadius(12)
        .heightRadius(3)
        .heightStrength(0.6f)
        .build();

final BlendReport report = new Blender(settings).blend(dimension, progressReceiver);
System.out.println(report);   // 262,144 columns in 94 ms on the GPU: 18,332 terrain and 4,001 height changes
```

`blend(dimension, area, progressReceiver)` restricts it to a rectangle, in block coordinates. Note that
`Dimension.getExtent()` is in *tile* coordinates; use `Dimension.getBlockExtent()`, which this fork adds, when you want
blocks.

`Blender` is not thread safe; create one per operation. It modifies the dimension in place, so take a copy first if you
want to keep the original.

## Performance

Both operations run on the GPU when there is one (see [GPU acceleration](GPU-ACCELERATION.md)) and on the CPU when there
is not, and the two produce identical maps — `BlendKernelTest` and `BlenderTest` check exactly that. The CPU path is not
a token fallback; it is the reference implementation, and `blend.cl` is a transliteration of it.

The expensive part of terrain blending is the "is anything different nearby" test, which reads the disc of radius `r`
around every column. That is quadratic in the radius, so a radius of 32 costs sixteen times a radius of 8. On a GPU it
does not matter much; on a CPU, keep the radius modest.
