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

import org.pepsoft.worldpainter.Terrain;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Writes the surface as a table: one row per sampled column, with its coordinates, height, water level and terrain.
 *
 * <p>The least exciting and possibly the most useful of the readable exports. A CSV opens in a spreadsheet, loads in
 * one line of Python or R, imports into a database, and reads perfectly well in Notepad, so anything anybody wants to
 * work out about a map that WorldPainter does not already tell them starts here.
 */
public class CsvGridExporter {
    /**
     * Write the grid to a file.
     *
     * @return The number of data rows written, not counting the header.
     */
    public int export(SurfaceGrid grid, File file) throws IOException {
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8), BUFFER_SIZE)) {
            return export(grid, out);
        }
    }

    /**
     * Write the grid to a writer, which is not closed.
     *
     * @return The number of data rows written, not counting the header.
     */
    public int export(SurfaceGrid grid, Writer out) throws IOException {
        out.write("x,z,height,water_level,under_water,terrain\n");
        final StringBuilder line = new StringBuilder(64);
        int rowsWritten = 0;
        for (int row = 0; row < grid.getRows(); row++) {
            final int worldY = grid.getWorldY(row);
            for (int column = 0; column < grid.getColumns(); column++) {
                if (! grid.isPresent(column, row)) {
                    continue;
                }
                final Terrain terrain = grid.getTerrain(column, row);
                line.setLength(0);
                line.append(grid.getWorldX(column)).append(',');
                line.append(worldY).append(',');
                line.append(String.format(Locale.ROOT, "%.3f", grid.getHeight(column, row))).append(',');
                line.append(grid.getWaterLevel(column, row)).append(',');
                line.append(grid.isFlooded(column, row) ? "true" : "false").append(',');
                line.append(quote(terrain.getName()));
                line.append('\n');
                out.write(line.toString());
                rowsWritten++;
            }
        }
        return rowsWritten;
    }

    /**
     * Quote a field if it contains anything that would confuse a CSV reader.
     */
    private static String quote(String value) {
        if ((value.indexOf(',') < 0) && (value.indexOf('"') < 0) && (value.indexOf('\n') < 0)) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static final int BUFFER_SIZE = 1 << 18;
}
