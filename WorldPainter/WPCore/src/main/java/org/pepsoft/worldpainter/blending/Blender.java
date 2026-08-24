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

import org.pepsoft.util.ProgressReceiver;
import org.pepsoft.util.ProgressReceiver.OperationCancelled;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.gpu.BlendKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;

import static org.pepsoft.worldpainter.Constants.TILE_SIZE_BITS;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE_MASK;

/**
 * Softens the hard edges a painted map inevitably has: the straight boundary where one terrain was painted against
 * another, and the stair steps left by a height brush.
 *
 * <p>Blending is applied to a dimension in place, one block of columns at a time, with each block reading a margin of
 * neighbouring columns so that the result does not depend on how the work was divided up. On a machine with a GPU the
 * per-column arithmetic runs there; otherwise it runs on the CPU, and the two produce identical maps.
 *
 * <p>The outermost columns of a dimension are left alone: a column is only blended when every column it would have to
 * read exists. That leaves a border a few blocks wide untouched rather than blending against nothing, which would pull
 * the edge of the map inwards.
 *
 * <p>Instances are not thread safe. Create one per operation.
 */
public final class Blender {
    public Blender(BlendSettings settings) {
        if (settings == null) {
            throw new NullPointerException("settings");
        }
        this.settings = settings;
    }

    /**
     * Blend an entire dimension.
     */
    public BlendReport blend(Dimension dimension, ProgressReceiver progressReceiver) throws OperationCancelled {
        return blend(dimension, dimension.getBlockExtent(), progressReceiver);
    }

    /**
     * Blend part of a dimension.
     *
     * @param dimension       The dimension to blend, in place.
     * @param area            The area to blend, in world coordinates.
     * @param progressReceiver Notified of progress, or {@code null}.
     * @return What was done.
     */
    public BlendReport blend(Dimension dimension, Rectangle area, ProgressReceiver progressReceiver) throws OperationCancelled {
        if ((area == null) || area.isEmpty() || settings.isNoOp()) {
            return new BlendReport(0, 0, 0, false, 0L);
        }
        final long start = System.nanoTime();
        final int terrainMargin = BlendAlgorithm.getEffectiveRadius(settings);
        final int heightMargin = ((settings.getHeightStrength() > 0f) ? settings.getHeightRadius() : 0);
        boolean usedGpu = false;
        int columnsExamined = 0, terrainChanged = 0, heightChanged = 0;

        final int blockCountX = divideRoundingUp(area.width, BLOCK_SIZE);
        final int blockCountY = divideRoundingUp(area.height, BLOCK_SIZE);
        final int totalBlocks = blockCountX * blockCountY;
        int blocksDone = 0;
        for (int blockY = 0; blockY < blockCountY; blockY++) {
            for (int blockX = 0; blockX < blockCountX; blockX++) {
                if (progressReceiver != null) {
                    progressReceiver.checkForCancellation();
                }
                final int originX = area.x + (blockX * BLOCK_SIZE);
                final int originY = area.y + (blockY * BLOCK_SIZE);
                final int width = Math.min(BLOCK_SIZE, (area.x + area.width) - originX);
                final int height = Math.min(BLOCK_SIZE, (area.y + area.height) - originY);
                columnsExamined += width * height;
                if (terrainMargin > 0) {
                    final BlockResult result = blendTerrainBlock(dimension, originX, originY, width, height, terrainMargin);
                    terrainChanged += result.changed;
                    usedGpu |= result.usedGpu;
                }
                if (heightMargin > 0) {
                    final BlockResult result = blendHeightBlock(dimension, originX, originY, width, height, heightMargin);
                    heightChanged += result.changed;
                    usedGpu |= result.usedGpu;
                }
                blocksDone++;
                if (progressReceiver != null) {
                    progressReceiver.setProgress((float) blocksDone / totalBlocks);
                }
            }
        }
        final BlendReport report = new BlendReport(columnsExamined, terrainChanged, heightChanged, usedGpu, System.nanoTime() - start);
        if (logger.isDebugEnabled()) {
            logger.debug("Blended {}", report);
        }
        return report;
    }

    private BlockResult blendTerrainBlock(Dimension dimension, int originX, int originY, int width, int height, int margin) {
        final int sourceWidth = width + (2 * margin), sourceHeight = height + (2 * margin);
        final int[] source = new int[sourceWidth * sourceHeight];
        final boolean[] present = new boolean[sourceWidth * sourceHeight];
        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                final Terrain terrain = dimension.getTerrainAt(originX - margin + x, originY - margin + y);
                if (terrain != null) {
                    source[(y * sourceWidth) + x] = terrain.ordinal();
                    present[(y * sourceWidth) + x] = true;
                } else {
                    // No tile here. The value is never read, because no column whose window includes it is blended
                    source[(y * sourceWidth) + x] = -1;
                }
            }
        }
        final boolean[] blendable = getFullyPresentColumns(present, sourceWidth, sourceHeight, width, height, margin);

        final int[] sampleIndices = new int[width * height];
        final boolean usedGpu = BlendKernel.blendTerrain(source, sampleIndices, width, height, margin, originX, originY,
                BlendAlgorithm.getEffectiveRadius(settings), settings.getTerrainScale(),
                (settings.getTerrainMode() == BlendMode.COMBINED) ? BlendAlgorithm.COMBINED_JITTER : 1f,
                settings.getTerrainMode().isCoherent(), settings.getTerrainMode().isStochastic(),
                settings.isBoundariesOnly(),
                dimension.getSeed() + settings.getSeedOffset() + BlendAlgorithm.WARP_X_SEED_OFFSET,
                dimension.getSeed() + settings.getSeedOffset() + BlendAlgorithm.WARP_Y_SEED_OFFSET,
                dimension.getSeed() + settings.getSeedOffset());
        if (! usedGpu) {
            BlendAlgorithm.blendTerrain(source, sampleIndices, width, height, margin, originX, originY, settings, dimension.getSeed());
        }

        final Terrain[] terrains = TERRAINS;
        int changed = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = (y * width) + x;
                if (! blendable[index]) {
                    continue;
                }
                final int sampleIndex = sampleIndices[index];
                final int ownIndex = ((y + margin) * sourceWidth) + x + margin;
                if (sampleIndex == ownIndex) {
                    continue;
                }
                final int ordinal = source[sampleIndex];
                if ((ordinal < 0) || (ordinal == source[ownIndex])) {
                    continue;
                }
                final int worldX = originX + x, worldY = originY + y;
                dimension.setTerrainAt(worldX, worldY, terrains[ordinal]);
                if (settings.isBlendWaterLevel()) {
                    final int sampleX = originX - margin + (sampleIndex % sourceWidth);
                    final int sampleY = originY - margin + (sampleIndex / sourceWidth);
                    final Tile sampleTile = dimension.getTile(sampleX >> TILE_SIZE_BITS, sampleY >> TILE_SIZE_BITS);
                    if (sampleTile != null) {
                        dimension.setWaterLevelAt(worldX, worldY,
                                sampleTile.getWaterLevel(sampleX & TILE_SIZE_MASK, sampleY & TILE_SIZE_MASK));
                    }
                }
                changed++;
            }
        }
        return new BlockResult(changed, usedGpu);
    }

    private BlockResult blendHeightBlock(Dimension dimension, int originX, int originY, int width, int height, int margin) {
        final int sourceWidth = width + (2 * margin), sourceHeight = height + (2 * margin);
        final float[] source = new float[sourceWidth * sourceHeight];
        final boolean[] present = new boolean[sourceWidth * sourceHeight];
        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                final int worldX = originX - margin + x, worldY = originY - margin + y;
                final Tile tile = dimension.getTile(worldX >> TILE_SIZE_BITS, worldY >> TILE_SIZE_BITS);
                if (tile != null) {
                    source[(y * sourceWidth) + x] = tile.getHeight(worldX & TILE_SIZE_MASK, worldY & TILE_SIZE_MASK);
                    present[(y * sourceWidth) + x] = true;
                }
            }
        }
        final boolean[] blendable = getFullyPresentColumns(present, sourceWidth, sourceHeight, width, height, margin);

        final float[] weights = BlendAlgorithm.createGaussianKernel(margin);
        final float[] blended = new float[width * height];
        final boolean usedGpu = BlendKernel.blendHeight(source, blended, width, height, margin, weights,
                settings.getHeightStrength(), settings.getHeightSlopeThreshold());
        if (! usedGpu) {
            BlendAlgorithm.blendHeight(source, blended, width, height, margin, weights, settings);
        }

        int changed = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = (y * width) + x;
                if (! blendable[index]) {
                    continue;
                }
                final float own = source[((y + margin) * sourceWidth) + x + margin];
                if (blended[index] != own) {
                    dimension.setHeightAt(originX + x, originY + y, blended[index]);
                    changed++;
                }
            }
        }
        return new BlockResult(changed, usedGpu);
    }

    /**
     * Work out which columns of the area have a complete margin around them, using a summed area table so that it
     * costs one pass over the source rather than one window scan per column.
     */
    private static boolean[] getFullyPresentColumns(boolean[] present, int sourceWidth, int sourceHeight,
                                                    int width, int height, int margin) {
        // totals[y][x] is the number of present columns in source strictly above and to the left of (x, y)
        final int[] totals = new int[(sourceWidth + 1) * (sourceHeight + 1)];
        for (int y = 0; y < sourceHeight; y++) {
            int rowTotal = 0;
            for (int x = 0; x < sourceWidth; x++) {
                rowTotal += present[(y * sourceWidth) + x] ? 1 : 0;
                totals[((y + 1) * (sourceWidth + 1)) + x + 1] = totals[(y * (sourceWidth + 1)) + x + 1] + rowTotal;
            }
        }
        final int windowWidth = (2 * margin) + 1, windowArea = windowWidth * windowWidth;
        final boolean[] blendable = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // The window of column (x, y) is the square of side windowWidth with its top left corner at (x, y)
                final int right = x + windowWidth, bottom = y + windowWidth;
                final int count = totals[(bottom * (sourceWidth + 1)) + right]
                        - totals[(y * (sourceWidth + 1)) + right]
                        - totals[(bottom * (sourceWidth + 1)) + x]
                        + totals[(y * (sourceWidth + 1)) + x];
                blendable[(y * width) + x] = (count == windowArea);
            }
        }
        return blendable;
    }

    private static int divideRoundingUp(int dividend, int divisor) {
        return ((dividend + divisor) - 1) / divisor;
    }

    /**
     * What one block of work came to.
     */
    private static final class BlockResult {
        BlockResult(int changed, boolean usedGpu) {
            this.changed = changed;
            this.usedGpu = usedGpu;
        }

        final int changed;
        final boolean usedGpu;
    }

    private final BlendSettings settings;

    /**
     * The side, in columns, of one block of work. Big enough that the margin is a small fraction of what is read, and
     * that a GPU has plenty to do; small enough that a block plus its margin is a modest allocation.
     */
    static final int BLOCK_SIZE = 512;

    private static final Terrain[] TERRAINS = Terrain.values();

    private static final Logger logger = LoggerFactory.getLogger(Blender.class);
}
