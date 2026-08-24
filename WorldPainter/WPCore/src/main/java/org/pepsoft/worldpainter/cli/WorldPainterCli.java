/*
 * WorldPainter, a graphical map generator for Minecraft. Copyright (C) 2011-2015  pepsoft.org
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.pepsoft.worldpainter.cli;

import org.pepsoft.util.ProgressReceiver.OperationCancelled;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.blending.BlendMode;
import org.pepsoft.worldpainter.blending.BlendReport;
import org.pepsoft.worldpainter.blending.BlendSettings;
import org.pepsoft.worldpainter.blending.Blender;
import org.pepsoft.worldpainter.exporting.readable.ReadableExportSettings;
import org.pepsoft.worldpainter.exporting.readable.ReadableExporter;
import org.pepsoft.worldpainter.exporting.readable.SurfaceGrid;
import org.pepsoft.worldpainter.gpu.GpuContext;
import org.pepsoft.worldpainter.gpu.GpuDevice;
import org.pepsoft.worldpainter.gpu.GpuPerlinNoise;
import org.pepsoft.worldpainter.gpu.GpuSettings;
import org.pepsoft.worldpainter.plugins.WPPluginManager;

import java.awt.Rectangle;
import java.io.*;
import java.util.*;

import static java.util.Locale.ROOT;

/**
 * A command line for WorldPainter, for the things that do not need a mouse.
 *
 * <p>WorldPainter is a paint program, and painting needs a window. Plenty of what people do with it does not: looking
 * at what is in a map, getting one out into another tool, applying the same treatment to a folder full of maps, or
 * doing any of that on a build server or over ssh, where there is no display at all. That is what this is for.
 *
 * <p>Run it with no arguments for the usage message.
 */
public class WorldPainterCli {
    public static void main(String[] args) {
        // Keep the log quiet unless it is asked for. This has to happen before anything creates a logger, which is
        // why it is here rather than anywhere more obvious. It only sets a default: an explicit -D on the command
        // line, or a logging configuration file, still wins.
        if (System.getProperty(LOG_LEVEL_PROPERTY) == null) {
            System.setProperty(LOG_LEVEL_PROPERTY, Arrays.asList(args).contains("--verbose") ? "info" : "warn");
        }
        System.exit(run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true)));
    }

    /**
     * Run the command line, returning the exit code. Takes its output streams as arguments so that it can be tested.
     */
    public static int run(String[] args, PrintWriter out, PrintWriter err) {
        if ((args.length == 0) || "help".equals(args[0]) || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage(out);
            return (args.length == 0) ? 1 : 0;
        }
        final String command = args[0];
        final Arguments arguments;
        try {
            arguments = Arguments.parse(Arrays.copyOfRange(args, 1, args.length), FLAGS);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
        try {
            applyGpuOptions(arguments);
            final int result;
            switch (command) {
                case "info":
                    result = info(arguments, out, err);
                    break;
                case "describe":
                    result = describe(arguments, out, err);
                    break;
                case "export":
                    result = export(arguments, out, err);
                    break;
                case "blend":
                    result = blend(arguments, out, err);
                    break;
                case "gpu":
                    result = gpu(out);
                    break;
                default:
                    err.println("Unknown command \"" + command + "\". Run with no arguments for the usage message.");
                    return 2;
            }
            for (String unrecognised: arguments.getUnrecognised()) {
                err.println("Warning: --" + unrecognised + " is not an option of the \"" + command + "\" command, and was ignored");
            }
            return result;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        } catch (OperationCancelled e) {
            err.println("Cancelled");
            return 130;
        } catch (Exception e) {
            err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
            if (arguments.getBoolean("verbose", false)) {
                e.printStackTrace(err);
            }
            return 1;
        }
    }

    /**
     * Print a summary of a map, for a person.
     */
    private static int info(Arguments arguments, PrintWriter out, PrintWriter err)
            throws IOException, UnloadableWorldException, OperationCancelled {
        final World2 world = loadWorld(requireFile(arguments, 0, "world file"), err);
        if (world == null) {
            return 1;
        }
        out.printf("World:      %s%n", world.getName());
        out.printf("Platform:   %s%n", (world.getPlatform() != null) ? world.getPlatform().displayName : "unknown");
        out.printf("Dimensions: %d%n", world.getDimensions().size());
        for (Dimension dimension: sortedDimensions(world)) {
            final Rectangle extent = dimension.getBlockExtent();
            out.println();
            out.printf("  %s (%s)%n", dimension.getName(), dimension.getAnchor());
            out.printf("    seed:      %d%n", dimension.getSeed());
            out.printf("    tiles:     %,d%n", dimension.getTileCount());
            out.printf("    extent:    %,d x %,d blocks, from x %,d, z %,d%n",
                    extent.width, extent.height, extent.x, extent.y);
            out.printf("    heights:   %d to %d%n", dimension.getMinHeight(), dimension.getMaxHeight());
            if (dimension.getTileCount() > 0) {
                final SurfaceGrid grid = SurfaceGrid.sample(dimension, null, samplingIntervalFor(dimension), null);
                out.printf("    surface:   %.1f to %.1f, mean %.1f%n",
                        minimumHeight(grid), maximumHeight(grid), meanHeight(grid));
                out.printf("    water:     %.1f%% of the map%n", 100.0 * floodedFraction(grid));
                out.printf("    terrains:  %s%n", describeTerrains(grid));
            }
        }
        return 0;
    }

    /**
     * Write the JSON description of a map, to a file or to standard output.
     */
    private static int describe(Arguments arguments, PrintWriter out, PrintWriter err)
            throws IOException, UnloadableWorldException, OperationCancelled {
        final World2 world = loadWorld(requireFile(arguments, 0, "world file"), err);
        if (world == null) {
            return 1;
        }
        final Dimension dimension = selectDimension(world, arguments, err);
        if (dimension == null) {
            return 1;
        }
        final ReadableExportSettings settings = readableSettings(arguments);
        final String output = arguments.getString("output", null);
        final SurfaceGrid grid = SurfaceGrid.sample(dimension, settings.getArea(), settings.getSampleInterval(), null);
        final org.pepsoft.worldpainter.exporting.readable.WorldSummaryExporter exporter =
                new org.pepsoft.worldpainter.exporting.readable.WorldSummaryExporter(settings);
        if (output != null) {
            exporter.export(dimension, grid, new File(output));
            err.println("Wrote " + output);
        } else {
            exporter.export(dimension, grid, out);
        }
        return 0;
    }

    /**
     * Write the readable exports of a map.
     */
    private static int export(Arguments arguments, PrintWriter out, PrintWriter err)
            throws IOException, UnloadableWorldException, OperationCancelled {
        final File worldFile = requireFile(arguments, 0, "world file");
        final World2 world = loadWorld(worldFile, err);
        if (world == null) {
            return 1;
        }
        final Dimension dimension = selectDimension(world, arguments, err);
        if (dimension == null) {
            return 1;
        }
        final File directory = new File(arguments.getString("directory", "."));
        final String baseName = arguments.getString("name", stripExtension(worldFile.getName()));
        final Set<ReadableExporter.Format> formats = parseFormats(arguments.getString("formats", null));

        final long start = System.currentTimeMillis();
        final List<File> written = new ReadableExporter(readableSettings(arguments))
                .export(dimension, directory, baseName, formats, null);
        for (File file: written) {
            out.printf("%s (%,d bytes)%n", file.getPath(), file.length());
        }
        err.printf(ROOT, "Wrote %d file(s) in %.1f s%n", written.size(), (System.currentTimeMillis() - start) / 1000.0);
        return 0;
    }

    /**
     * Blend a map and save it.
     */
    private static int blend(Arguments arguments, PrintWriter out, PrintWriter err)
            throws IOException, UnloadableWorldException, OperationCancelled {
        final File worldFile = requireFile(arguments, 0, "world file");
        final String output = arguments.getString("output", null);
        if (output == null) {
            err.println("blend needs --output, the file to write the blended map to");
            return 2;
        }
        final World2 world = loadWorld(worldFile, err);
        if (world == null) {
            return 1;
        }
        final String dimensionName = arguments.getString("dimension", null);
        final BlendSettings settings = blendSettings(arguments);
        if (settings.isNoOp()) {
            err.println("These settings would not change anything. Set --mode, --radius or --height-radius.");
            return 2;
        }

        for (Dimension dimension: sortedDimensions(world)) {
            if ((dimensionName != null) && (! matches(dimension, dimensionName))) {
                continue;
            }
            final BlendReport report = new Blender(settings).blend(dimension, null);
            out.printf("%s: %s%n", dimension.getName(), report);
        }

        final File outputFile = new File(output);
        try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(outputFile))) {
            new WorldIO(world).save(stream);
        }
        err.printf("Wrote %s (%,d bytes)%n", outputFile.getPath(), outputFile.length());
        return 0;
    }

    /**
     * Report what OpenCL devices there are, which one WorldPainter would use, and whether it can be trusted with an
     * export.
     */
    private static int gpu(PrintWriter out) {
        final List<GpuDevice> devices = GpuContext.enumerateDevices();
        if (devices.isEmpty()) {
            out.println("No OpenCL devices found. Exports will run on the CPU.");
            out.println();
            out.println("If this machine has a graphics card, it needs its vendor's driver installed; the OpenCL");
            out.println("runtime comes with it. On Linux the ICD loader (libOpenCL.so.1) is packaged separately,");
            out.println("usually as ocl-icd-libopencl1.");
            return 0;
        }
        out.printf("OpenCL devices (%d):%n", devices.size());
        for (GpuDevice device: devices) {
            out.printf("  %s%s%n", device.getDescription(), device.isDedicated() ? "  [dedicated]" : "");
        }
        out.println();
        final GpuContext context = GpuContext.get();
        if (context == null) {
            out.println("None of them is usable, so exports will run on the CPU.");
            out.println("Try --gpu-device to pick one by name, or --gpu-preference any-device.");
            return 0;
        }
        out.printf("Selected: %s%n", context.getDevice().getDescription());
        out.print("Verifying that it reproduces WorldPainter's noise exactly... ");
        out.flush();
        final long start = System.nanoTime();
        final boolean verified = GpuPerlinNoise.selfTest();
        out.printf("%s (%,d ms)%n", verified ? "yes" : "NO", (System.nanoTime() - start) / 1_000_000L);
        if (! verified) {
            out.println();
            out.println("This device does not produce bit for bit the same noise as the CPU, so WorldPainter will");
            out.println("not use it: an export on it would not match one without it. This is a driver problem.");
            return 1;
        }
        out.println("Exports and blending will use this device.");
        return 0;
    }

    private static void applyGpuOptions(Arguments arguments) {
        GpuSettings.setMode(arguments.getEnum(GpuSettings.Mode.class, "gpu", GpuSettings.getMode()));
        GpuSettings.setDevicePreference(arguments.getEnum(GpuSettings.DevicePreference.class, "gpu-preference",
                GpuSettings.getDevicePreference()));
        final String deviceName = arguments.getString("gpu-device", null);
        if (deviceName != null) {
            GpuSettings.setDeviceNameFilter(deviceName);
        }
    }

    private static ReadableExportSettings readableSettings(Arguments arguments) {
        final ReadableExportSettings.Builder builder = ReadableExportSettings.builder()
                .sampleInterval(arguments.getInt("interval", 1))
                .geometry(arguments.getEnum(ReadableExportSettings.Geometry.class, "geometry",
                        ReadableExportSettings.Geometry.SMOOTH))
                .verticalExaggeration(arguments.getFloat("exaggeration", 1f))
                .includeWater(arguments.getBoolean("water", true))
                .includeNormals(arguments.getBoolean("normals", true))
                .centreOnOrigin(arguments.getBoolean("centre", true))
                .summaryColumns(arguments.getInt("summary-columns", 128))
                .includeGrids(arguments.getBoolean("grids", true));
        final String area = arguments.getString("area", null);
        if (area != null) {
            builder.area(parseArea(area));
        }
        return builder.build();
    }

    private static BlendSettings blendSettings(Arguments arguments) {
        return BlendSettings.builder()
                .terrainMode(arguments.getEnum(BlendMode.class, "mode", BlendMode.ORGANIC))
                .terrainRadius(arguments.getInt("radius", 8))
                .terrainScale(arguments.getFloat("scale", 24f))
                .terrainStrength(arguments.getFloat("strength", 1f))
                .boundariesOnly(arguments.getBoolean("boundaries-only", true))
                .heightRadius(arguments.getInt("height-radius", 2))
                .heightStrength(arguments.getFloat("height-strength", 0.5f))
                .heightSlopeThreshold(arguments.getFloat("slope-threshold", 1.5f))
                .blendWaterLevel(arguments.getBoolean("water-level", false))
                .seedOffset(arguments.getLong("seed-offset", BlendSettings.DEFAULT_SEED_OFFSET))
                .build();
    }

    /**
     * Parse an area as {@code x,z,width,length} in block coordinates.
     */
    static Rectangle parseArea(String area) {
        final String[] fields = area.split("[,: ]+");
        if (fields.length != 4) {
            throw new IllegalArgumentException("--area must be x,z,width,length, but was \"" + area + '"');
        }
        try {
            return new Rectangle(Integer.parseInt(fields[0].trim()), Integer.parseInt(fields[1].trim()),
                    Integer.parseInt(fields[2].trim()), Integer.parseInt(fields[3].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--area must be four whole numbers, but was \"" + area + '"');
        }
    }

    static Set<ReadableExporter.Format> parseFormats(String formats) {
        if ((formats == null) || formats.trim().isEmpty() || "all".equalsIgnoreCase(formats.trim())) {
            return EnumSet.allOf(ReadableExporter.Format.class);
        }
        final Set<ReadableExporter.Format> selected = EnumSet.noneOf(ReadableExporter.Format.class);
        for (String name: formats.split("[,+ ]+")) {
            final String trimmed = name.trim().toUpperCase(ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            switch (trimmed) {
                case "OBJ":
                case "MESH":
                    selected.add(ReadableExporter.Format.OBJ);
                    break;
                case "JSON":
                case "SUMMARY":
                    selected.add(ReadableExporter.Format.JSON);
                    break;
                case "TXT":
                case "ASCII":
                case "MAP":
                    selected.add(ReadableExporter.Format.ASCII);
                    break;
                case "CSV":
                case "TABLE":
                    selected.add(ReadableExporter.Format.CSV);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown format \"" + name.trim()
                            + "\". Use obj, json, txt, csv, or all.");
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("--formats did not name any format");
        }
        return selected;
    }

    private static World2 loadWorld(File file, PrintWriter err) throws IOException, UnloadableWorldException {
        if (! file.isFile()) {
            err.println("No such file: " + file.getPath());
            return null;
        }
        initialisePlugins();
        final WorldIO worldIO = new WorldIO();
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            worldIO.load(in);
        }
        return worldIO.getWorld();
    }

    /**
     * Load the platform providers, unless something in this process already has. Checking rather than remembering
     * matters when the command line is called from inside a larger program, or from a test: initialising the plugin
     * manager twice is an error.
     */
    private static synchronized void initialisePlugins() {
        if (WPPluginManager.getInstance() == null) {
            WPPluginManager.initialise(null, WPContext.INSTANCE);
        }
    }

    private static Dimension selectDimension(World2 world, Arguments arguments, PrintWriter err) {
        final String name = arguments.getString("dimension", null);
        final List<Dimension> dimensions = sortedDimensions(world);
        if (name == null) {
            final Dimension surface = world.getDimension(Dimension.Anchor.NORMAL_DETAIL);
            if (surface != null) {
                return surface;
            }
            if (dimensions.isEmpty()) {
                err.println("This world has no dimensions");
                return null;
            }
            return dimensions.get(0);
        }
        for (Dimension dimension: dimensions) {
            if (matches(dimension, name)) {
                return dimension;
            }
        }
        final StringBuilder available = new StringBuilder();
        for (Dimension dimension: dimensions) {
            available.append((available.length() > 0) ? ", " : "").append(dimension.getName());
        }
        err.println("This world has no dimension called \"" + name + "\". It has: " + available);
        return null;
    }

    private static boolean matches(Dimension dimension, String name) {
        return name.equalsIgnoreCase(dimension.getName()) || name.equalsIgnoreCase(String.valueOf(dimension.getAnchor()));
    }

    private static List<Dimension> sortedDimensions(World2 world) {
        final List<Dimension> dimensions = new ArrayList<>(world.getDimensions());
        dimensions.sort(Comparator.comparing(Dimension::getName));
        return dimensions;
    }

    private static File requireFile(Arguments arguments, int index, String what) {
        final String path = arguments.getPositional(index, null);
        if (path == null) {
            throw new IllegalArgumentException("Missing " + what);
        }
        return new File(path);
    }

    private static String stripExtension(String fileName) {
        final int dot = fileName.lastIndexOf('.');
        return (dot > 0) ? fileName.substring(0, dot) : fileName;
    }

    /**
     * A sample interval that keeps the {@code info} command quick on a large map without changing what it says by
     * anything a person would notice.
     */
    private static int samplingIntervalFor(Dimension dimension) {
        final Rectangle extent = dimension.getBlockExtent();
        final int longest = Math.max(extent.width, extent.height);
        return Math.max(1, longest / 512);
    }

    private static float minimumHeight(SurfaceGrid grid) {
        float minimum = Float.MAX_VALUE;
        for (int row = 0; row < grid.getRows(); row++) {
            for (int column = 0; column < grid.getColumns(); column++) {
                if (grid.isPresent(column, row)) {
                    minimum = Math.min(minimum, grid.getHeight(column, row));
                }
            }
        }
        return (minimum == Float.MAX_VALUE) ? 0f : minimum;
    }

    private static float maximumHeight(SurfaceGrid grid) {
        float maximum = -Float.MAX_VALUE;
        for (int row = 0; row < grid.getRows(); row++) {
            for (int column = 0; column < grid.getColumns(); column++) {
                if (grid.isPresent(column, row)) {
                    maximum = Math.max(maximum, grid.getHeight(column, row));
                }
            }
        }
        return (maximum == -Float.MAX_VALUE) ? 0f : maximum;
    }

    private static float meanHeight(SurfaceGrid grid) {
        double total = 0;
        int count = 0;
        for (int row = 0; row < grid.getRows(); row++) {
            for (int column = 0; column < grid.getColumns(); column++) {
                if (grid.isPresent(column, row)) {
                    total += grid.getHeight(column, row);
                    count++;
                }
            }
        }
        return (count > 0) ? (float) (total / count) : 0f;
    }

    private static double floodedFraction(SurfaceGrid grid) {
        int flooded = 0, present = 0;
        for (int row = 0; row < grid.getRows(); row++) {
            for (int column = 0; column < grid.getColumns(); column++) {
                if (grid.isPresent(column, row)) {
                    present++;
                    if (grid.isFlooded(column, row)) {
                        flooded++;
                    }
                }
            }
        }
        return (present > 0) ? ((double) flooded / present) : 0;
    }

    private static String describeTerrains(SurfaceGrid grid) {
        final Map<Terrain, Integer> counts = new LinkedHashMap<>();
        int present = 0;
        for (int row = 0; row < grid.getRows(); row++) {
            for (int column = 0; column < grid.getColumns(); column++) {
                final Terrain terrain = grid.getTerrain(column, row);
                if (terrain != null) {
                    counts.merge(terrain, 1, Integer::sum);
                    present++;
                }
            }
        }
        final List<Map.Entry<Terrain, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        final StringBuilder description = new StringBuilder();
        for (int i = 0; (i < entries.size()) && (i < TERRAINS_TO_LIST); i++) {
            description.append((description.length() > 0) ? ", " : "");
            description.append(String.format(ROOT, "%s %.0f%%", entries.get(i).getKey().getName(),
                    (100.0 * entries.get(i).getValue()) / present));
        }
        if (entries.size() > TERRAINS_TO_LIST) {
            description.append(String.format(ROOT, " and %d more", entries.size() - TERRAINS_TO_LIST));
        }
        return (description.length() > 0) ? description.toString() : "none";
    }

    private static void printUsage(PrintWriter out) {
        out.println("WorldPainter " + Version.VERSION + " command line");
        out.println();
        out.println("Usage: worldpainter <command> [file] [options]");
        out.println();
        out.println("Commands:");
        out.println("  info <file.world>        What is in a map: size, heights, water, terrains");
        out.println("  describe <file.world>    Write a JSON description of a map, for a program or a model to read");
        out.println("  export <file.world>      Write a map as OBJ, JSON, ASCII and CSV");
        out.println("  blend <file.world>       Soften terrain boundaries and height steps, and save the result");
        out.println("  gpu                      List the OpenCL devices and check whether one can be used");
        out.println();
        out.println("Common options:");
        out.println("  --dimension <name>       Which dimension to work on (default: the surface)");
        out.println("  --gpu auto|off|force     Whether to use a GPU (default: auto)");
        out.println("  --gpu-device <name>      Use the device whose name contains this");
        out.println("  --gpu-preference dedicated-gpu|any-gpu|any-device");
        out.println("  --verbose                Print a stack trace when something goes wrong");
        out.println();
        out.println("export and describe options:");
        out.println("  --directory <dir>        Where to write (default: the current directory)");
        out.println("  --name <name>            Base name for the files (default: the world file's name)");
        out.println("  --formats obj,json,txt,csv");
        out.println("  --output <file>          For describe: write here instead of to standard output");
        out.println("  --interval <n>           Export every n'th column; the mesh shrinks with the square of it");
        out.println("  --area x,z,width,length  Only this part of the map");
        out.println("  --geometry smooth|blocky");
        out.println("  --exaggeration <f>       Multiply the heights, to make the relief easier to see");
        out.println("  --no-water               Leave out the water surface");
        out.println("  --no-normals             Leave out vertex normals");
        out.println("  --no-centre              Keep world coordinates instead of centring on the origin");
        out.println("  --summary-columns <n>    How wide the ASCII map and grids in the summary may be");
        out.println();
        out.println("blend options:");
        out.println("  --output <file.world>    Where to write the blended map (required)");
        out.println("  --mode organic|speckled|combined|none");
        out.println("  --radius <n>             How far a terrain may bleed across its boundary (default: 8)");
        out.println("  --scale <f>              Size of the features in an organic blend (default: 24)");
        out.println("  --strength <f>           0 to 1 (default: 1)");
        out.println("  --no-boundaries-only     Blend everywhere, not only near a boundary");
        out.println("  --height-radius <n>      Radius of the height smoothing (default: 2; 0 turns it off)");
        out.println("  --height-strength <f>    0 to 1 (default: 0.5)");
        out.println("  --slope-threshold <f>    Only smooth where the ground is steeper than this (default: 1.5)");
        out.println("  --water-level            Move water levels along with the terrain");
        out.println("  --seed-offset <n>        Change this for a different blend of the same map");
        out.println();
        out.println("Examples:");
        out.println("  worldpainter info MyMap.world");
        out.println("  worldpainter export MyMap.world --directory out --interval 4 --exaggeration 2");
        out.println("  worldpainter blend MyMap.world --output MyMap-blended.world --mode combined --radius 12");
        out.println("  worldpainter gpu");
    }

    /** The level slf4j-simple logs at. Set before anything creates a logger; see {@link #main}. */
    private static final String LOG_LEVEL_PROPERTY = "org.slf4j.simpleLogger.defaultLogLevel";

    /** Options that take no value. */
    private static final Set<String> FLAGS = new HashSet<>(Arrays.asList(
            "verbose", "water", "normals", "centre", "center", "grids", "waterlevel", "boundariesonly"));

    private static final int TERRAINS_TO_LIST = 5;
}
