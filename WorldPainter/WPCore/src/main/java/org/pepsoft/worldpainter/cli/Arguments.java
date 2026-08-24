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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The command line, parsed.
 *
 * <p>Deliberately small: {@code --name value}, {@code --name=value}, {@code --flag}, {@code --no-flag}, and
 * everything else in order as a positional argument. Adding a command line parsing library to WorldPainter's core for
 * this would be a poor trade.
 *
 * <p>Every option that is asked for is remembered, so that {@link #getUnrecognised()} can report the ones that were
 * not. A typo in an option name would otherwise silently do nothing, which on a command that rewrites a map is not
 * something to find out about afterwards.
 */
final class Arguments {
    private Arguments(List<String> positional, Map<String, String> options) {
        this.positional = positional;
        this.options = options;
    }

    /**
     * Parse a command line.
     *
     * @param flagNames The names of the options that are flags, and so take no value.
     */
    static Arguments parse(String[] argv, Set<String> flagNames) {
        final List<String> positional = new ArrayList<>();
        final Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < argv.length; i++) {
            final String argument = argv[i];
            if (! argument.startsWith("--")) {
                positional.add(argument);
                continue;
            }
            final String body = argument.substring(2);
            final int equals = body.indexOf('=');
            if (equals >= 0) {
                options.put(normalise(body.substring(0, equals)), body.substring(equals + 1));
            } else if (body.startsWith("no-")) {
                options.put(normalise(body.substring(3)), "false");
            } else if (flagNames.contains(normalise(body))) {
                options.put(normalise(body), "true");
            } else if ((i + 1) < argv.length) {
                options.put(normalise(body), argv[++i]);
            } else {
                throw new IllegalArgumentException("Option --" + body + " needs a value");
            }
        }
        return new Arguments(positional, options);
    }

    List<String> getPositional() {
        return positional;
    }

    String getPositional(int index, String defaultValue) {
        return (index < positional.size()) ? positional.get(index) : defaultValue;
    }

    String getString(String name, String defaultValue) {
        asked.add(normalise(name));
        return options.getOrDefault(normalise(name), defaultValue);
    }

    int getInt(String name, int defaultValue) {
        final String value = getString(name, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a whole number, but was \"" + value + '"');
        }
    }

    long getLong(String name, long defaultValue) {
        final String value = getString(name, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a whole number, but was \"" + value + '"');
        }
    }

    float getFloat(String name, float defaultValue) {
        final String value = getString(name, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a number, but was \"" + value + '"');
        }
    }

    boolean getBoolean(String name, boolean defaultValue) {
        final String value = getString(name, null);
        if (value == null) {
            return defaultValue;
        }
        return ! ("false".equalsIgnoreCase(value.trim()) || "no".equalsIgnoreCase(value.trim())
                || "off".equalsIgnoreCase(value.trim()) || "0".equals(value.trim()));
    }

    <E extends Enum<E>> E getEnum(Class<E> type, String name, E defaultValue) {
        final String value = getString(name, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            final StringBuilder allowed = new StringBuilder();
            for (E constant: type.getEnumConstants()) {
                allowed.append((allowed.length() > 0) ? ", " : "").append(constant.name().toLowerCase(Locale.ROOT));
            }
            throw new IllegalArgumentException("--" + name + " must be one of: " + allowed + ", but was \"" + value + '"');
        }
    }

    /**
     * The options that were given but never asked for: almost always a typo.
     */
    List<String> getUnrecognised() {
        final List<String> unrecognised = new ArrayList<>();
        for (String name: options.keySet()) {
            if (! asked.contains(name)) {
                unrecognised.add(name);
            }
        }
        return unrecognised;
    }

    /**
     * Option names are matched ignoring case and hyphens, so {@code --heightRadius}, {@code --height-radius} and
     * {@code --HEIGHT_RADIUS} are all the same option.
     */
    private static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private final List<String> positional;
    private final Map<String, String> options;
    private final Set<String> asked = new HashSet<>();
}
