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

import java.io.IOException;
import java.io.Writer;

/**
 * Writes the handful of Wavefront OBJ statements the mesh exporter needs, quickly.
 *
 * <p>A mesh of any size is millions of lines, and {@code String.format} is far too slow to produce them one at a
 * time, so numbers are formatted straight into a reusable buffer. Coordinates are written with three decimals, which
 * is a thousandth of a block: below anything that could matter, and short enough to keep the file readable in a text
 * editor, which is rather the point of exporting OBJ in the first place.
 */
final class ObjWriter {
    ObjWriter(Writer out) {
        this.out = out;
    }

    void comment(String text) throws IOException {
        out.write("# ");
        out.write(text);
        out.write('\n');
    }

    void blankLine() throws IOException {
        out.write('\n');
    }

    void statement(String keyword, String value) throws IOException {
        out.write(keyword);
        out.write(' ');
        out.write(value);
        out.write('\n');
    }

    /**
     * A vertex. OBJ is Y up, so a map coordinate becomes x, and the height becomes y.
     */
    void vertex(float x, float y, float z) throws IOException {
        line.setLength(0);
        line.append('v').append(' ');
        appendNumber(line, x);
        line.append(' ');
        appendNumber(line, y);
        line.append(' ');
        appendNumber(line, z);
        line.append('\n');
        out.write(line.toString());
    }

    void normal(float x, float y, float z) throws IOException {
        line.setLength(0);
        line.append("vn").append(' ');
        appendNumber(line, x);
        line.append(' ');
        appendNumber(line, y);
        line.append(' ');
        appendNumber(line, z);
        line.append('\n');
        out.write(line.toString());
    }

    /**
     * A quadrilateral face, by one based vertex index.
     */
    void quad(int a, int b, int c, int d) throws IOException {
        line.setLength(0);
        line.append('f').append(' ').append(a).append(' ').append(b).append(' ').append(c).append(' ').append(d).append('\n');
        out.write(line.toString());
    }

    /**
     * A quadrilateral face whose vertices carry normals with the same indices.
     */
    void quadWithNormals(int a, int b, int c, int d) throws IOException {
        line.setLength(0);
        line.append('f').append(' ');
        line.append(a).append("//").append(a).append(' ');
        line.append(b).append("//").append(b).append(' ');
        line.append(c).append("//").append(c).append(' ');
        line.append(d).append("//").append(d).append('\n');
        out.write(line.toString());
    }

    /**
     * Append a number with at most three decimals, without an exponent and without trailing zeroes.
     */
    static void appendNumber(StringBuilder buffer, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            buffer.append('0');
            return;
        }
        long scaled = Math.round((double) value * 1000);
        if (scaled < 0) {
            buffer.append('-');
            scaled = -scaled;
        }
        buffer.append(scaled / 1000);
        final int fraction = (int) (scaled % 1000);
        if (fraction != 0) {
            buffer.append('.');
            buffer.append((char) ('0' + (fraction / 100)));
            if ((fraction % 100) != 0) {
                buffer.append((char) ('0' + ((fraction / 10) % 10)));
                if ((fraction % 10) != 0) {
                    buffer.append((char) ('0' + (fraction % 10)));
                }
            }
        }
    }

    private final Writer out;
    private final StringBuilder line = new StringBuilder(64);
}
