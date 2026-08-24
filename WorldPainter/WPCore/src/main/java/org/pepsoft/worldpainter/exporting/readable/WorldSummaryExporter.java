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

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Version;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.layers.Layer;

import java.awt.Rectangle;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes a map as JSON: what it is, how big it is, what it is made of, and what it looks like.
 *
 * <p>The intended reader is a program or a language model rather than a person, though it is indented so that a
 * person can read it too. It answers the questions you would otherwise have to open WorldPainter to answer - how
 * large is this map, how much of it is water, what terrains does it use, how high does it go, what layers are on it -
 * and includes a coarse height and terrain grid and an ASCII map, so that something that has never seen the file can
 * still reason about the shape of the landscape.
 *
 * <p>The grids are sampled down to at most {@link ReadableExportSettings#getSummaryColumns()} columns across. A
 * summary is meant to be read in one go, so it stays a readable size however large the map is.
 */
public class WorldSummaryExporter {
    public WorldSummaryExporter(ReadableExportSettings settings) {
        this.settings = (settings != null) ? settings : ReadableExportSettings.builder().build();
    }

    /**
     * Write a summary of a dimension to a file.
     */
    public void export(Dimension dimension, SurfaceGrid grid, File file) throws IOException {
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            export(dimension, grid, out);
        }
    }

    /**
     * Write a summary of a dimension to a writer, which is not closed.
     *
     * @param grid A sampled surface. It is sampled down further if it is finer than the summary needs.
     */
    public void export(Dimension dimension, SurfaceGrid grid, Writer out) throws IOException {
        final int step = getSummaryStep(grid);
        try (JsonWriter json = new JsonWriter(out)) {
            json.startObject();
            json.value("generator", "WorldPainter " + Version.VERSION);
            json.value("generatedAt", Instant.now().toString());
            json.value("about", "A description of a WorldPainter map: its extent, what it is made of, and a coarse "
                    + "picture of its surface. Coordinates are Minecraft block coordinates, with x running east and "
                    + "z running south. Heights are the y coordinate of the surface block.");

            writeWorld(json, dimension);
            writeDimension(json, dimension, grid);
            writeHeightStatistics(json, grid);
            writeWaterStatistics(json, grid);
            writeTerrainStatistics(json, grid);
            writeLayers(json, dimension);
            if (settings.isIncludeGrids()) {
                writeGrids(json, grid, step);
            }
            writeAsciiMap(json, grid, step);

            json.endObject();
        }
    }

    private void writeWorld(JsonWriter json, Dimension dimension) throws IOException {
        final World2 world = dimension.getWorld();
        json.startObject("world");
        json.value("name", (world != null) ? world.getName() : null);
        json.value("seed", dimension.getSeed());
        if ((world != null) && (world.getPlatform() != null)) {
            json.value("platform", world.getPlatform().displayName);
        } else {
            json.nullValue("platform");
        }
        json.endObject();
    }

    private void writeDimension(JsonWriter json, Dimension dimension, SurfaceGrid grid) throws IOException {
        final Rectangle extent = dimension.getBlockExtent();
        json.startObject("dimension");
        json.value("name", dimension.getName());
        json.value("anchor", String.valueOf(dimension.getAnchor()));
        json.value("minHeight", dimension.getMinHeight());
        json.value("maxHeight", dimension.getMaxHeight());
        json.value("tileCount", dimension.getTileCount());
        json.startObject("extent");
        if (extent != null) {
            json.value("x", extent.x);
            json.value("z", extent.y);
            json.value("width", extent.width);
            json.value("length", extent.height);
        }
        json.endObject();
        json.startObject("sampled");
        json.value("originX", grid.getOriginX());
        json.value("originZ", grid.getOriginY());
        json.value("columns", grid.getColumns());
        json.value("rows", grid.getRows());
        json.value("sampleInterval", grid.getSampleInterval());
        json.value("columnsWithTerrain", grid.getPresentCount());
        json.endObject();
        json.endObject();
    }

    private void writeHeightStatistics(JsonWriter json, SurfaceGrid grid) throws IOException {
        float minimum = Float.MAX_VALUE, maximum = -Float.MAX_VALUE;
        double total = 0;
        int count = 0;
        for (int row = 0; row < grid.getRows(); row++) {
            for (int column = 0; column < grid.getColumns(); column++) {
                if (! grid.isPresent(column, row)) {
                    continue;
                }
                final float height = grid.getHeight(column, row);
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
                total += height;
                count++;
            }
        }
        json.startObject("height");
        if (count == 0) {
            json.nullValue("min");
            json.nullValue("max");
            json.nullValue("mean");
        } else {
            json.value("min", minimum);
            json.value("max", maximum);
            json.value("mean", total / count);
            json.value("range", maximum - minimum);
        }
        json.value("columns", count);
        json.endObject();
    }

    private void writeWaterStatistics(JsonWriter json, SurfaceGrid grid) throws IOException {
        int flooded = 0, present = 0, deepest = 0;
        for (int row = 0; row < grid.getRows(); row++) {
            for (int column = 0; column < grid.getColumns(); column++) {
                if (! grid.isPresent(column, row)) {
                    continue;
                }
                present++;
                if (grid.isFlooded(column, row)) {
                    flooded++;
                    deepest = Math.max(deepest, grid.getWaterLevel(column, row) - Math.round(grid.getHeight(column, row)));
                }
            }
        }
        json.startObject("water");
        json.value("floodedColumns", flooded);
        json.value("floodedFraction", (present > 0) ? ((double) flooded / present) : 0);
        json.value("greatestDepth", deepest);
        json.endObject();
    }

    private void writeTerrainStatistics(JsonWriter json, SurfaceGrid grid) throws IOException {
        final Map<Short, Integer> counts = new LinkedHashMap<>();
        int present = 0;
        for (int row = 0; row < grid.getRows(); row++) {
            for (int column = 0; column < grid.getColumns(); column++) {
                final short ordinal = grid.getTerrainOrdinal(column, row);
                if (ordinal != SurfaceGrid.ABSENT) {
                    counts.merge(ordinal, 1, Integer::sum);
                    present++;
                }
            }
        }
        final List<Map.Entry<Short, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        final Terrain[] terrains = Terrain.values();

        json.startArray("terrain");
        for (Map.Entry<Short, Integer> entry: entries) {
            final Terrain terrain = terrains[entry.getKey()];
            json.startObject();
            json.value("name", terrain.name());
            json.value("displayName", terrain.getName());
            json.value("description", terrain.getDescription());
            json.value("columns", entry.getValue());
            json.value("fraction", (present > 0) ? ((double) entry.getValue() / present) : 0);
            json.endObject();
        }
        json.endArray();
    }

    private void writeLayers(JsonWriter json, Dimension dimension) throws IOException {
        json.startArray("layers");
        for (Layer layer: dimension.getAllLayers(false)) {
            json.startObject();
            json.value("name", layer.getName());
            json.value("id", layer.getId());
            json.value("dataSize", layer.getDataSize().name());
            json.value("description", layer.getDescription());
            json.endObject();
        }
        json.endArray();
    }

    /**
     * The height and terrain of the map on a coarse grid, as arrays of rows running north to south.
     */
    private void writeGrids(JsonWriter json, SurfaceGrid grid, int step) throws IOException {
        final Terrain[] terrains = Terrain.values();
        json.startObject("grid");
        json.value("about", "Rows run north to south; within a row, columns run west to east. A null height, or a "
                + "terrain of null, means the map has no tile there.");
        json.value("sampleInterval", grid.getSampleInterval() * step);
        json.value("originX", grid.getOriginX());
        json.value("originZ", grid.getOriginY());
        json.value("columns", divideRoundingUp(grid.getColumns(), step));
        json.value("rows", divideRoundingUp(grid.getRows(), step));

        json.startArray("heights");
        for (int row = 0; row < grid.getRows(); row += step) {
            json.startInlineArray();
            for (int column = 0; column < grid.getColumns(); column += step) {
                if (grid.isPresent(column, row)) {
                    json.element(Math.round(grid.getHeight(column, row) * 10) / 10.0);
                } else {
                    json.element((String) null);
                }
            }
            json.endArray();
        }
        json.endArray();

        json.startArray("terrains");
        for (int row = 0; row < grid.getRows(); row += step) {
            json.startInlineArray();
            for (int column = 0; column < grid.getColumns(); column += step) {
                final short ordinal = grid.getTerrainOrdinal(column, row);
                json.element((ordinal != SurfaceGrid.ABSENT) ? terrains[ordinal].name() : null);
            }
            json.endArray();
        }
        json.endArray();
        json.endObject();
    }

    private void writeAsciiMap(JsonWriter json, SurfaceGrid grid, int step) throws IOException {
        final AsciiMapExporter.AsciiMap map = new AsciiMapExporter().render(grid);
        json.startObject("asciiMap");
        json.value("about", "A picture of the surface, one character per sampled column, north at the top.");
        json.value("sampleInterval", grid.getSampleInterval() * step);
        json.startObject("legend");
        for (Map.Entry<Character, String> entry: map.getLegend().entrySet()) {
            json.value(String.valueOf(entry.getKey()), entry.getValue());
        }
        json.endObject();
        json.startArray("rows");
        for (int row = 0; row < map.getRows().size(); row += step) {
            json.element(sampleRow(map.getRows().get(row), step));
        }
        json.endArray();
        json.endObject();
    }

    private static String sampleRow(String row, int step) {
        if (step <= 1) {
            return row;
        }
        final StringBuilder sampled = new StringBuilder((row.length() / step) + 1);
        for (int i = 0; i < row.length(); i += step) {
            sampled.append(row.charAt(i));
        }
        return sampled.toString();
    }

    /**
     * How many of the grid's samples to skip between the ones that go in the summary, so that the summary is at most
     * {@link ReadableExportSettings#getSummaryColumns()} wide.
     */
    private int getSummaryStep(SurfaceGrid grid) {
        final int longest = Math.max(grid.getColumns(), grid.getRows());
        return Math.max(1, divideRoundingUp(longest, settings.getSummaryColumns()));
    }

    private static int divideRoundingUp(int dividend, int divisor) {
        return ((dividend + divisor) - 1) / divisor;
    }

    private final ReadableExportSettings settings;
}
