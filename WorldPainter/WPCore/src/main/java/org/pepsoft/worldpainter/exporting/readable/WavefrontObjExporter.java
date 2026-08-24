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
package org.pepsoft.worldpainter.exporting.readable;

import org.pepsoft.util.ProgressReceiver;
import org.pepsoft.util.ProgressReceiver.OperationCancelled;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Writes the surface of a dimension as a Wavefront OBJ mesh, with a matching MTL material library.
 *
 * <p>OBJ is about as open as a 3D format gets: it is plain text, every 3D application reads it, and you can open it
 * in Notepad and see what it says. That makes it useful for a great deal more than looking at a map in Blender - it
 * is a way of getting a WorldPainter landscape into a renderer, a game engine, a 3D printer, an analysis script, or
 * a language model, none of which can read a {@code .world} file.
 *
 * <p>The mesh is Y up, one unit per block, so a map imported into any 3D application comes in at true scale unless
 * {@link ReadableExportSettings#getVerticalExaggeration()} says otherwise. Faces are grouped by terrain, so a
 * modeller can select all the sand or all the grass in one click, and each group's material carries the colour
 * WorldPainter itself draws that terrain in.
 *
 * <p>A map of any size makes a large file: one block per sample is a vertex per block. Use
 * {@link ReadableExportSettings#getSampleInterval()} to take every second, fourth or eighth column instead; the file
 * shrinks with the square of it, and for looking at the shape of a landscape a coarse mesh is usually plenty.
 */
public class WavefrontObjExporter {
    public WavefrontObjExporter(ReadableExportSettings settings) {
        this.settings = (settings != null) ? settings : ReadableExportSettings.builder().build();
    }

    /**
     * Export a dimension to {@code objFile}, writing the material library next to it.
     *
     * @return A description of what was written.
     */
    public ObjExportResult export(Dimension dimension, File objFile, ProgressReceiver progressReceiver)
            throws IOException, OperationCancelled {
        final SurfaceGrid grid = SurfaceGrid.sample(dimension, settings.getArea(), settings.getSampleInterval(),
                (progressReceiver != null) ? new org.pepsoft.util.SubProgressReceiver(progressReceiver, 0f, 0.3f) : null);
        return export(dimension, grid, objFile,
                (progressReceiver != null) ? new org.pepsoft.util.SubProgressReceiver(progressReceiver, 0.3f, 0.7f) : null);
    }

    /**
     * Export a surface that has already been sampled. Use this when several readable exports are being written at
     * once, so that the dimension is only walked once.
     */
    public ObjExportResult export(Dimension dimension, SurfaceGrid grid, File objFile, ProgressReceiver progressReceiver)
            throws IOException, OperationCancelled {
        final File materialFile = getMaterialFile(objFile);
        final Map<Short, MaterialEntry> materials = collectMaterials(dimension, grid);
        writeMaterialLibrary(materialFile, materials);
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(objFile), StandardCharsets.UTF_8), BUFFER_SIZE)) {
            return write(dimension, grid, out, materialFile.getName(), materials, progressReceiver);
        }
    }

    private ObjExportResult write(Dimension dimension, SurfaceGrid grid, Writer out, String materialFileName,
                                  Map<Short, MaterialEntry> materials, ProgressReceiver progressReceiver)
            throws IOException, OperationCancelled {
        final ObjWriter obj = new ObjWriter(out);
        writeHeader(dimension, grid, obj, materialFileName);

        final Counter counter = new Counter();
        if (settings.getGeometry() == ReadableExportSettings.Geometry.SMOOTH) {
            writeSmoothMesh(grid, obj, materials, counter, progressReceiver);
        } else {
            writeBlockyMesh(grid, obj, materials, counter, progressReceiver);
        }
        if (settings.isIncludeWater()) {
            writeWaterSurface(grid, obj, counter);
        }
        obj.blankLine();
        obj.comment(String.format(Locale.ROOT, "%,d vertices, %,d faces", counter.vertices, counter.faces));
        return new ObjExportResult(counter.vertices, counter.faces, materials.size(), grid.getColumns(), grid.getRows());
    }

    private void writeHeader(Dimension dimension, SurfaceGrid grid, ObjWriter obj, String materialFileName) throws IOException {
        obj.comment("Wavefront OBJ mesh of a WorldPainter map");
        obj.comment("");
        obj.comment("world:              " + safe(dimension.getWorld() != null ? dimension.getWorld().getName() : null));
        obj.comment("dimension:          " + safe(dimension.getName()));
        obj.comment("seed:               " + dimension.getSeed());
        obj.comment(String.format(Locale.ROOT, "area:               x %d to %d, z %d to %d (blocks)",
                grid.getOriginX(), grid.getWorldX(Math.max(grid.getColumns() - 1, 0)),
                grid.getOriginY(), grid.getWorldY(Math.max(grid.getRows() - 1, 0))));
        obj.comment("samples:            " + grid.getColumns() + " x " + grid.getRows()
                + " (every " + grid.getSampleInterval() + " block" + ((grid.getSampleInterval() == 1) ? "" : "s") + ')');
        obj.comment("geometry:           " + settings.getGeometry());
        obj.comment("vertical scale:     " + settings.getVerticalExaggeration() + "x");
        obj.comment("axes:               X = east, Y = up (height), Z = south; one unit is one block");
        obj.comment("");
        obj.comment("Faces are grouped by terrain type; see the material library for the colours.");
        obj.blankLine();
        obj.statement("mtllib", materialFileName);
        obj.blankLine();
    }

    /**
     * One quad per sample, with the corner vertices shared between neighbouring quads and placed at the average of
     * the heights around them.
     */
    private void writeSmoothMesh(SurfaceGrid grid, ObjWriter obj, Map<Short, MaterialEntry> materials,
                                 Counter counter, ProgressReceiver progressReceiver) throws IOException, OperationCancelled {
        final int columns = grid.getColumns(), rows = grid.getRows();
        if ((columns == 0) || (rows == 0)) {
            return;
        }
        final int cornerColumns = columns + 1, cornerRows = rows + 1;
        final float[] cornerHeights = new float[cornerColumns * cornerRows];
        for (int row = 0; row < cornerRows; row++) {
            for (int column = 0; column < cornerColumns; column++) {
                cornerHeights[(row * cornerColumns) + column] = getCornerHeight(grid, column, row);
            }
        }

        obj.comment("Surface");
        final int firstCorner = counter.vertices + 1;
        for (int row = 0; row < cornerRows; row++) {
            for (int column = 0; column < cornerColumns; column++) {
                obj.vertex(toModelX(grid, column), cornerHeights[(row * cornerColumns) + column] * settings.getVerticalExaggeration(),
                        toModelZ(grid, row));
            }
        }
        counter.vertices += cornerColumns * cornerRows;

        if (settings.isIncludeNormals()) {
            obj.blankLine();
            for (int row = 0; row < cornerRows; row++) {
                for (int column = 0; column < cornerColumns; column++) {
                    writeCornerNormal(obj, cornerHeights, cornerColumns, cornerRows, column, row, grid.getSampleInterval());
                }
            }
        }

        int materialsDone = 0;
        for (Map.Entry<Short, MaterialEntry> entry: materials.entrySet()) {
            if (progressReceiver != null) {
                progressReceiver.checkForCancellation();
            }
            final short terrain = entry.getKey();
            obj.blankLine();
            obj.statement("g", entry.getValue().name);
            obj.statement("usemtl", entry.getValue().name);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    if (grid.getTerrainOrdinal(column, row) != terrain) {
                        continue;
                    }
                    // Wound anticlockwise seen from above, so that the face points up
                    final int topLeft = firstCorner + (row * cornerColumns) + column;
                    final int bottomLeft = topLeft + cornerColumns;
                    if (settings.isIncludeNormals()) {
                        obj.quadWithNormals(topLeft, bottomLeft, bottomLeft + 1, topLeft + 1);
                    } else {
                        obj.quad(topLeft, bottomLeft, bottomLeft + 1, topLeft + 1);
                    }
                    counter.faces++;
                }
            }
            materialsDone++;
            if (progressReceiver != null) {
                progressReceiver.setProgress((float) materialsDone / materials.size());
            }
        }
    }

    /**
     * A flat top at each sample's exact height, plus a wall down to every neighbour that is lower. Keeps the stepped,
     * blocky look of the map, at several times the file size.
     */
    private void writeBlockyMesh(SurfaceGrid grid, ObjWriter obj, Map<Short, MaterialEntry> materials,
                                 Counter counter, ProgressReceiver progressReceiver) throws IOException, OperationCancelled {
        final int columns = grid.getColumns(), rows = grid.getRows();
        final float exaggeration = settings.getVerticalExaggeration();
        int materialsDone = 0;
        for (Map.Entry<Short, MaterialEntry> entry: materials.entrySet()) {
            if (progressReceiver != null) {
                progressReceiver.checkForCancellation();
            }
            final short terrain = entry.getKey();
            obj.blankLine();
            obj.statement("g", entry.getValue().name);
            obj.statement("usemtl", entry.getValue().name);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    if (grid.getTerrainOrdinal(column, row) != terrain) {
                        continue;
                    }
                    final float top = grid.getHeight(column, row) * exaggeration;
                    final float west = toModelX(grid, column), east = toModelX(grid, column + 1);
                    final float north = toModelZ(grid, row), south = toModelZ(grid, row + 1);

                    obj.vertex(west, top, north);
                    obj.vertex(west, top, south);
                    obj.vertex(east, top, south);
                    obj.vertex(east, top, north);
                    counter.vertices += 4;
                    obj.quad(counter.vertices - 3, counter.vertices - 2, counter.vertices - 1, counter.vertices);
                    counter.faces++;

                    // A wall down to each lower neighbour, wound so that it faces outwards
                    counter.faces += writeWall(obj, counter, west, north, west, south, top,
                            getNeighbourHeight(grid, column - 1, row) * exaggeration);
                    counter.faces += writeWall(obj, counter, east, south, east, north, top,
                            getNeighbourHeight(grid, column + 1, row) * exaggeration);
                    counter.faces += writeWall(obj, counter, east, north, west, north, top,
                            getNeighbourHeight(grid, column, row - 1) * exaggeration);
                    counter.faces += writeWall(obj, counter, west, south, east, south, top,
                            getNeighbourHeight(grid, column, row + 1) * exaggeration);
                }
            }
            materialsDone++;
            if (progressReceiver != null) {
                progressReceiver.setProgress((float) materialsDone / materials.size());
            }
        }
    }

    /**
     * A vertical quad from {@code top} down to {@code bottom} along the edge from (x1, z1) to (x2, z2).
     *
     * @return The number of faces written: one, or none if the neighbour is not lower.
     */
    private int writeWall(ObjWriter obj, Counter counter, float x1, float z1, float x2, float z2, float top, float bottom)
            throws IOException {
        if (bottom >= top) {
            return 0;
        }
        obj.vertex(x1, top, z1);
        obj.vertex(x1, bottom, z1);
        obj.vertex(x2, bottom, z2);
        obj.vertex(x2, top, z2);
        counter.vertices += 4;
        obj.quad(counter.vertices - 3, counter.vertices - 2, counter.vertices - 1, counter.vertices);
        return 1;
    }

    /**
     * A flat surface over every flooded sample, as a separate object so that it can be given a transparent material
     * or hidden altogether.
     */
    private void writeWaterSurface(SurfaceGrid grid, ObjWriter obj, Counter counter) throws IOException {
        final int columns = grid.getColumns(), rows = grid.getRows();
        boolean started = false;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (! grid.isFlooded(column, row)) {
                    continue;
                }
                if (! started) {
                    obj.blankLine();
                    obj.statement("o", WATER_MATERIAL);
                    obj.statement("g", WATER_MATERIAL);
                    obj.statement("usemtl", WATER_MATERIAL);
                    started = true;
                }
                // The surface of the water is the top of the highest water block
                final float level = (grid.getWaterLevel(column, row) + 1) * settings.getVerticalExaggeration();
                final float west = toModelX(grid, column), east = toModelX(grid, column + 1);
                final float north = toModelZ(grid, row), south = toModelZ(grid, row + 1);
                obj.vertex(west, level, north);
                obj.vertex(west, level, south);
                obj.vertex(east, level, south);
                obj.vertex(east, level, north);
                counter.vertices += 4;
                obj.quad(counter.vertices - 3, counter.vertices - 2, counter.vertices - 1, counter.vertices);
                counter.faces++;
            }
        }
    }

    /**
     * The height of a corner of the sample grid: the average of the samples that exist around it.
     */
    private float getCornerHeight(SurfaceGrid grid, int column, int row) {
        float total = 0f;
        int count = 0;
        for (int dy = -1; dy <= 0; dy++) {
            for (int dx = -1; dx <= 0; dx++) {
                if (grid.isPresent(column + dx, row + dy)) {
                    total += grid.getHeight(column + dx, row + dy);
                    count++;
                }
            }
        }
        return (count > 0) ? (total / count) : 0f;
    }

    /**
     * The height to drop a wall down to. Off the edge of the map, the wall goes all the way down to the lowest
     * sample, so that the mesh has sides rather than a floating rim.
     */
    private float getNeighbourHeight(SurfaceGrid grid, int column, int row) {
        return grid.isPresent(column, row) ? grid.getHeight(column, row) : 0f;
    }

    private void writeCornerNormal(ObjWriter obj, float[] cornerHeights, int cornerColumns, int cornerRows,
                                   int column, int row, int sampleInterval) throws IOException {
        final int left = Math.max(column - 1, 0), right = Math.min(column + 1, cornerColumns - 1);
        final int up = Math.max(row - 1, 0), down = Math.min(row + 1, cornerRows - 1);
        final float dx = (cornerHeights[(row * cornerColumns) + right] - cornerHeights[(row * cornerColumns) + left])
                / ((right - left) * sampleInterval);
        final float dz = (cornerHeights[(down * cornerColumns) + column] - cornerHeights[(up * cornerColumns) + column])
                / ((down - up) * sampleInterval);
        // The surface normal of a height field is (-dh/dx, 1, -dh/dz), normalised
        final float exaggeration = settings.getVerticalExaggeration();
        float nx = -dx * exaggeration, ny = 1f, nz = -dz * exaggeration;
        final float length = (float) Math.sqrt((nx * nx) + (ny * ny) + (nz * nz));
        if (length > 0f) {
            nx /= length;
            ny /= length;
            nz /= length;
        }
        obj.normal(nx, ny, nz);
    }

    private float toModelX(SurfaceGrid grid, int column) {
        final float x = grid.getWorldX(column);
        return settings.isCentreOnOrigin() ? (x - getCentreX(grid)) : x;
    }

    private float toModelZ(SurfaceGrid grid, int row) {
        final float z = grid.getWorldY(row);
        return settings.isCentreOnOrigin() ? (z - getCentreZ(grid)) : z;
    }

    private float getCentreX(SurfaceGrid grid) {
        return grid.getOriginX() + ((grid.getColumns() * grid.getSampleInterval()) / 2f);
    }

    private float getCentreZ(SurfaceGrid grid) {
        return grid.getOriginY() + ((grid.getRows() * grid.getSampleInterval()) / 2f);
    }

    /**
     * Find every terrain that occurs in the grid, and work out a representative colour for each.
     */
    private Map<Short, MaterialEntry> collectMaterials(Dimension dimension, SurfaceGrid grid) {
        final TreeSet<Short> used = new TreeSet<>();
        for (int row = 0; row < grid.getRows(); row++) {
            for (int column = 0; column < grid.getColumns(); column++) {
                final short terrain = grid.getTerrainOrdinal(column, row);
                if (terrain != SurfaceGrid.ABSENT) {
                    used.add(terrain);
                }
            }
        }
        final Terrain[] terrains = Terrain.values();
        final Map<Short, MaterialEntry> materials = new LinkedHashMap<>();
        for (Short ordinal: used) {
            final Terrain terrain = terrains[ordinal];
            materials.put(ordinal, new MaterialEntry(toMaterialName(terrain), getRepresentativeColour(dimension, terrain)));
        }
        return materials;
    }

    /**
     * The colour WorldPainter draws a terrain in. Most terrains vary their colour from block to block, so several
     * samples are averaged to get one colour that stands for the terrain as a whole.
     */
    private int getRepresentativeColour(Dimension dimension, Terrain terrain) {
        final int height = Math.max(dimension.getMinHeight() + 1, Math.min(SAMPLE_HEIGHT, dimension.getMaxHeight() - 1));
        long red = 0, green = 0, blue = 0;
        int count = 0;
        for (int i = 0; i < COLOUR_SAMPLES; i++) {
            final int x = i * 37, y = i * 91;
            final int colour;
            try {
                colour = terrain.getColour(dimension.getSeed(), x, y, height, height,
                        (dimension.getWorld() != null) ? dimension.getWorld().getPlatform() : null,
                        settings.getColourScheme());
            } catch (RuntimeException e) {
                // A custom terrain that has not been configured cannot produce a colour; grey will do
                return 0x808080;
            }
            red += (colour >> 16) & 0xff;
            green += (colour >> 8) & 0xff;
            blue += colour & 0xff;
            count++;
        }
        return (int) (((red / count) << 16) | ((green / count) << 8) | (blue / count));
    }

    private void writeMaterialLibrary(File materialFile, Map<Short, MaterialEntry> materials) throws IOException {
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(materialFile), StandardCharsets.UTF_8))) {
            out.write("# Materials for a WorldPainter map exported as OBJ\n");
            out.write("# Kd is the terrain's colour as WorldPainter draws it, as red, green and blue from 0 to 1.\n\n");
            for (MaterialEntry material: materials.values()) {
                writeMaterial(out, material.name, material.colour, 1f);
            }
            if (settings.isIncludeWater()) {
                writeMaterial(out, WATER_MATERIAL, WATER_COLOUR, WATER_OPACITY);
            }
        }
    }

    private void writeMaterial(Writer out, String name, int colour, float opacity) throws IOException {
        out.write("newmtl " + name + '\n');
        out.write(String.format(Locale.ROOT, "Kd %.4f %.4f %.4f\n",
                ((colour >> 16) & 0xff) / 255f, ((colour >> 8) & 0xff) / 255f, (colour & 0xff) / 255f));
        out.write("Ka 0.0000 0.0000 0.0000\n");
        out.write("Ks 0.0000 0.0000 0.0000\n");
        out.write("illum 1\n");
        if (opacity < 1f) {
            out.write(String.format(Locale.ROOT, "d %.2f\n", opacity));
        }
        out.write('\n');
    }

    /**
     * The file the OBJ's {@code mtllib} statement will point at: the same name with a {@code .mtl} extension.
     */
    static File getMaterialFile(File objFile) {
        final String name = objFile.getName();
        final int dot = name.lastIndexOf('.');
        return new File(objFile.getAbsoluteFile().getParentFile(), ((dot > 0) ? name.substring(0, dot) : name) + ".mtl");
    }

    /**
     * Turn a terrain name into something that is legal in an OBJ material name: no spaces, no punctuation.
     */
    static String toMaterialName(Terrain terrain) {
        final StringBuilder name = new StringBuilder(terrain.name().length() + 8);
        name.append("terrain_");
        for (char c: terrain.getName().toCharArray()) {
            name.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
        }
        return name.toString();
    }

    private static String safe(String value) {
        return ((value != null) && (! value.isEmpty())) ? value.replace('\n', ' ') : "(unnamed)";
    }

    /**
     * How many vertices and faces were written, so that a caller can say something useful about the result.
     */
    public static final class ObjExportResult {
        ObjExportResult(int vertices, int faces, int materials, int columns, int rows) {
            this.vertices = vertices;
            this.faces = faces;
            this.materials = materials;
            this.columns = columns;
            this.rows = rows;
        }

        public int getVertices() {
            return vertices;
        }

        public int getFaces() {
            return faces;
        }

        public int getMaterials() {
            return materials;
        }

        public int getColumns() {
            return columns;
        }

        public int getRows() {
            return rows;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%,d x %,d samples, %,d vertices, %,d faces, %d materials",
                    columns, rows, vertices, faces, materials);
        }

        private final int vertices, faces, materials, columns, rows;
    }

    private static final class MaterialEntry {
        MaterialEntry(String name, int colour) {
            this.name = name;
            this.colour = colour;
        }

        final String name;
        final int colour;
    }

    private static final class Counter {
        int vertices, faces;
    }

    private final ReadableExportSettings settings;

    /** The name of the material and object the water surface is written as. */
    public static final String WATER_MATERIAL = "water";

    private static final int WATER_COLOUR = 0x3050A0;
    private static final float WATER_OPACITY = 0.55f;
    private static final int COLOUR_SAMPLES = 16;
    private static final int SAMPLE_HEIGHT = 62;
    private static final int BUFFER_SIZE = 1 << 20;
}
