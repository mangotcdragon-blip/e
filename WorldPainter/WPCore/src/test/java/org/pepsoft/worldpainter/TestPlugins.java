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
package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.plugins.WPPluginManager;

/**
 * Initialises the plugin manager for a test, once per JVM.
 *
 * <p>{@link WPPluginManager#initialise} throws if it has already run, and Surefire runs the whole suite in one JVM, so
 * every test that needs a platform provider has to go through here rather than calling it directly.
 */
public final class TestPlugins {
    private TestPlugins() {
        // Prevent instantiation
    }

    public static synchronized void ensureInitialised() {
        if (WPPluginManager.getInstance() == null) {
            WPPluginManager.initialise(null, WPContext.INSTANCE);
        }
    }
}
