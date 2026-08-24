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
import org.pepsoft.util.SubProgressReceiver;
import org.pepsoft.worldpainter.Dimension;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Writes a map out in formats anything can read.
 *
 * <p>A {@code .world} file, and the region files WorldPainter exports, are binary and understood by exactly two
 * programs. That is fine until you want to look at a map in Blender, analyse it in a spreadsheet, put a picture of it
 * in a README, describe it to a language model, or simply see what is in it on a machine that has neither WorldPainter
 * nor Minecraft installed. This writes the same map as text:
 *
 * <ul>
 *   <li>a Wavefront OBJ mesh with an MTL material library, which every 3D application reads;</li>
 *   <li>a JSON summary of what the map is and what it is made of, including a coarse height and terrain grid;</li>
 *   <li>an ASCII map, legible in Notepad and pasteable into anything;</li>
 *   <li>a CSV table of every sampled column.</li>
 * </ul>
 *
 * <p>The dimension is walked once and shared between all of them, so asking for all four costs barely more than
 * asking for one.
 */
public class ReadableExporter {
    public ReadableExporter(ReadableExportSettings settings) {
        this.settings = (settings != null) ? settings : ReadableExportSettings.builder().build();
    }

    /**
     * Write every format.
     *
     * @param dimension        The dimension to export.
     * @param directory        The directory to write into. Created if it does not exist.
     * @param baseName         The base name for the files, without an extension.
     * @param progressReceiver Notified of progress, or {@code null}.
     * @return The files that were written.
     */
    public List<File> exportAll(Dimension dimension, File directory, String baseName, ProgressReceiver progressReceiver)
            throws IOException, OperationCancelled {
        return export(dimension, directory, baseName, EnumSet.allOf(Format.class), progressReceiver);
    }

    /**
     * Write the requested formats.
     *
     * @param dimension        The dimension to export.
     * @param directory        The directory to write into. Created if it does not exist.
     * @param baseName         The base name for the files, without an extension.
     * @param formats          The formats to write.
     * @param progressReceiver Notified of progress, or {@code null}.
     * @return The files that were written.
     */
    public List<File> export(Dimension dimension, File directory, String baseName, Set<Format> formats,
                             ProgressReceiver progressReceiver) throws IOException, OperationCancelled {
        if (formats.isEmpty()) {
            return Collections.emptyList();
        }
        if ((! directory.isDirectory()) && (! directory.mkdirs())) {
            throw new IOException("Could not create directory " + directory);
        }
        final String safeBaseName = sanitise(baseName);

        final SurfaceGrid grid = SurfaceGrid.sample(dimension, settings.getArea(), settings.getSampleInterval(),
                subProgress(progressReceiver, 0f, SAMPLING_SHARE));

        final List<File> written = new ArrayList<>(formats.size());
        float progress = SAMPLING_SHARE;
        final float share = (1f - SAMPLING_SHARE) / formats.size();
        for (Format format: formats) {
            if (progressReceiver != null) {
                progressReceiver.checkForCancellation();
                progressReceiver.setMessage("Writing " + format.getDescription());
            }
            final File file = new File(directory, safeBaseName + format.getExtension());
            switch (format) {
                case OBJ:
                    new WavefrontObjExporter(settings).export(dimension, grid, file, subProgress(progressReceiver, progress, progress + share));
                    written.add(file);
                    written.add(WavefrontObjExporter.getMaterialFile(file));
                    break;
                case JSON:
                    new WorldSummaryExporter(settings).export(dimension, grid, file);
                    written.add(file);
                    break;
                case ASCII:
                    new AsciiMapExporter().export(grid, file);
                    written.add(file);
                    break;
                case CSV:
                    new CsvGridExporter().export(grid, file);
                    written.add(file);
                    break;
                default:
                    throw new InternalError("Unknown format " + format);
            }
            progress += share;
            if (progressReceiver != null) {
                progressReceiver.setProgress(progress);
            }
        }
        if (progressReceiver != null) {
            progressReceiver.done();
        }
        return written;
    }

    private static ProgressReceiver subProgress(ProgressReceiver progressReceiver, float from, float to)
            throws OperationCancelled {
        return (progressReceiver != null) ? new SubProgressReceiver(progressReceiver, from, to - from) : null;
    }

    /**
     * Strip anything from a name that would be trouble in a file name on any of the platforms WorldPainter runs on.
     */
    static String sanitise(String baseName) {
        if ((baseName == null) || baseName.trim().isEmpty()) {
            return "map";
        }
        final StringBuilder safe = new StringBuilder(baseName.length());
        for (char c: baseName.trim().toCharArray()) {
            safe.append((Character.isLetterOrDigit(c) || (c == '-') || (c == '_')) ? c : '_');
        }
        return safe.toString();
    }

    /**
     * The formats a map can be written out in.
     */
    public enum Format {
        /** A Wavefront OBJ mesh, plus the MTL material library that goes with it. */
        OBJ(".obj", "a 3D mesh"),

        /** A JSON description of the map, for a program or a language model to read. */
        JSON(".json", "a description of the map"),

        /** A picture of the map made of letters. */
        ASCII(".txt", "an ASCII map"),

        /** One row per sampled column. */
        CSV(".csv", "a table of columns");

        Format(String extension, String description) {
            this.extension = extension;
            this.description = description;
        }

        public String getExtension() {
            return extension;
        }

        public String getDescription() {
            return description;
        }

        private final String extension, description;
    }

    private final ReadableExportSettings settings;

    /** How much of the progress bar sampling the dimension gets. It is the slowest part by a wide margin. */
    private static final float SAMPLING_SHARE = 0.4f;
}
