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

import org.junit.After;
import org.junit.Test;
import org.pepsoft.util.ProgressReceiver.OperationCancelled;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.TestData;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.gpu.GpuSettings;

import java.awt.Rectangle;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Tests blending a whole dimension: that it changes what it should, leaves alone what it should, and produces the
 * same map with or without a GPU.
 */
public class BlenderTest {
    @After
    public void resetGpuSettings() {
        GpuSettings.reset();
    }

    /**
     * The property that matters most: whether a map was blended on a GPU should not be something you can tell by
     * looking at the result.
     */
    @Test
    public void gpuAndCpuProduceTheSameMap() throws OperationCancelled {
        GpuSettings.setMode(GpuSettings.Mode.AUTO);
        GpuSettings.setDevicePreference(GpuSettings.DevicePreference.ANY_DEVICE);
        assumeTrue("No OpenCL device available; skipping", org.pepsoft.worldpainter.gpu.BlendKernel.isAvailable());

        final BlendSettings settings = BlendSettings.builder()
                .terrainMode(BlendMode.COMBINED)
                .terrainRadius(6)
                .heightRadius(3)
                .heightStrength(0.6f)
                .heightSlopeThreshold(0.5f)
                .build();

        final Dimension accelerated = createTestDimension();
        final BlendReport acceleratedReport = new Blender(settings).blend(accelerated, AREA, null);
        assertTrue("this test is meaningless if the blend did not run on the GPU", acceleratedReport.isUsedGpu());

        GpuSettings.setMode(GpuSettings.Mode.OFF);
        final Dimension unaccelerated = createTestDimension();
        final BlendReport unacceleratedReport = new Blender(settings).blend(unaccelerated, AREA, null);
        assertFalse(unacceleratedReport.isUsedGpu());

        assertEquals(unacceleratedReport.getTerrainChanged(), acceleratedReport.getTerrainChanged());
        assertEquals(unacceleratedReport.getHeightChanged(), acceleratedReport.getHeightChanged());
        assertTrue("the blend should have changed something", unacceleratedReport.getTerrainChanged() > 0);
        assertTrue("the blend should have changed some heights", unacceleratedReport.getHeightChanged() > 0);

        for (int x = AREA.x; x < (AREA.x + AREA.width); x++) {
            for (int y = AREA.y; y < (AREA.y + AREA.height); y++) {
                assertEquals("terrain differs at " + x + ", " + y,
                        unaccelerated.getTerrainAt(x, y), accelerated.getTerrainAt(x, y));
                assertEquals("height differs at " + x + ", " + y,
                        Float.floatToRawIntBits(unaccelerated.getHeightAt(x, y)),
                        Float.floatToRawIntBits(accelerated.getHeightAt(x, y)));
            }
        }
    }

    /**
     * Blending redistributes the terrain that is there; it never introduces a terrain from somewhere else.
     */
    @Test
    public void blendingIntroducesNoNewTerrains() throws OperationCancelled {
        GpuSettings.setMode(GpuSettings.Mode.OFF);
        final Dimension dimension = createTestDimension();
        final Set<Terrain> before = EnumSet.copyOf(dimension.getAllTerrains());

        new Blender(BlendSettings.builder().terrainMode(BlendMode.ORGANIC).terrainRadius(8).build())
                .blend(dimension, AREA, null);

        assertTrue("blending invented a terrain that was not on the map",
                before.containsAll(EnumSet.copyOf(dimension.getAllTerrains())));
    }

    /**
     * With boundariesOnly set, a column surrounded entirely by its own terrain has nothing to blend with and must be
     * left exactly as it was, however strong the blend.
     */
    @Test
    public void interiorsAreLeftAloneWhenBlendingBoundariesOnly() throws OperationCancelled {
        GpuSettings.setMode(GpuSettings.Mode.OFF);
        final Dimension dimension = createTestDimension();
        final BlendSettings settings = BlendSettings.builder()
                .terrainMode(BlendMode.SPECKLED)
                .terrainRadius(8)
                .boundariesOnly(true)
                .heightRadius(0)
                .build();

        new Blender(settings).blend(dimension, AREA, null);

        // The boundary in the test map runs down the middle; a long way from it, nothing may have moved
        for (int x = AREA.x + 8; x < (AREA.x + (AREA.width / 2)) - 16; x++) {
            for (int y = AREA.y + 8; y < ((AREA.y + AREA.height) - 8); y++) {
                assertEquals("terrain moved deep inside a uniform area at " + x + ", " + y,
                        Terrain.GRASS, dimension.getTerrainAt(x, y));
            }
        }
    }

    /**
     * A settings object that does nothing has to do nothing, rather than quietly rewriting every column with the same
     * value and marking the map as changed.
     */
    @Test
    public void noOpSettingsChangeNothing() throws OperationCancelled {
        GpuSettings.setMode(GpuSettings.Mode.OFF);
        final Dimension dimension = createTestDimension();
        final BlendSettings settings = BlendSettings.builder()
                .terrainMode(BlendMode.NONE)
                .heightRadius(0)
                .build();
        assertTrue(settings.isNoOp());

        final BlendReport report = new Blender(settings).blend(dimension, AREA, null);

        assertEquals(0, report.getTerrainChanged());
        assertEquals(0, report.getHeightChanged());
    }

    /**
     * Smoothing a terraced height map has to actually make it smoother.
     */
    @Test
    public void heightBlendingReducesSteps() throws OperationCancelled {
        GpuSettings.setMode(GpuSettings.Mode.OFF);
        final Dimension dimension = createTestDimension();
        final float roughnessBefore = getRoughness(dimension);

        new Blender(BlendSettings.builder()
                .terrainMode(BlendMode.NONE)
                .heightRadius(4)
                .heightStrength(1f)
                .heightSlopeThreshold(0f)
                .build()).blend(dimension, AREA, null);

        final float roughnessAfter = getRoughness(dimension);
        assertTrue("smoothing should have reduced the steepest step, but it went from " + roughnessBefore + " to "
                + roughnessAfter, roughnessAfter < (roughnessBefore * 0.5f));
    }

    /**
     * Blending the same map twice with the same settings has to give the same result, or nobody could reproduce a map
     * from its seed and its settings.
     */
    @Test
    public void blendingIsReproducible() throws OperationCancelled {
        GpuSettings.setMode(GpuSettings.Mode.OFF);
        final BlendSettings settings = BlendSettings.builder().terrainMode(BlendMode.COMBINED).terrainRadius(6).build();
        final Dimension first = createTestDimension(), second = createTestDimension();

        new Blender(settings).blend(first, AREA, null);
        new Blender(settings).blend(second, AREA, null);

        for (int x = AREA.x; x < (AREA.x + AREA.width); x++) {
            for (int y = AREA.y; y < (AREA.y + AREA.height); y++) {
                assertEquals(first.getTerrainAt(x, y), second.getTerrainAt(x, y));
            }
        }
    }

    /**
     * Blending with no area given has to blend the whole map. {@code Dimension.getExtent()} is in tile coordinates,
     * so taking it for a block rectangle would blend a single column and quietly do nothing.
     */
    @Test
    public void blendingWithoutAnAreaCoversTheWholeMap() throws OperationCancelled {
        GpuSettings.setMode(GpuSettings.Mode.OFF);
        final Dimension dimension = createTestDimension();

        final BlendReport report = new Blender(BlendSettings.builder().terrainMode(BlendMode.ORGANIC).terrainRadius(6).build())
                .blend(dimension, null);

        assertEquals(AREA.width * AREA.height, report.getColumnsExamined());
        assertTrue("blending the whole map should have changed something", report.getTerrainChanged() > 0);
    }

    /**
     * A dimension two tiles square: grass on the left, sand on the right, with a terraced height map so that there is
     * something for the height blend to smooth.
     */
    private Dimension createTestDimension() {
        final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 256, 256), 62);
        for (int x = 0; x < 256; x++) {
            for (int y = 0; y < 256; y++) {
                if (x >= 128) {
                    dimension.setTerrainAt(x, y, Terrain.SAND);
                }
                dimension.setHeightAt(x, y, 62f + ((x / 32) * 5f) + ((y / 48) * 3f));
            }
        }
        return dimension;
    }

    /**
     * The steepest step between horizontally adjacent columns, well inside the area so that the untouched border does
     * not count. The mean difference would not do: spreading a step over more columns leaves the total rise, and
     * therefore the mean, exactly where it was. It is the height of the steepest step that smoothing takes out.
     */
    private float getRoughness(Dimension dimension) {
        float steepest = 0f;
        for (int x = AREA.x + BORDER; x < ((AREA.x + AREA.width) - BORDER); x++) {
            for (int y = AREA.y + BORDER; y < ((AREA.y + AREA.height) - BORDER); y++) {
                steepest = Math.max(steepest, Math.abs(dimension.getHeightAt(x + 1, y) - dimension.getHeightAt(x, y)));
            }
        }
        return steepest;
    }

    private static final Rectangle AREA = new Rectangle(0, 0, 256, 256);

    /** How far inside the area to measure, to stay clear of the border blending deliberately leaves alone. */
    private static final int BORDER = 8;
}
