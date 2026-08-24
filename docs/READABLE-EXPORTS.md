# Readable exports

A `.world` file is a gzipped Java object graph. The region files WorldPainter exports are NBT in a custom container.
Between them, roughly two programs on earth can read either. That is fine right up until you want to look at a map in
Blender, print one, analyse one in a spreadsheet, put a picture of one in a README, describe one to a language model, or
simply see what is in one on a machine that has neither WorldPainter nor Minecraft installed.

`org.pepsoft.worldpainter.exporting.readable` writes the same map as text.

```
worldpainter export MyMap.world --directory out
```

```
out/MyMap.obj   (2,262,928 bytes)
out/MyMap.mtl   (971 bytes)
out/MyMap.json  (132,181 bytes)
out/MyMap.txt   (26,055 bytes)
out/MyMap.csv   (769,804 bytes)
```

The dimension is sampled once and shared between all four, so asking for all of them costs barely more than asking for
one.

## OBJ — a 3D mesh

Wavefront OBJ is about as open as a 3D format gets: plain text, read by every 3D application there is, and legible in a
text editor. The export writes the surface as a mesh, with a matching `.mtl` material library.

- **Y up, one unit per block**, so it imports at true scale into Blender, Unreal, Unity, Houdini, a slicer, or anything
  else. `--exaggeration` multiplies the heights when you want the relief to read more strongly.
- **Faces grouped by terrain**, with a material per terrain coloured the way WorldPainter itself draws it. Select all
  the sand in one click.
- **An optional water surface** as a separate object with a translucent material, so it can be hidden or restyled.
- **Vertex normals**, so a smooth mesh shades properly without the importer having to guess.
- **Centred on the origin** by default, because 3D software is much happier near zero than at x = 100000.

Two geometries:

| `--geometry` | Vertices | Looks like |
| --- | --- | --- |
| `smooth` (default) | one per grid corner, shared between neighbours, at the average of the heights around it | a continuous landscape; much the smaller file |
| `blocky` | four per top face, plus walls down to every lower neighbour | the stepped, voxel look, with every column's exact height preserved |

A file header records the world, the dimension, the seed, the area, the sample interval and the axis convention, so the
mesh says where it came from.

### Size

One sample per block means one vertex per block: a 2000 × 2000 map is four million vertices. Use `--interval` to take
every second, fourth or eighth column instead — the file shrinks with the square of it, and for looking at the shape of
a landscape a coarse mesh is usually plenty. `--area x,z,width,length` exports part of a map.

## JSON — a description of the map

Intended for a program or a language model to read, though it is indented so a person can too. It answers the questions
you would otherwise open WorldPainter to answer:

```json
{
  "generator": "WorldPainter 2.27.1-SNAPSHOT",
  "about": "A description of a WorldPainter map: its extent, what it is made of, and a coarse picture of its surface...",
  "world": { "name": "MyMap", "seed": -6894864525167107490, "platform": "Minecraft 1.2 - 1.12" },
  "dimension": { "name": "Surface", "minHeight": 0, "maxHeight": 256, "tileCount": 25, "extent": { ... } },
  "height": { "min": 59.3, "max": 74.9, "mean": 67.5, "range": 15.6, "columns": 409600 },
  "water": { "floodedColumns": 6432, "floodedFraction": 0.0157, "greatestDepth": 9 },
  "terrain": [ { "name": "GRASS", "displayName": "Grass", "columns": 236112, "fraction": 0.5764 }, ... ],
  "layers": [ { "name": "Trees", "id": "Trees", "dataSize": "NIBBLE" }, ... ],
  "grid": { "sampleInterval": 32, "heights": [[67.1, 66.8, ...], ...], "terrains": [["GRASS", ...], ...] },
  "asciiMap": { "legend": { "g": "Grass", "~": "water or lava (above the surface)" }, "rows": ["ggggg~~~ggg", ...] }
}
```

The grids and the ASCII map are sampled down to at most `--summary-columns` wide (128 by default), so a summary of a
huge map is still something that fits in one read. `--no-grids` leaves the grids out and keeps only the statistics.

`worldpainter describe MyMap.world` writes it to standard output, which makes it easy to pipe into `jq` or into
whatever you feed a model with.

## TXT — an ASCII map

```
gggggggggggggggggggggggggggggggggggggbbbbbggggggggggggggggggggggguuuuuuuuuuuuuuuuuuuuuuuuuuuu
gggggggggggggggggggggggggggggggggggggbbbbbggggggggggggggggggggggggguuuuuuuuuuuuuuuuuuuuuuuuuu
ggggggggggggggggggggggggggggggggggggggbbbggggggggggggggggggggggggggggggggguuuuuuuuuuuugggggggg
ccccggggggggggggggggggggggggggggggggggggggggggggggggbbb~~~bbbgggggggggggggggggggggggggggggggg
cccccccccgggggggggggggggggggggggggggggggggggggggggggbbbbbbbbgggggggggggggggggggggggggggggggggg

Legend
  g  Grass
  c  Custom 1
  u  Custom 2
  b  Beaches
  ~  water or lava (above the surface)
     no tile, or Void
```

One character per sampled column, north at the top. Each terrain gets a letter from its own name where one is free, so
the map is readable without constantly consulting the legend. Water is drawn as `~` whatever is under it, because on a
map the shape of the coastline matters more than what the seabed is made of.

This is the only form of a WorldPainter map you can paste into a chat window, commit to a repository and diff, grep,
or read over ssh.

## CSV — a table

```
x,z,height,water_level,under_water,terrain
-256,-256,67.133,61,false,Grass
-252,-256,66.838,61,false,Grass
```

One row per sampled column. Opens in a spreadsheet, loads in one line of Python or R, imports into a database, and reads
fine in Notepad. Anything anybody wants to work out about a map that WorldPainter does not already tell them starts
here.

## From code

```java
final ReadableExportSettings settings = ReadableExportSettings.builder()
        .sampleInterval(4)
        .geometry(ReadableExportSettings.Geometry.SMOOTH)
        .verticalExaggeration(2f)
        .build();

new ReadableExporter(settings).exportAll(dimension, new File("out"), "MyMap", progressReceiver);
```

Or one format at a time, sharing the sampled surface:

```java
final SurfaceGrid grid = SurfaceGrid.sample(dimension, null, 4, null);
new WavefrontObjExporter(settings).export(dimension, grid, new File("out/map.obj"), null);
new WorldSummaryExporter(settings).export(dimension, grid, new File("out/map.json"));
new AsciiMapExporter().export(grid, new File("out/map.txt"));
new CsvGridExporter().export(grid, new File("out/map.csv"));
```

`SurfaceGrid` is the shared snapshot: heights, terrains and water levels on a regular grid, with `isPresent` telling
you where the map has no tile.

## Options

| Option | Meaning | Default |
| --- | --- | --- |
| `--interval <n>` | export every n'th column | 1 |
| `--area x,z,width,length` | only this part of the map, in block coordinates | the whole map |
| `--geometry smooth\|blocky` | mesh style | `smooth` |
| `--exaggeration <f>` | multiply the heights | 1 |
| `--no-water` | leave out the water surface | water included |
| `--no-normals` | leave out vertex normals | normals included |
| `--no-centre` | keep world coordinates instead of centring on the origin | centred |
| `--summary-columns <n>` | how wide the grids and ASCII map in the JSON may be | 128 |
| `--no-grids` | leave the grids out of the JSON | grids included |
| `--formats obj,json,txt,csv` | which formats to write (`all` for everything) | all |
| `--name <name>` | base name for the files | the world file's name |
| `--dimension <name>` | which dimension | the surface |
