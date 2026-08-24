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
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.layers.exporters.ExporterSettings;
import org.pepsoft.worldpainter.layers.exporters.ResourcesExporter;
import org.pepsoft.worldpainter.layers.exporters.ResourcesExporter.ResourcesExporterSettings;
import org.pepsoft.worldpainter.plugins.BlockBasedPlatformProvider;
import org.pepsoft.worldpainter.plugins.PlatformManager;

import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.pepsoft.minecraft.Material.DEEPSLATE_Y;
import static org.pepsoft.minecraft.Material.STONE;
import static org.pepsoft.worldpainter.DefaultPlugin.JAVA_ANVIL_1_18;

/**
 * Checks that the accelerated Resources layer produces the same chunk as the unaccelerated one.
 *
 * <p>{@link TerrainKernelTest} checks the kernel itself; this checks the code around it in
 * {@link ResourcesExporter}: the way a chunk is described to the device as 256 columns, and the way the answers are
 * turned back into blocks. A mistake there (a transposed coordinate, an off by one in a column's range) would not show
 * up in a kernel test but would quietly corrupt every exported map.
 */
public class ResourcesExporterAccelerationTest {
    @BeforeClass
    public static void initialisePlugins() {
        TestPlugins.ensureInitialised();
    }

    @After
    public void resetGpuSettings() {
        GpuSettings.reset();
    }

    @Test
    public void acceleratedChunkIsIdenticalToUnacceleratedChunk() {
        GpuTestSupport.requireDevice();

        final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 128, 128), TERRAIN_HEIGHT);
        final Tile tile = dimension.getTile(0, 0);
        // Vary the layer across the tile so that the per-column chance lookup is exercised, and leave part of it at
        // zero so that the columns the exporter has to skip are exercised too
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                tile.setLayerValue(Resources.INSTANCE, x, y, ((x + y) % 5 == 0) ? 0 : (1 + ((x * 7 + y) % 15)));
            }
        }

        final Chunk accelerated = createChunk();
        final Chunk unaccelerated = createChunk();

        // The default settings pick a random seed offset per material every time they are created, so both exporters
        // have to be given the same settings object; a real export gets them from the layer settings of the dimension
        final ExporterSettings settings = ResourcesExporterSettings.defaultSettings(JAVA_ANVIL_1_18,
                dimension.getAnchor(), MIN_HEIGHT, MAX_HEIGHT);

        GpuSettings.setMode(GpuSettings.Mode.AUTO);
        GpuSettings.setDevicePreference(GpuSettings.DevicePreference.ANY_DEVICE);
        final long dispatchesBefore = ResourceLayerKernel.getDispatchCount();
        final ResourcesExporter gpuExporter = new ResourcesExporter(dimension, JAVA_ANVIL_1_18, settings);
        gpuExporter.render(tile, accelerated);
        assertTrue("this test is meaningless unless the chunk actually went to the GPU",
                ResourceLayerKernel.getDispatchCount() > dispatchesBefore);

        GpuSettings.setMode(GpuSettings.Mode.OFF);
        final ResourcesExporter cpuExporter = new ResourcesExporter(dimension, JAVA_ANVIL_1_18, settings);
        cpuExporter.render(tile, unaccelerated);

        int differences = 0, resourcesPlaced = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = MIN_HEIGHT; y < MAX_HEIGHT; y++) {
                    final Material expected = unaccelerated.getMaterial(x, y, z);
                    if (expected != baseMaterial(y)) {
                        resourcesPlaced++;
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
        assertTrue("the test set-up should have placed some resources", resourcesPlaced > 0);
        assertEquals("the accelerated export placed different blocks", 0, differences);
    }

    /**
     * A chunk pre-filled the way the first pass would leave it, so that the deepslate ore substitution is exercised.
     */
    private Chunk createChunk() {
        final BlockBasedPlatformProvider provider = (BlockBasedPlatformProvider) PlatformManager.getInstance().getPlatformProvider(JAVA_ANVIL_1_18);
        final Chunk chunk = provider.createChunk(JAVA_ANVIL_1_18, 0, 0, MIN_HEIGHT, MAX_HEIGHT);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = MIN_HEIGHT; y <= TERRAIN_HEIGHT; y++) {
                    chunk.setMaterial(x, y, z, baseMaterial(y));
                }
            }
        }
        return chunk;
    }

    private static Material baseMaterial(int y) {
        if (y > TERRAIN_HEIGHT) {
            return Material.AIR;
        }
        return (y < 0) ? DEEPSLATE_Y : STONE;
    }

    private static final int MIN_HEIGHT = -64, MAX_HEIGHT = 320, TERRAIN_HEIGHT = 62;
}
