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
package org.pepsoft.worldpainter.cli;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pepsoft.worldpainter.exporting.readable.MiniJson;
import org.pepsoft.worldpainter.exporting.readable.ReadableExporter;

import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;

import static org.junit.Assert.*;

/**
 * Runs the command line against the regression test world, which is a real map with three dimensions, several
 * terrains, custom terrains and some water in it.
 */
public class WorldPainterCliTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @BeforeClass
    public static void extractTestWorld() throws IOException {
        worldFile = File.createTempFile("worldpainter-cli-test", ".world");
        worldFile.deleteOnExit();
        try (InputStream in = WorldPainterCliTest.class.getResourceAsStream(TEST_WORLD);
             OutputStream out = Files.newOutputStream(worldFile.toPath())) {
            assertNotNull("the regression test world is missing from the test resources", in);
            in.transferTo(out);
        }
    }

    @Test
    public void noArgumentsPrintsTheUsageAndFails() {
        final Result result = run();

        assertEquals(1, result.exitCode);
        assertTrue(result.out.contains("Usage: worldpainter"));
        assertTrue(result.out.contains("blend"));
    }

    @Test
    public void helpPrintsTheUsageAndSucceeds() {
        final Result result = run("help");

        assertEquals(0, result.exitCode);
        assertTrue(result.out.contains("Usage: worldpainter"));
    }

    @Test
    public void anUnknownCommandIsRejected() {
        final Result result = run("frobnicate");

        assertEquals(2, result.exitCode);
        assertTrue(result.err.contains("Unknown command"));
    }

    @Test
    public void aMissingFileIsReportedRatherThanThrown() {
        final Result result = run("info", "/no/such/file.world");

        assertEquals(1, result.exitCode);
        assertTrue(result.err.contains("No such file"));
    }

    @Test
    public void infoDescribesEveryDimension() {
        final Result result = run("info", worldFile.getPath());

        assertEquals(result.err, 0, result.exitCode);
        assertTrue(result.out.contains("Dimensions: 3"));
        assertTrue(result.out.contains("Surface"));
        assertTrue(result.out.contains("Nether"));
        assertTrue(result.out.contains("End"));
        assertTrue("info should say how big the map is", result.out.contains("640 x 640 blocks"));
        assertTrue("info should list the terrains", result.out.contains("Grass"));
    }

    @Test
    public void describeWritesValidJsonToStandardOutput() {
        final Result result = run("describe", worldFile.getPath(), "--interval", "8");

        assertEquals(result.err, 0, result.exitCode);
        MiniJson.parse(result.out);
        assertTrue(result.out.contains("\"asciiMap\""));
    }

    @Test
    public void exportWritesTheRequestedFormats() throws IOException {
        final File directory = temporaryFolder.newFolder("export");

        final Result result = run("export", worldFile.getPath(),
                "--directory", directory.getPath(), "--name", "map", "--interval", "8", "--formats", "obj,csv");

        assertEquals(result.err, 0, result.exitCode);
        assertTrue(new File(directory, "map.obj").isFile());
        assertTrue(new File(directory, "map.mtl").isFile());
        assertTrue(new File(directory, "map.csv").isFile());
        assertFalse("only the requested formats should be written", new File(directory, "map.json").isFile());
    }

    @Test
    public void exportHonoursTheRequestedArea() throws IOException {
        final File directory = temporaryFolder.newFolder("area");

        final Result result = run("export", worldFile.getPath(), "--directory", directory.getPath(),
                "--name", "part", "--formats", "csv", "--area", "0,0,64,64");

        assertEquals(result.err, 0, result.exitCode);
        // One header row plus one row per column of a 64 x 64 area
        assertEquals((64 * 64) + 1, Files.readAllLines(new File(directory, "part.csv").toPath(), StandardCharsets.UTF_8).size());
    }

    @Test
    public void blendWritesAWorldThatCanBeReadBack() throws IOException {
        final File output = new File(temporaryFolder.newFolder("blend"), "blended.world");

        final Result result = run("blend", worldFile.getPath(), "--output", output.getPath(),
                "--mode", "organic", "--radius", "8", "--height-radius", "0", "--gpu", "off");

        assertEquals(result.err, 0, result.exitCode);
        assertTrue(output.isFile());
        assertTrue("the blended world should be a plausible size", output.length() > 100000);
        assertTrue("blending should have changed something on the surface", result.out.contains("terrain and"));

        // The result has to load, and to still describe itself the same way
        final Result info = run("info", output.getPath());
        assertEquals(info.err, 0, info.exitCode);
        assertTrue(info.out.contains("Dimensions: 3"));
    }

    @Test
    public void blendNeedsAnOutputFile() {
        final Result result = run("blend", worldFile.getPath());

        assertEquals(2, result.exitCode);
        assertTrue(result.err.contains("--output"));
    }

    @Test
    public void settingsThatWouldDoNothingAreRejectedRatherThanRewritingTheMap() {
        final Result result = run("blend", worldFile.getPath(), "--output", "/tmp/never-written.world",
                "--mode", "none", "--height-radius", "0");

        assertEquals(2, result.exitCode);
        assertTrue(result.err.contains("would not change anything"));
        assertFalse(new File("/tmp/never-written.world").exists());
    }

    @Test
    public void aMistypedOptionIsReported() {
        final Result result = run("info", worldFile.getPath(), "--dimensino", "Surface");

        assertEquals(result.err, 0, result.exitCode);
        assertTrue("a typo in an option name should not pass silently", result.err.contains("dimensino"));
    }

    @Test
    public void anUnknownDimensionIsReported() {
        final Result result = run("info", worldFile.getPath(), "--dimension", "Atlantis");

        // info describes every dimension, so --dimension is not one of its options; describe is the one that uses it
        final Result describe = run("describe", worldFile.getPath(), "--dimension", "Atlantis");
        assertEquals(1, describe.exitCode);
        assertTrue(describe.err.contains("Atlantis"));
        assertTrue("the error should say what the world does have", describe.err.contains("Surface"));
        assertEquals(0, result.exitCode);
    }

    @Test
    public void gpuReportsWhatThereIs() {
        final Result result = run("gpu", "--gpu-preference", "any-device");

        assertEquals(result.err, 0, result.exitCode);
        assertTrue(result.out.contains("OpenCL devices") || result.out.contains("No OpenCL devices found"));
    }

    @Test
    public void formatNamesAreParsedAndAliased() {
        assertEquals(EnumSet.allOf(ReadableExporter.Format.class), WorldPainterCli.parseFormats(null));
        assertEquals(EnumSet.allOf(ReadableExporter.Format.class), WorldPainterCli.parseFormats("all"));
        assertEquals(EnumSet.of(ReadableExporter.Format.OBJ), WorldPainterCli.parseFormats("obj"));
        assertEquals(EnumSet.of(ReadableExporter.Format.OBJ), WorldPainterCli.parseFormats("mesh"));
        assertEquals(EnumSet.of(ReadableExporter.Format.ASCII, ReadableExporter.Format.CSV),
                WorldPainterCli.parseFormats("txt,csv"));
        try {
            WorldPainterCli.parseFormats("pdf");
            fail("an unknown format should be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("pdf"));
        }
    }

    @Test
    public void areasAreParsed() {
        assertEquals(new Rectangle(-100, 200, 64, 128), WorldPainterCli.parseArea("-100,200,64,128"));
        assertEquals(new Rectangle(0, 0, 1, 1), WorldPainterCli.parseArea("0, 0, 1, 1"));
        try {
            WorldPainterCli.parseArea("0,0,64");
            fail("an area needs four numbers");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("x,z,width,length"));
        }
    }

    private Result run(String... args) {
        final StringWriter out = new StringWriter(), err = new StringWriter();
        final int exitCode;
        try (PrintWriter outWriter = new PrintWriter(out); PrintWriter errWriter = new PrintWriter(err)) {
            exitCode = WorldPainterCli.run(args, outWriter, errWriter);
        }
        return new Result(exitCode, out.toString(), err.toString());
    }

    private static final class Result {
        Result(int exitCode, String out, String err) {
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
        }

        final int exitCode;
        final String out, err;
    }

    private static File worldFile;

    private static final String TEST_WORLD = "/testset/test-v2.3.6-1.world";
}
