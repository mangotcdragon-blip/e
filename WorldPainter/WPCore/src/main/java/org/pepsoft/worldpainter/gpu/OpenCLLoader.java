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
package org.pepsoft.worldpainter.gpu;

import org.lwjgl.system.Configuration;
import org.lwjgl.system.Library;
import org.pepsoft.util.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Finds and loads the system's OpenCL ICD loader.
 *
 * <p>LWJGL by default asks the operating system for a library called {@code OpenCL}, which resolves to
 * {@code libOpenCL.so} on Linux. That file is part of the <em>development</em> package of the ICD loader and is
 * frequently absent on an end user's machine, which has only the versioned {@code libOpenCL.so.1} that the drivers
 * install. Rather than telling users to install a development package, this class probes the names that actually occur
 * in the wild and points LWJGL at the first one that loads.
 *
 * <p>All of this happens before any class in {@code org.lwjgl.opencl} is touched, because LWJGL resolves the library
 * once, in a static initialiser, and caches the failure forever if it does not find it.
 */
final class OpenCLLoader {
    private OpenCLLoader() {
        // Prevent instantiation
    }

    /**
     * Make sure the OpenCL ICD loader is loadable by LWJGL.
     *
     * @return {@code true} if an OpenCL library was found. {@code false} means the machine has no OpenCL runtime
     * installed at all, which is not an error; it just means no acceleration.
     */
    static synchronized boolean ensureLoaded() {
        if (loaded != null) {
            return loaded;
        }
        if (Configuration.OPENCL_LIBRARY_NAME.get() != null) {
            // The user pointed us at a specific library; respect it and let any failure surface.
            loaded = true;
            return true;
        }
        // LWJGL prints a multi-line complaint to its debug stream every time a library fails to load. That is a
        // normal outcome here - the whole point is to try several names - so send it somewhere else while probing,
        // and let the outcome be reported once, at debug level, below.
        final Object originalDebugStream = Configuration.DEBUG_STREAM.get();
        Configuration.DEBUG_STREAM.set(SILENCE);
        try {
            for (String candidate: getCandidateNames()) {
                try {
                    Library.loadNative(OpenCLLoader.class, "org.lwjgl.opencl", candidate);
                    Configuration.OPENCL_LIBRARY_NAME.set(candidate);
                    logger.debug("Loaded OpenCL ICD loader \"{}\"", candidate);
                    loaded = true;
                    return true;
                } catch (Throwable t) {
                    logger.debug("OpenCL ICD loader \"{}\" not available ({})", candidate, t.getMessage());
                }
            }
        } finally {
            if (originalDebugStream != null) {
                Configuration.DEBUG_STREAM.set(originalDebugStream);
            } else {
                Configuration.DEBUG_STREAM.set(System.err);
            }
        }
        logger.debug("No OpenCL ICD loader found; GPU acceleration is not available on this machine");
        loaded = false;
        return false;
    }

    private static String[] getCandidateNames() {
        switch (SystemUtils.getOS()) {
            case WINDOWS:
                return new String[] { "OpenCL" };
            case MAC:
                return new String[] { "/System/Library/Frameworks/OpenCL.framework/OpenCL", "OpenCL" };
            default:
                // Linux and anything else Unix-like. libOpenCL.so is the development symlink and is often missing;
                // libOpenCL.so.1 is what the drivers and the ICD loader packages actually install.
                return new String[] { "OpenCL", "libOpenCL.so.1", "libOpenCL.so" };
        }
    }

    private static Boolean loaded;

    /** Swallows LWJGL's diagnostics while candidate library names are being tried. */
    private static final PrintStream SILENCE = new PrintStream(OutputStream.nullOutputStream(), false);

    private static final Logger logger = LoggerFactory.getLogger(OpenCLLoader.class);
}
