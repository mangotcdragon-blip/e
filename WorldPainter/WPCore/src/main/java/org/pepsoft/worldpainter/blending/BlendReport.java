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
package org.pepsoft.worldpainter.blending;

import java.util.Locale;

/**
 * What a blend did, for the log, the status bar, or a command line.
 */
public final class BlendReport {
    BlendReport(int columnsExamined, int terrainChanged, int heightChanged, boolean usedGpu, long durationNanos) {
        this.columnsExamined = columnsExamined;
        this.terrainChanged = terrainChanged;
        this.heightChanged = heightChanged;
        this.usedGpu = usedGpu;
        this.durationNanos = durationNanos;
    }

    /** How many columns were considered. */
    public int getColumnsExamined() {
        return columnsExamined;
    }

    /** How many columns ended up with a different terrain. */
    public int getTerrainChanged() {
        return terrainChanged;
    }

    /** How many columns ended up with a different height. */
    public int getHeightChanged() {
        return heightChanged;
    }

    /** Whether any part of the work ran on a GPU. */
    public boolean isUsedGpu() {
        return usedGpu;
    }

    public long getDurationNanos() {
        return durationNanos;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%,d columns in %,d ms on the %s: %,d terrain and %,d height changes",
                columnsExamined, durationNanos / 1_000_000L, usedGpu ? "GPU" : "CPU", terrainChanged, heightChanged);
    }

    private final int columnsExamined, terrainChanged, heightChanged;
    private final boolean usedGpu;
    private final long durationNanos;
}
