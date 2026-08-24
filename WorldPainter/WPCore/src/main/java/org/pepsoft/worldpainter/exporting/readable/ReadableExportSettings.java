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

import org.pepsoft.worldpainter.ColourScheme;

import java.awt.Rectangle;

/**
 * What to put in a readable export, and at what detail.
 *
 * <p>Sensible for a whole map by default: the full extent, one sample per block for the mesh, and a summary sampled
 * coarsely enough to stay readable.
 */
public final class ReadableExportSettings {
    private ReadableExportSettings(Builder builder) {
        area = (builder.area != null) ? new Rectangle(builder.area) : null;
        sampleInterval = builder.sampleInterval;
        geometry = builder.geometry;
        verticalExaggeration = builder.verticalExaggeration;
        includeWater = builder.includeWater;
        includeNormals = builder.includeNormals;
        centreOnOrigin = builder.centreOnOrigin;
        summaryColumns = builder.summaryColumns;
        includeGrids = builder.includeGrids;
        colourScheme = builder.colourScheme;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The area to export, or {@code null} for the whole extent of the dimension.
     */
    public Rectangle getArea() {
        return (area != null) ? new Rectangle(area) : null;
    }

    /**
     * Export every n'th column. One is every block; larger values make a coarser mesh and a much smaller file.
     */
    public int getSampleInterval() {
        return sampleInterval;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    /**
     * Multiplies the heights, to make a landscape's relief easier to see. One is true scale.
     */
    public float getVerticalExaggeration() {
        return verticalExaggeration;
    }

    /** Whether to include a surface for the water, as a second mesh. */
    public boolean isIncludeWater() {
        return includeWater;
    }

    /** Whether to write vertex normals, which most renderers use to shade a smooth mesh properly. */
    public boolean isIncludeNormals() {
        return includeNormals;
    }

    /**
     * Whether to move the mesh so that the middle of the exported area sits at the origin. Most 3D software is much
     * happier with coordinates near zero than with a map that starts at x = 100000.
     */
    public boolean isCentreOnOrigin() {
        return centreOnOrigin;
    }

    /**
     * How many columns wide the ASCII map and the sampled grids in the summary should be at most. The sample interval
     * for those is worked out from this, so that a summary of a huge map is still something a person can read and
     * something that fits in a language model's context.
     */
    public int getSummaryColumns() {
        return summaryColumns;
    }

    /**
     * Whether the summary should include the full sampled height and terrain grids as well as the statistics.
     */
    public boolean isIncludeGrids() {
        return includeGrids;
    }

    /** The colour scheme to take material colours from. */
    public ColourScheme getColourScheme() {
        return colourScheme;
    }

    /**
     * How the surface is turned into polygons.
     */
    public enum Geometry {
        /**
         * One quad per column, with the corners shared between neighbours and placed at the average of the heights
         * around them. Produces a continuous landscape and the smallest files.
         */
        SMOOTH,

        /**
         * One flat quad per column at exactly that column's height, plus vertical walls wherever a neighbour is
         * lower. Preserves the blocky look, and every column's height exactly, at the cost of a much larger file.
         */
        BLOCKY
    }

    public static final class Builder {
        public Builder area(Rectangle area) {
            this.area = area;
            return this;
        }

        public Builder sampleInterval(int sampleInterval) {
            if (sampleInterval < 1) {
                throw new IllegalArgumentException("sampleInterval must be at least one");
            }
            this.sampleInterval = sampleInterval;
            return this;
        }

        public Builder geometry(Geometry geometry) {
            this.geometry = (geometry != null) ? geometry : Geometry.SMOOTH;
            return this;
        }

        public Builder verticalExaggeration(float verticalExaggeration) {
            if (verticalExaggeration <= 0f) {
                throw new IllegalArgumentException("verticalExaggeration must be greater than zero");
            }
            this.verticalExaggeration = verticalExaggeration;
            return this;
        }

        public Builder includeWater(boolean includeWater) {
            this.includeWater = includeWater;
            return this;
        }

        public Builder includeNormals(boolean includeNormals) {
            this.includeNormals = includeNormals;
            return this;
        }

        public Builder centreOnOrigin(boolean centreOnOrigin) {
            this.centreOnOrigin = centreOnOrigin;
            return this;
        }

        public Builder summaryColumns(int summaryColumns) {
            if (summaryColumns < 8) {
                throw new IllegalArgumentException("summaryColumns must be at least eight");
            }
            this.summaryColumns = summaryColumns;
            return this;
        }

        public Builder includeGrids(boolean includeGrids) {
            this.includeGrids = includeGrids;
            return this;
        }

        public Builder colourScheme(ColourScheme colourScheme) {
            this.colourScheme = (colourScheme != null) ? colourScheme : ColourScheme.DEFAULT;
            return this;
        }

        public ReadableExportSettings build() {
            return new ReadableExportSettings(this);
        }

        private Rectangle area;
        private int sampleInterval = 1;
        private Geometry geometry = Geometry.SMOOTH;
        private float verticalExaggeration = 1f;
        private boolean includeWater = true;
        private boolean includeNormals = true;
        private boolean centreOnOrigin = true;
        private int summaryColumns = 128;
        private boolean includeGrids = true;
        private ColourScheme colourScheme = ColourScheme.DEFAULT;
    }

    private final Rectangle area;
    private final int sampleInterval, summaryColumns;
    private final Geometry geometry;
    private final float verticalExaggeration;
    private final boolean includeWater, includeNormals, centreOnOrigin, includeGrids;
    private final ColourScheme colourScheme;
}
