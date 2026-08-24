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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Draws a map as a picture made of letters.
 *
 * <p>Not a toy: an ASCII map is the only form of a WorldPainter map that can be pasted into a chat window, committed
 * to a repository as a diffable file, grepped, or handed to a language model, and it is legible in Notepad on a
 * machine with nothing installed. One character stands for one sampled column, and the legend says what each one is.
 *
 * <p>Water is drawn as {@code ~} whatever terrain is under it, because on a map the shape of the coastline matters
 * more than what the seabed is made of, and columns with no tile are left blank.
 */
public class AsciiMapExporter {
    /**
     * Render a sampled surface as characters.
     */
    public AsciiMap render(SurfaceGrid grid) {
        final Map<Character, String> legend = new LinkedHashMap<>();
        final Map<Short, Character> characters = assignCharacters(grid, legend);
        final List<String> rows = new ArrayList<>(grid.getRows());
        final StringBuilder row = new StringBuilder(grid.getColumns());
        for (int y = 0; y < grid.getRows(); y++) {
            row.setLength(0);
            for (int x = 0; x < grid.getColumns(); x++) {
                if (! grid.isPresent(x, y)) {
                    row.append(ABSENT_CHARACTER);
                } else if (grid.isFlooded(x, y)) {
                    row.append(WATER_CHARACTER);
                } else {
                    row.append(characters.getOrDefault(grid.getTerrainOrdinal(x, y), UNKNOWN_CHARACTER));
                }
            }
            rows.add(row.toString());
        }
        return new AsciiMap(rows, legend, grid.getOriginX(), grid.getOriginY(), grid.getSampleInterval());
    }

    /**
     * Render a sampled surface and write it to a file, with a legend and a note about the scale.
     */
    public AsciiMap export(SurfaceGrid grid, File file) throws IOException {
        final AsciiMap map = render(grid);
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            out.write(map.toString());
        }
        return map;
    }

    /**
     * Give each terrain a character, preferring one of the letters of its own name so that the map is readable
     * without constantly consulting the legend.
     */
    private Map<Short, Character> assignCharacters(SurfaceGrid grid, Map<Character, String> legend) {
        final Terrain[] terrains = Terrain.values();
        final Map<Short, Character> characters = new LinkedHashMap<>();
        // In order of how much of the map each covers, so that the most common terrain gets the character closest to
        // its own name
        for (short ordinal: getTerrainsByFrequency(grid)) {
            final Terrain terrain = terrains[ordinal];
            final char character = chooseCharacter(terrain.getName(), legend.keySet());
            characters.put(ordinal, character);
            legend.put(character, terrain.getName());
        }
        if (containsWater(grid)) {
            legend.put(WATER_CHARACTER, "water or lava (above the surface)");
        }
        legend.put(ABSENT_CHARACTER, "no tile, or Void");
        return characters;
    }

    private char chooseCharacter(String name, Set<Character> taken) {
        for (int i = 0; i < name.length(); i++) {
            final char lower = Character.toLowerCase(name.charAt(i));
            if (Character.isLetter(lower) && (! taken.contains(lower))) {
                return lower;
            }
        }
        for (int i = 0; i < name.length(); i++) {
            final char upper = Character.toUpperCase(name.charAt(i));
            if (Character.isLetter(upper) && (! taken.contains(upper))) {
                return upper;
            }
        }
        for (char candidate: FALLBACK_CHARACTERS.toCharArray()) {
            if (! taken.contains(candidate)) {
                return candidate;
            }
        }
        return UNKNOWN_CHARACTER;
    }

    /**
     * The terrains present in the grid, most common first, with ties broken by ordinal so that the same map always
     * gets the same legend.
     */
    private short[] getTerrainsByFrequency(SurfaceGrid grid) {
        final Map<Short, Integer> counts = new LinkedHashMap<>();
        for (int y = 0; y < grid.getRows(); y++) {
            for (int x = 0; x < grid.getColumns(); x++) {
                final short ordinal = grid.getTerrainOrdinal(x, y);
                if (ordinal != SurfaceGrid.ABSENT) {
                    counts.merge(ordinal, 1, Integer::sum);
                }
            }
        }
        final List<Map.Entry<Short, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> a.getValue().equals(b.getValue())
                ? Short.compare(a.getKey(), b.getKey())
                : Integer.compare(b.getValue(), a.getValue()));
        final short[] result = new short[entries.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = entries.get(i).getKey();
        }
        return result;
    }

    private boolean containsWater(SurfaceGrid grid) {
        for (int y = 0; y < grid.getRows(); y++) {
            for (int x = 0; x < grid.getColumns(); x++) {
                if (grid.isFlooded(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A map drawn as characters, with the legend that explains it.
     */
    public static final class AsciiMap {
        AsciiMap(List<String> rows, Map<Character, String> legend, int originX, int originY, int sampleInterval) {
            this.rows = rows;
            this.legend = legend;
            this.originX = originX;
            this.originY = originY;
            this.sampleInterval = sampleInterval;
        }

        /** One string per row, north to south; one character per sampled column, west to east. */
        public List<String> getRows() {
            return rows;
        }

        /** What each character means. */
        public Map<Character, String> getLegend() {
            return legend;
        }

        public int getOriginX() {
            return originX;
        }

        public int getOriginY() {
            return originY;
        }

        public int getSampleInterval() {
            return sampleInterval;
        }

        /**
         * The map, its legend and a note about its scale, as a text file would contain them.
         */
        @Override
        public String toString() {
            final StringBuilder text = new StringBuilder();
            text.append("WorldPainter map\n");
            text.append(String.format(Locale.ROOT, "One character is %d x %d blocks. North is up, west is left.\n",
                    sampleInterval, sampleInterval));
            text.append(String.format(Locale.ROOT, "The top left character is the column at x = %d, z = %d.\n",
                    originX, originY));
            text.append('\n');
            for (String row: rows) {
                text.append(row).append('\n');
            }
            text.append("\nLegend\n");
            for (Map.Entry<Character, String> entry: legend.entrySet()) {
                text.append("  ").append(entry.getKey()).append("  ").append(entry.getValue()).append('\n');
            }
            return text.toString();
        }

        private final List<String> rows;
        private final Map<Character, String> legend;
        private final int originX, originY, sampleInterval;
    }

    /** Drawn wherever the water level is above the surface. */
    public static final char WATER_CHARACTER = '~';

    /** Drawn where the map has no tile, or the column is Void. */
    public static final char ABSENT_CHARACTER = ' ';

    /** Drawn when there are more terrains than there are characters to give them. */
    public static final char UNKNOWN_CHARACTER = '?';

    private static final String FALLBACK_CHARACTERS = "0123456789+=*#%&$@<>/\\|";
}
