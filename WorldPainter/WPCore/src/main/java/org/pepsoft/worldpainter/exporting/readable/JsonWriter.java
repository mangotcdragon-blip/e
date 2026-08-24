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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * A small streaming JSON writer, enough for the world summary.
 *
 * <p>WorldPainter's core has no JSON library on its class path, and the summary is the only thing that needs one.
 * Writing it out is a few dozen lines, and doing it as a stream means a summary of a large map never has to exist in
 * memory as a tree of objects first.
 *
 * <p>The output is indented, because the whole point of the summary is that a person or a language model can read it.
 * Arrays of numbers are written on one line, which keeps a sampled height grid compact without making it unreadable.
 */
final class JsonWriter implements AutoCloseable {
    JsonWriter(Writer out) {
        this.out = out;
    }

    JsonWriter startObject() throws IOException {
        separate();
        out.write('{');
        contexts.push(new Context());
        return this;
    }

    JsonWriter startObject(String name) throws IOException {
        writeName(name);
        out.write('{');
        contexts.push(new Context());
        return this;
    }

    JsonWriter endObject() throws IOException {
        final Context context = contexts.pop();
        if (! context.empty) {
            newLine();
        }
        out.write('}');
        return this;
    }

    JsonWriter startArray(String name) throws IOException {
        writeName(name);
        out.write('[');
        contexts.push(new Context());
        return this;
    }

    /**
     * Start an array whose elements are all written on the same line. For grids and histograms, where one number per
     * line would make the file ten times longer and no clearer.
     */
    JsonWriter startInlineArray(String name) throws IOException {
        writeName(name);
        out.write('[');
        final Context context = new Context();
        context.inline = true;
        contexts.push(context);
        return this;
    }

    JsonWriter startInlineArray() throws IOException {
        separate();
        out.write('[');
        final Context context = new Context();
        context.inline = true;
        contexts.push(context);
        return this;
    }

    JsonWriter endArray() throws IOException {
        final Context context = contexts.pop();
        if ((! context.empty) && (! context.inline)) {
            newLine();
        }
        out.write(']');
        return this;
    }

    JsonWriter value(String name, String value) throws IOException {
        writeName(name);
        writeString(value);
        return this;
    }

    JsonWriter value(String name, long value) throws IOException {
        writeName(name);
        out.write(Long.toString(value));
        return this;
    }

    JsonWriter value(String name, double value) throws IOException {
        writeName(name);
        writeNumber(value);
        return this;
    }

    JsonWriter value(String name, boolean value) throws IOException {
        writeName(name);
        out.write(value ? "true" : "false");
        return this;
    }

    JsonWriter nullValue(String name) throws IOException {
        writeName(name);
        out.write("null");
        return this;
    }

    JsonWriter element(String value) throws IOException {
        separate();
        writeString(value);
        return this;
    }

    JsonWriter element(long value) throws IOException {
        separate();
        out.write(Long.toString(value));
        return this;
    }

    JsonWriter element(double value) throws IOException {
        separate();
        writeNumber(value);
        return this;
    }

    @Override
    public void close() throws IOException {
        out.write('\n');
        out.flush();
    }

    private void writeName(String name) throws IOException {
        separate();
        writeString(name);
        out.write(": ");
    }

    /**
     * Write the comma and the indentation that go before the next value, if it is not the first in its container.
     */
    private void separate() throws IOException {
        final Context context = contexts.peek();
        if (context == null) {
            return;
        }
        if (! context.empty) {
            out.write(',');
            if (context.inline) {
                out.write(' ');
            }
        }
        context.empty = false;
        if (! context.inline) {
            newLine();
        }
    }

    private void newLine() throws IOException {
        out.write('\n');
        for (int i = contexts.size(); i > 0; i--) {
            out.write("  ");
        }
    }

    private void writeNumber(double value) throws IOException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            out.write("null");
        } else if (value == Math.rint(value) && (Math.abs(value) < 1e15)) {
            out.write(Long.toString((long) value));
        } else {
            out.write(String.format(Locale.ROOT, "%.4f", value));
        }
    }

    private void writeString(String value) throws IOException {
        if (value == null) {
            out.write("null");
            return;
        }
        out.write('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.write("\\\"");
                    break;
                case '\\':
                    out.write("\\\\");
                    break;
                case '\n':
                    out.write("\\n");
                    break;
                case '\r':
                    out.write("\\r");
                    break;
                case '\t':
                    out.write("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.write(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.write(c);
                    }
            }
        }
        out.write('"');
    }

    /**
     * One nesting level: whether anything has been written into it yet, and whether it is being kept on one line.
     */
    private static final class Context {
        boolean empty = true;
        boolean inline;
    }

    private final Writer out;
    private final Deque<Context> contexts = new ArrayDeque<>();
}
