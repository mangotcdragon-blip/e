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

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.ChunkFactory;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.TestData;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.WPContext;
import org.pepsoft.worldpainter.exporting.WorldPainterChunkFactory;
import org.pepsoft.worldpainter.plugins.WPPluginManager;

import java.awt.Rectangle;

import static java.util.Collections.emptyMap;
import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.DefaultPlugin.JAVA_ANVIL_1_18;

/**
 * Checks that generating a chunk's Stone Mix subsurface on the GPU produces the same chunk as generating it on the
 * CPU.
 *
 * <p>The four blocks between y = -4 and y = -1 are excluded. There the CPU implementation decides between stone and
 * deepslate by drawing from a {@link java.util.Random} shared by every chunk and every export thread, so it does not
 * produce the same answer twice even without a GPU involved. Those layers are generated on the CPU in both cases; see
 * {@link StoneMixKernel}.
 */
public class SubsurfaceAccelerationTest {
    @BeforeClass
    public static void initialisePlugins() {
        WPPluginManager.initialise(null, WPContext.INSTANCE);
    }

    @After
    public void resetGpuSettings() {
        GpuSettings.reset();
    }

    @Test
    public void acceleratedChunkIsIdenticalToUnacceleratedChunk() {
        GpuTestSupport.requireDevice();

        final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 128, 128), TERRAIN_HEIGHT);
        assertEquals("this test is about the Stone Mix subsurface", Terrain.STONE_MIX, dimension.getSubsurfaceMaterial());

        GpuSettings.setMode(GpuSettings.Mode.AUTO);
        GpuSettings.setDevicePreference(GpuSettings.DevicePreference.ANY_DEVICE);
        final Chunk accelerated = createChunk(dimension);

        GpuSettings.setMode(GpuSettings.Mode.OFF);
        final Chunk unaccelerated = createChunk(dimension);

        int differences = 0, stoneMixBlocks = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = dimension.getMinHeight(); y < dimension.getMaxHeight(); y++) {
                    if ((y >= -4) && (y < 0)) {
                        // Not deterministic on the CPU either; see the class comment
                        continue;
                    }
                    final Material expected = unaccelerated.getMaterial(x, y, z);
                    if (isStoneMix(expected)) {
                        stoneMixBlocks++;
                    }
                    if (! expected.equals(accelerated.getMaterial(x, y, z))) {
                        if (differences < 10) {
                            System.err.println("Difference at " + x + ", " + y + ", " + z + ": expected " + expected
                                    + " but the accelerated export produced " + accelerated.getMaterial(x, y, z));
                        }
                        differences++;
                    }
                }
            }
        }
        assertTrue("the chunk should be mostly Stone Mix", stoneMixBlocks > 10000);
        assertEquals("the accelerated export placed different blocks", 0, differences);
    }

    private Chunk createChunk(Dimension dimension) {
        final WorldPainterChunkFactory factory = new WorldPainterChunkFactory(dimension, emptyMap(), JAVA_ANVIL_1_18, dimension.getMaxHeight());
        final ChunkFactory.ChunkCreationResult result = factory.createChunk(0, 0);
        assertNotNull(result);
        return result.chunk;
    }

    private static boolean isStoneMix(Material material) {
        for (Material candidate: StoneMixKernel.PALETTE) {
            if (candidate.equals(material)) {
                return true;
            }
        }
        return false;
    }

    private static final int TERRAIN_HEIGHT = 62;
}
