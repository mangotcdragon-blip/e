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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pepsoft.util.ProgressReceiver.OperationCancelled;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.TestData;
import org.pepsoft.worldpainter.Terrain;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.awt.Rectangle;

import static org.junit.Assert.*;

/**
 * Tests the plain text exports: that they are written, that they are well formed, and that what they say about the
 * map is true.
 *
 * <p>The point of these formats is that other programs read them, so "well formed" is checked properly rather than
 * assumed: the OBJ is parsed back and every face index and material reference verified, and the JSON is parsed
 * against a strict reader.
 */
public class ReadableExporterTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesEveryFormat() throws IOException, OperationCancelled {
        final Dimension dimension = createTestDimension();
        final File directory = temporaryFolder.newFolder("all");

        final List<File> written = new ReadableExporter(settings().build())
                .exportAll(dimension, directory, "My Test Map!", null);

        assertEquals(5, written.size());
        for (File file: written) {
            assertTrue(file + " was not written", file.isFile());
            assertTrue(file + " is empty", file.length() > 0);
            assertTrue("the base name should have been made safe for a file system: " + file.getName(),
                    file.getName().startsWith("My_Test_Map_"));
        }
    }

    @Test
    public void objMeshIsWellFormed() throws IOException, OperationCancelled {
        final Dimension dimension = createTestDimension();
        final File directory = temporaryFolder.newFolder("obj");
        final File objFile = new File(directory, "map.obj");

        final WavefrontObjExporter.ObjExportResult result =
                new WavefrontObjExporter(settings().build()).export(dimension, objFile, null);

        final ObjModel model = ObjModel.parse(objFile);
        assertEquals(result.getVertices(), model.vertexCount);
        assertEquals(result.getFaces(), model.faces.size());
        assertTrue("the mesh should have faces", model.faces.size() > 0);
        for (int[] face: model.faces) {
            for (int index: face) {
                assertTrue("face refers to vertex " + index + ", but there are only " + model.vertexCount,
                        (index >= 1) && (index <= model.vertexCount));
            }
        }
        assertEquals("every face should have a material", 0, model.facesWithoutMaterial);

        // The material library has to exist and to define everything the mesh refers to
        assertNotNull("the mesh should point at a material library", model.materialLibrary);
        final File materialFile = new File(directory, model.materialLibrary);
        assertTrue("the material library was not written", materialFile.isFile());
        final Set<String> defined = readMaterialNames(materialFile);
        for (String used: model.materialsUsed) {
            assertTrue("the mesh uses material \"" + used + "\", which the library does not define",
                    defined.contains(used));
        }
    }

    /**
     * A flat map has to come out as a flat mesh at the right height, with every face pointing up.
     */
    @Test
    public void flatMapProducesFlatMeshAtTheRightHeight() throws IOException, OperationCancelled {
        final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 128, 128), TERRAIN_HEIGHT);
        final File objFile = new File(temporaryFolder.newFolder("flat"), "flat.obj");

        new WavefrontObjExporter(settings().includeWater(false).build()).export(dimension, objFile, null);

        final ObjModel model = ObjModel.parse(objFile);
        for (float[] vertex: model.vertices) {
            assertEquals("every vertex of a flat map should be at the terrain height", TERRAIN_HEIGHT, vertex[1], 0.01f);
        }
        for (int[] face: model.faces) {
            assertEquals("faces should be quads", 4, face.length);
            assertTrue("faces should be wound so that they point up, not down", pointsUp(model, face));
        }
    }

    /**
     * The vertical exaggeration has to actually scale the mesh, and nothing else.
     */
    @Test
    public void verticalExaggerationScalesTheMesh() throws IOException, OperationCancelled {
        final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 128, 128), TERRAIN_HEIGHT);
        final File objFile = new File(temporaryFolder.newFolder("scaled"), "scaled.obj");

        new WavefrontObjExporter(settings().verticalExaggeration(3f).includeWater(false).build())
                .export(dimension, objFile, null);

        final ObjModel model = ObjModel.parse(objFile);
        for (float[] vertex: model.vertices) {
            assertEquals(TERRAIN_HEIGHT * 3f, vertex[1], 0.01f);
        }
    }

    /**
     * The blocky mesh has to put every top face at its column's exact height, rather than at an average.
     */
    @Test
    public void blockyMeshPreservesExactHeights() throws IOException, OperationCancelled {
        final Dimension dimension = createTestDimension();
        final File objFile = new File(temporaryFolder.newFolder("blocky"), "blocky.obj");

        new WavefrontObjExporter(settings()
                .geometry(ReadableExportSettings.Geometry.BLOCKY)
                .includeWater(false)
                .includeNormals(false)
                .build()).export(dimension, objFile, null);

        final ObjModel model = ObjModel.parse(objFile);
        final float[] meshHeights = new float[model.vertices.size()];
        for (int i = 0; i < meshHeights.length; i++) {
            meshHeights[i] = model.vertices.get(i)[1];
        }
        Arrays.sort(meshHeights);
        // Every sampled column's exact height has to appear in the mesh, to within the three decimals the file is
        // written with. A smooth mesh would have averaged the neighbours instead, and would fail this.
        for (int x = 0; x < 128; x += 8) {
            for (int y = 0; y < 128; y += 8) {
                assertTrue("the mesh has no vertex at the height of the column at " + x + ", " + y
                                + " (" + dimension.getHeightAt(x, y) + ')',
                        containsApproximately(meshHeights, dimension.getHeightAt(x, y)));
            }
        }
    }

    @Test
    public void summaryIsValidJsonAndDescribesTheMap() throws IOException, OperationCancelled {
        final Dimension dimension = createTestDimension();
        final File file = new File(temporaryFolder.newFolder("json"), "summary.json");
        final SurfaceGrid grid = SurfaceGrid.sample(dimension, null, 1, null);

        new WorldSummaryExporter(settings().build()).export(dimension, grid, file);

        final String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        MiniJson.parse(json);

        assertTrue(json.contains("\"terrain\""));
        assertTrue(json.contains("\"asciiMap\""));
        assertTrue(json.contains("\"grid\""));
        assertTrue("the summary should name the terrains on the map", json.contains(Terrain.SAND.name()));
        assertTrue("the summary should name the terrains on the map", json.contains(Terrain.GRASS.name()));
        assertTrue("the summary should report the seed", json.contains(Long.toString(dimension.getSeed())));
    }

    @Test
    public void asciiMapMatchesTheGrid() throws IOException, OperationCancelled {
        final Dimension dimension = createTestDimension();
        final SurfaceGrid grid = SurfaceGrid.sample(dimension, null, 2, null);

        final AsciiMapExporter.AsciiMap map = new AsciiMapExporter().render(grid);

        assertEquals(grid.getRows(), map.getRows().size());
        for (String row: map.getRows()) {
            assertEquals(grid.getColumns(), row.length());
        }
        // Every character drawn has to be in the legend
        final Set<Character> legend = map.getLegend().keySet();
        for (String row: map.getRows()) {
            for (char c: row.toCharArray()) {
                assertTrue("the legend does not explain '" + c + '\'', legend.contains(c));
            }
        }
        // The left half of the test map is grass and the right half sand, so the two halves have to differ
        final String middleRow = map.getRows().get(map.getRows().size() / 2);
        assertNotEquals("the two halves of the map should be drawn differently",
                middleRow.charAt(middleRow.length() / 4), middleRow.charAt((middleRow.length() * 3) / 4));
    }

    @Test
    public void csvHasARowPerColumnOfTheMap() throws IOException, OperationCancelled {
        final Dimension dimension = createTestDimension();
        final File file = new File(temporaryFolder.newFolder("csv"), "grid.csv");
        final SurfaceGrid grid = SurfaceGrid.sample(dimension, null, 4, null);

        final int rowsWritten = new CsvGridExporter().export(grid, file);

        final List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals("x,z,height,water_level,under_water,terrain", lines.get(0));
        assertEquals(rowsWritten, lines.size() - 1);
        assertEquals(grid.getPresentCount(), rowsWritten);
        for (String line: lines.subList(1, lines.size())) {
            assertEquals("every row should have six fields: " + line, 6, line.split(",", -1).length);
        }
        // Spot check the first data row against the map itself
        final String[] first = lines.get(1).split(",");
        final int x = Integer.parseInt(first[0]), z = Integer.parseInt(first[1]);
        assertEquals(dimension.getHeightAt(x, z), Float.parseFloat(first[2]), 0.001f);
        assertEquals(dimension.getTerrainAt(x, z).getName(), first[5]);
    }

    @Test
    public void samplingIntervalReducesTheGrid() throws OperationCancelled {
        final Dimension dimension = createTestDimension();

        final SurfaceGrid fine = SurfaceGrid.sample(dimension, null, 1, null);
        final SurfaceGrid coarse = SurfaceGrid.sample(dimension, null, 8, null);

        assertEquals(divideRoundingUp(fine.getColumns(), 8), coarse.getColumns());
        assertEquals(divideRoundingUp(fine.getRows(), 8), coarse.getRows());
        // The coarse grid's samples have to be the same columns the fine grid has, not something interpolated
        for (int row = 0; row < coarse.getRows(); row++) {
            for (int column = 0; column < coarse.getColumns(); column++) {
                assertEquals(fine.getHeight(column * 8, row * 8), coarse.getHeight(column, row), 0f);
                assertEquals(fine.getTerrain(column * 8, row * 8), coarse.getTerrain(column, row));
            }
        }
    }

    /**
     * With no area given, the whole map has to be exported. {@code Dimension.getExtent()} is in tile coordinates, so
     * taking it for a block rectangle silently exports a one block map; this is the guard against that.
     */
    @Test
    public void samplingWithoutAnAreaCoversTheWholeMap() throws OperationCancelled {
        final Dimension dimension = createTestDimension();

        final SurfaceGrid grid = SurfaceGrid.sample(dimension, null, 1, null);

        assertEquals(128, grid.getColumns());
        assertEquals(128, grid.getRows());
        assertEquals(128 * 128, grid.getPresentCount());
        assertEquals(Terrain.SAND, grid.getTerrain(100, 64));
        assertEquals(Terrain.GRASS, grid.getTerrain(20, 64));
    }

    @Test
    public void onlyTheRequestedFormatsAreWritten() throws IOException, OperationCancelled {
        final Dimension dimension = createTestDimension();
        final File directory = temporaryFolder.newFolder("some");

        final List<File> written = new ReadableExporter(settings().build())
                .export(dimension, directory, "map", EnumSet.of(ReadableExporter.Format.CSV), null);

        assertEquals(1, written.size());
        assertEquals("map.csv", written.get(0).getName());
        assertEquals(1, directory.listFiles().length);
    }

    /**
     * A dimension with two terrains, a hill, and a lake, so that there is something to say about it.
     */
    private Dimension createTestDimension() {
        final Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 128, 128), TERRAIN_HEIGHT);
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                if (x >= 64) {
                    dimension.setTerrainAt(x, y, Terrain.SAND);
                }
                final double distance = Math.hypot(x - 32, y - 32);
                if (distance < 24) {
                    dimension.setHeightAt(x, y, TERRAIN_HEIGHT + (float) (24 - distance));
                } else if (Math.hypot(x - 96, y - 96) < 20) {
                    dimension.setHeightAt(x, y, TERRAIN_HEIGHT - 6f);
                    dimension.setWaterLevelAt(x, y, TERRAIN_HEIGHT - 1);
                }
            }
        }
        return dimension;
    }

    private ReadableExportSettings.Builder settings() {
        return ReadableExportSettings.builder().sampleInterval(2);
    }

    /**
     * Whether a face's vertices are wound so that its normal points up.
     */
    private boolean pointsUp(ObjModel model, int[] face) {
        final float[] a = model.vertices.get(face[0] - 1);
        final float[] b = model.vertices.get(face[1] - 1);
        final float[] c = model.vertices.get(face[2] - 1);
        final float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        final float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        // The y component of the cross product of the two edges
        return ((uz * vx) - (ux * vz)) > 0f;
    }

    private Set<String> readMaterialNames(File materialFile) throws IOException {
        final Set<String> names = new HashSet<>();
        for (String line: Files.readAllLines(materialFile.toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("newmtl ")) {
                names.add(line.substring(7).trim());
            }
        }
        return names;
    }

    /**
     * Whether a sorted array contains a value, to within the precision the OBJ file is written with.
     */
    private static boolean containsApproximately(float[] sorted, float value) {
        int index = Arrays.binarySearch(sorted, value);
        if (index >= 0) {
            return true;
        }
        index = -index - 1;
        return ((index < sorted.length) && (Math.abs(sorted[index] - value) < TOLERANCE))
                || ((index > 0) && (Math.abs(sorted[index - 1] - value) < TOLERANCE));
    }

    private static int divideRoundingUp(int dividend, int divisor) {
        return ((dividend + divisor) - 1) / divisor;
    }

    /**
     * As much of an OBJ file as these tests need to check.
     */
    private static final class ObjModel {
        static ObjModel parse(File file) throws IOException {
            final ObjModel model = new ObjModel();
            String currentMaterial = null;
            for (String line: Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                final String[] fields = trimmed.split("\\s+");
                switch (fields[0]) {
                    case "v":
                        assertEquals("a vertex should have three coordinates: " + line, 4, fields.length);
                        model.vertices.add(new float[] {
                                Float.parseFloat(fields[1]), Float.parseFloat(fields[2]), Float.parseFloat(fields[3]) });
                        model.vertexCount++;
                        break;
                    case "vn":
                        model.normalCount++;
                        break;
                    case "f":
                        final int[] face = new int[fields.length - 1];
                        for (int i = 1; i < fields.length; i++) {
                            // A face vertex is index, index/texture, or index//normal
                            face[i - 1] = Integer.parseInt(fields[i].split("/")[0]);
                        }
                        model.faces.add(face);
                        if (currentMaterial == null) {
                            model.facesWithoutMaterial++;
                        }
                        break;
                    case "usemtl":
                        currentMaterial = fields[1];
                        model.materialsUsed.add(currentMaterial);
                        break;
                    case "mtllib":
                        model.materialLibrary = fields[1];
                        break;
                    case "g":
                    case "o":
                        break;
                    default:
                        fail("unexpected statement in the OBJ file: " + line);
                }
            }
            return model;
        }

        final List<float[]> vertices = new ArrayList<>();
        final List<int[]> faces = new ArrayList<>();
        final Set<String> materialsUsed = new HashSet<>();
        String materialLibrary;
        int vertexCount, normalCount, facesWithoutMaterial;
    }

    private static final int TERRAIN_HEIGHT = 62;

    /** The OBJ file is written with three decimals, so heights read back from it are good to about that. */
    private static final float TOLERANCE = 0.002f;
}
