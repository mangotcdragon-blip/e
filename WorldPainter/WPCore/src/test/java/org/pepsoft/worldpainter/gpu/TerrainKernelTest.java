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

import org.junit.Test;
import org.pepsoft.minecraft.Material;
import org.pepsoft.util.PerlinNoise;
import org.pepsoft.worldpainter.Terrain;

import java.util.Random;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.Constants.SMALL_BLOBS;
import static org.pepsoft.worldpainter.Constants.TINY_BLOBS;

/**
 * Verifies that the terrain generation kernels place exactly the blocks the CPU implementations would.
 */
public class TerrainKernelTest {
    /**
     * Generate a chunk's worth of Stone Mix subsurface on the device and check every block against
     * {@link Terrain#STONE_MIX} itself.
     *
     * <p>The four layers from y = -4 to y = -1 are excluded, because there the Java implementation draws from a
     * {@link Random} shared between every thread and every chunk, and so has no defined value to compare against. The
     * kernel marks those blocks {@link StoneMixKernel#HOST} and the exporter generates them the old way.
     */
    @Test
    public void stoneMixMatchesTheCpu() {
        GpuTestSupport.requireDevice();

        final long seed = 0xC0FFEEL;
        final StoneMixKernel kernel = StoneMixKernel.get(seed);
        assertNotNull("no Stone Mix kernel although a device is available", kernel);

        final int lowestY = -64, highestY = 100, depth = highestY - lowestY + 1;
        final int columnCount = 16 * 16;
        final int[] columns = new int[columnCount * StoneMixKernel.COLUMN_STRIDE];
        int index = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                columns[index++] = 1024 + x;
                columns[index++] = -2048 + z;
                columns[index++] = lowestY;
                columns[index++] = highestY;
            }
        }

        final byte[] volume = kernel.generate(columns, columnCount, lowestY, depth, 0);
        assertNotNull(volume);
        assertEquals(columnCount * depth, volume.length);

        int compared = 0, hostBand = 0;
        for (int column = 0; column < columnCount; column++) {
            final int worldX = columns[column * StoneMixKernel.COLUMN_STRIDE];
            final int worldY = columns[column * StoneMixKernel.COLUMN_STRIDE + 1];
            for (int y = lowestY; y <= highestY; y++) {
                final byte paletteIndex = volume[(column * depth) + (y - lowestY)];
                if ((y >= -4) && (y < 0)) {
                    assertEquals("the non-deterministic band should be left to the host",
                            StoneMixKernel.HOST, paletteIndex);
                    hostBand++;
                    continue;
                }
                assertNotEquals("no material generated at " + worldX + ", " + worldY + ", " + y,
                        StoneMixKernel.NONE, paletteIndex);
                final Material expected = Terrain.STONE_MIX.getMaterial(null, seed, worldX, worldY, y, 64);
                assertEquals("wrong material at " + worldX + ", " + worldY + ", " + y,
                        expected, StoneMixKernel.PALETTE[paletteIndex & 0xff]);
                compared++;
            }
        }
        assertEquals(columnCount * 4, hostBand);
        assertEquals((columnCount * depth) - hostBand, compared);
    }

    /**
     * A column whose range is empty must come back entirely unfilled, and one whose range is a subset of the volume
     * must be filled only within that subset. The exporter relies on this to describe void and shallow columns.
     */
    @Test
    public void stoneMixRespectsColumnRanges() {
        GpuTestSupport.requireDevice();

        final StoneMixKernel kernel = StoneMixKernel.get(7L);
        assertNotNull(kernel);

        final int lowestY = 0, depth = 64, columnCount = 16 * 16;
        final int[] columns = new int[columnCount * StoneMixKernel.COLUMN_STRIDE];
        for (int column = 0; column < columnCount; column++) {
            final int base = column * StoneMixKernel.COLUMN_STRIDE;
            columns[base] = column;
            columns[base + 1] = column * 3;
            columns[base + 2] = lowestY;
            // Every third column is empty; the others stop part way up
            columns[base + 3] = ((column % 3) == 0) ? -1 : (column % depth);
        }

        final byte[] volume = kernel.generate(columns, columnCount, lowestY, depth, 0);
        assertNotNull(volume);
        for (int column = 0; column < columnCount; column++) {
            final int columnMaxY = columns[(column * StoneMixKernel.COLUMN_STRIDE) + 3];
            for (int y = lowestY; y < (lowestY + depth); y++) {
                final byte paletteIndex = volume[(column * depth) + (y - lowestY)];
                if (y <= columnMaxY) {
                    assertNotEquals("column " + column + " should be filled at " + y, StoneMixKernel.NONE, paletteIndex);
                } else {
                    assertEquals("column " + column + " should be empty at " + y, StoneMixKernel.NONE, paletteIndex);
                }
            }
        }
    }

    /**
     * Run the Resources kernel over a chunk and check it against the same decision made in Java. The reference here is
     * a transcription of the inner loop of {@code ResourcesExporter.render()}; the exporter passes the kernel its own
     * tables, so this checks the part that is actually different between the two paths.
     */
    @Test
    public void resourceLayerMatchesTheCpu() {
        GpuTestSupport.requireDevice();

        final long seed = 0x1234567L;
        final int materialCount = 12;
        final long[] seedOffsets = new long[materialCount];
        final int[] minLevels = new int[materialCount], maxLevels = new int[materialCount];
        final float[][] chances = new float[materialCount][ResourceLayerKernel.CHANCES_PER_MATERIAL];
        final boolean[] smallBlobScale = new boolean[materialCount];
        final Random random = new Random(42L);
        for (int i = 0; i < materialCount; i++) {
            seedOffsets[i] = random.nextInt(200000000);
            minLevels[i] = -64 + random.nextInt(16);
            maxLevels[i] = 16 + random.nextInt(80);
            smallBlobScale[i] = (i % 4) == 0;
            for (int value = 0; value < ResourceLayerKernel.CHANCES_PER_MATERIAL; value++) {
                chances[i][value] = PerlinNoise.getLevelForPromillage(Math.min(random.nextInt(40) * value / 8f, 1000f));
            }
        }

        final ResourceLayerKernel kernel = ResourceLayerKernel.get(seed, seedOffsets, minLevels, maxLevels, chances, smallBlobScale);
        assertNotNull("no Resources kernel although a device is available", kernel);

        final int lowestY = -64, highestY = 90, depth = highestY - lowestY + 1, columnCount = 16 * 16;
        final int[] columns = new int[columnCount * ResourceLayerKernel.COLUMN_STRIDE];
        int index = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                columns[index++] = -333 + x;
                columns[index++] = 777 + z;
                columns[index++] = lowestY;
                columns[index++] = highestY;
                columns[index++] = 1 + (((x * 16) + z) % 15);
            }
        }

        final byte[] volume = kernel.generate(columns, columnCount, lowestY, depth);
        assertNotNull(volume);

        final PerlinNoise[] noise = new PerlinNoise[materialCount];
        for (int i = 0; i < materialCount; i++) {
            noise[i] = new PerlinNoise(seed + seedOffsets[i]);
        }
        int placed = 0;
        for (int column = 0; column < columnCount; column++) {
            final int base = column * ResourceLayerKernel.COLUMN_STRIDE;
            final int worldX = columns[base], worldY = columns[base + 1], resourcesValue = columns[base + 4];
            final double dx = worldX / TINY_BLOBS, dy = worldY / TINY_BLOBS;
            final double dirtX = worldX / SMALL_BLOBS, dirtY = worldY / SMALL_BLOBS;
            for (int y = lowestY; y <= highestY; y++) {
                final double dz = y / TINY_BLOBS, dirtZ = y / SMALL_BLOBS;
                int expected = -1;
                for (int i = 0; i < materialCount; i++) {
                    final float chance = chances[i][resourcesValue];
                    if ((chance <= 0.5f) && (y >= minLevels[i]) && (y <= maxLevels[i])
                            && ((smallBlobScale[i] ? noise[i].getPerlinNoise(dirtX, dirtY, dirtZ) : noise[i].getPerlinNoise(dx, dy, dz)) >= chance)) {
                        expected = i;
                        break;
                    }
                }
                final byte actual = volume[(column * depth) + (y - lowestY)];
                if (expected < 0) {
                    assertEquals("expected nothing at " + worldX + ", " + worldY + ", " + y,
                            ResourceLayerKernel.NONE, actual);
                } else {
                    assertEquals("wrong material at " + worldX + ", " + worldY + ", " + y, expected, actual & 0xff);
                    placed++;
                }
            }
        }
        assertTrue("the test data should have placed at least some resources", placed > 0);
    }
}
