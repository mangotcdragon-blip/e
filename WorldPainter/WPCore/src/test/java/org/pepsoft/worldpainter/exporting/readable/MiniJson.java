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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict JSON reader, for checking that the summary exporter produces valid JSON.
 *
 * <p>Deliberately unforgiving: it accepts exactly what RFC 8259 allows and nothing else, so that a summary which only
 * happens to look like JSON does not pass. WorldPainter's core has no JSON library on its class path, and a parser
 * that only has to be correct, not fast, is short enough to keep in the tests.
 */
final class MiniJson {
    private MiniJson(String text) {
        this.text = text;
    }

    /**
     * Parse a JSON document.
     *
     * @return A {@link Map}, {@link List}, {@link String}, {@link Double}, {@link Boolean}, or {@code null}.
     * @throws IllegalArgumentException if the text is not valid JSON.
     */
    static Object parse(String text) {
        final MiniJson parser = new MiniJson(text);
        parser.skipWhitespace();
        final Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position < text.length()) {
            throw parser.error("trailing content");
        }
        return value;
    }

    private Object readValue() {
        if (position >= text.length()) {
            throw error("unexpected end of document");
        }
        switch (text.charAt(position)) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        final Map<String, Object> object = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a member name");
            }
            final String name = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("expected ':'");
            }
            position++;
            skipWhitespace();
            object.put(name, readValue());
            skipWhitespace();
            final char c = peek();
            if (c == ',') {
                position++;
            } else if (c == '}') {
                position++;
                return object;
            } else {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray() {
        final List<Object> array = new ArrayList<>();
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            final char c = peek();
            if (c == ',') {
                position++;
            } else if (c == ']') {
                position++;
                return array;
            } else {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String readString() {
        position++;
        final StringBuilder value = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw error("unterminated string");
            }
            final char c = text.charAt(position++);
            if (c == '"') {
                return value.toString();
            } else if (c == '\\') {
                final char escape = text.charAt(position++);
                switch (escape) {
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u':
                        value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                        break;
                    default:
                        throw error("invalid escape '\\" + escape + '\'');
                }
            } else if (c < 0x20) {
                throw error("unescaped control character in a string");
            } else {
                value.append(c);
            }
        }
    }

    private Double readNumber() {
        final int start = position;
        if (peek() == '-') {
            position++;
        }
        readDigits();
        if ((position < text.length()) && (text.charAt(position) == '.')) {
            position++;
            readDigits();
        }
        if ((position < text.length()) && ((text.charAt(position) == 'e') || (text.charAt(position) == 'E'))) {
            position++;
            if ((position < text.length()) && ((text.charAt(position) == '+') || (text.charAt(position) == '-'))) {
                position++;
            }
            readDigits();
        }
        if (position == start) {
            throw error("expected a value");
        }
        return Double.valueOf(text.substring(start, position));
    }

    private void readDigits() {
        final int start = position;
        while ((position < text.length()) && (text.charAt(position) >= '0') && (text.charAt(position) <= '9')) {
            position++;
        }
        if (position == start) {
            throw error("expected a digit");
        }
    }

    private void expect(String literal) {
        if (! text.startsWith(literal, position)) {
            throw error("expected \"" + literal + '"');
        }
        position += literal.length();
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("unexpected end of document");
        }
        return text.charAt(position);
    }

    private void skipWhitespace() {
        while ((position < text.length()) && (" \t\r\n".indexOf(text.charAt(position)) >= 0)) {
            position++;
        }
    }

    private IllegalArgumentException error(String message) {
        final int from = Math.max(0, position - 40), to = Math.min(text.length(), position + 40);
        return new IllegalArgumentException("Invalid JSON at offset " + position + ": " + message
                + "\n..." + text.substring(from, to).replace('\n', ' ') + "...");
    }

    private final String text;
    private int position;
}
