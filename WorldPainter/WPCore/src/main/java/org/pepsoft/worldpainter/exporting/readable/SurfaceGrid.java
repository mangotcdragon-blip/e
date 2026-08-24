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
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.layers.Void;

import java.awt.Rectangle;

import static org.pepsoft.worldpainter.Constants.TILE_SIZE_BITS;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE_MASK;

/**
 * A snapshot of the surface of a dimension, on a regular grid.
 *
 * <p>Every readable export needs the same thing - the height, terrain and water level of a set of columns - and
 * reading them out of the dimension is much more expensive than anything done with them afterwards. Taking the
 * snapshot once and handing it to each exporter means a caller who wants a mesh, a summary, a map and a spreadsheet
 * walks the dimension once instead of four times.
 */
public final class SurfaceGrid {
    private SurfaceGrid(int originX, int originY, int sampleInterval, int columns, int rows) {
        this.originX = originX;
        this.originY = originY;
        this.sampleInterval = sampleInterval;
        this.columns = columns;
        this.rows = rows;
        heights = new float[columns * rows];
        terrains = new short[columns * rows];
        waterLevels = new short[columns * rows];
        flooded = new boolean[columns * rows];
    }

    /**
     * Sample a dimension.
     *
     * @param dimension        The dimension to sample.
     * @param area             The area to sample, in world coordinates, or {@code null} for the whole extent.
     * @param sampleInterval   Sample every n'th column.
     * @param progressReceiver Notified of progress, or {@code null}.
     */
    public static SurfaceGrid sample(Dimension dimension, Rectangle area, int sampleInterval, ProgressReceiver progressReceiver)
            throws OperationCancelled {
        if (sampleInterval < 1) {
            throw new IllegalArgumentException("sampleInterval must be at least one");
        }
        final Rectangle extent = (area != null) ? area : dimension.getBlockExtent();
        if ((extent == null) || extent.isEmpty()) {
            return new SurfaceGrid(0, 0, sampleInterval, 0, 0);
        }
        final int columns = divideRoundingUp(extent.width, sampleInterval);
        final int rows = divideRoundingUp(extent.height, sampleInterval);
        final SurfaceGrid grid = new SurfaceGrid(extent.x, extent.y, sampleInterval, columns, rows);
        for (int row = 0; row < rows; row++) {
            if (progressReceiver != null) {
                progressReceiver.checkForCancellation();
            }
            final int worldY = extent.y + (row * sampleInterval);
            for (int column = 0; column < columns; column++) {
                final int worldX = extent.x + (column * sampleInterval);
                final int index = (row * columns) + column;
                final Tile tile = dimension.getTile(worldX >> TILE_SIZE_BITS, worldY >> TILE_SIZE_BITS);
                if (tile == null) {
                    grid.terrains[index] = ABSENT;
                    continue;
                }
                final int xInTile = worldX & TILE_SIZE_MASK, yInTile = worldY & TILE_SIZE_MASK;
                if (tile.getBitLayerValue(Void.INSTANCE, xInTile, yInTile)) {
                    grid.terrains[index] = ABSENT;
                    continue;
                }
                grid.heights[index] = tile.getHeight(xInTile, yInTile);
                grid.terrains[index] = (short) tile.getTerrain(xInTile, yInTile).ordinal();
                final int waterLevel = tile.getWaterLevel(xInTile, yInTile);
                grid.waterLevels[index] = (short) waterLevel;
                grid.flooded[index] = waterLevel > Math.round(grid.heights[index]);
            }
            if (progressReceiver != null) {
                progressReceiver.setProgress((float) (row + 1) / rows);
            }
        }
        return grid;
    }

    /** The world X coordinate of column zero. */
    public int getOriginX() {
        return originX;
    }

    /** The world Y coordinate of row zero. */
    public int getOriginY() {
        return originY;
    }

    /** The distance, in blocks, between adjacent samples. */
    public int getSampleInterval() {
        return sampleInterval;
    }

    public int getColumns() {
        return columns;
    }

    public int getRows() {
        return rows;
    }

    /** The world X coordinate of a column. */
    public int getWorldX(int column) {
        return originX + (column * sampleInterval);
    }

    /** The world Y coordinate of a row. */
    public int getWorldY(int row) {
        return originY + (row * sampleInterval);
    }

    /**
     * Whether there is anything at all at this sample: {@code false} where the map has no tile, or the column is
     * marked as Void.
     */
    public boolean isPresent(int column, int row) {
        return ((column >= 0) && (column < columns) && (row >= 0) && (row < rows))
                && (terrains[(row * columns) + column] != ABSENT);
    }

    /** The surface height of a sample, or zero where there is nothing. */
    public float getHeight(int column, int row) {
        return isPresent(column, row) ? heights[(row * columns) + column] : 0f;
    }

    /** The terrain of a sample, or {@code null} where there is nothing. */
    public Terrain getTerrain(int column, int row) {
        return isPresent(column, row) ? TERRAINS[terrains[(row * columns) + column]] : null;
    }

    /** The terrain ordinal of a sample, or {@link #ABSENT}. */
    public short getTerrainOrdinal(int column, int row) {
        return ((column >= 0) && (column < columns) && (row >= 0) && (row < rows))
                ? terrains[(row * columns) + column] : ABSENT;
    }

    /** The water or lava level of a sample. Only meaningful where {@link #isFlooded} is {@code true}. */
    public int getWaterLevel(int column, int row) {
        return waterLevels[(row * columns) + column];
    }

    /** Whether the water level of a sample is above its surface, that is, whether it is under water. */
    public boolean isFlooded(int column, int row) {
        return isPresent(column, row) && flooded[(row * columns) + column];
    }

    /** The number of samples that exist. */
    public int getPresentCount() {
        int count = 0;
        for (short terrain: terrains) {
            if (terrain != ABSENT) {
                count++;
            }
        }
        return count;
    }

    private static int divideRoundingUp(int dividend, int divisor) {
        return ((dividend + divisor) - 1) / divisor;
    }

    private final int originX, originY, sampleInterval, columns, rows;
    private final float[] heights;
    private final short[] terrains;
    private final short[] waterLevels;
    private final boolean[] flooded;

    /** The terrain ordinal used for a sample where the map has nothing. */
    public static final short ABSENT = -1;

    private static final Terrain[] TERRAINS = Terrain.values();
}
