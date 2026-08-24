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

import java.io.Serial;
import java.io.Serializable;

/**
 * What a blend should do. Immutable; build one with {@link #builder()}.
 *
 * <p>The defaults are a gentle blend that improves most maps without anyone having to think about it: an eight block
 * organic terrain blend and a light smoothing of the height map, both confined to the places where something actually
 * changes.
 */
public final class BlendSettings implements Serializable {
    private BlendSettings(Builder builder) {
        terrainMode = builder.terrainMode;
        terrainRadius = builder.terrainRadius;
        terrainScale = builder.terrainScale;
        terrainStrength = builder.terrainStrength;
        boundariesOnly = builder.boundariesOnly;
        heightRadius = builder.heightRadius;
        heightStrength = builder.heightStrength;
        heightSlopeThreshold = builder.heightSlopeThreshold;
        blendWaterLevel = builder.blendWaterLevel;
        seedOffset = builder.seedOffset;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** How terrain boundaries are softened, or {@link BlendMode#NONE} to leave the terrain alone. */
    public BlendMode getTerrainMode() {
        return terrainMode;
    }

    /** How far, in blocks, a terrain may bleed across its boundary. */
    public int getTerrainRadius() {
        return terrainRadius;
    }

    /**
     * The size, in blocks, of the features in the noise that drives an {@link BlendMode#ORGANIC} blend. Larger values
     * give longer, lazier fingers; smaller values give a busier, more fretted edge.
     */
    public float getTerrainScale() {
        return terrainScale;
    }

    /** How much of the full displacement to apply, from 0 (none) to 1. */
    public float getTerrainStrength() {
        return terrainStrength;
    }

    /**
     * Whether to leave columns alone unless there is a different terrain within {@link #getTerrainRadius()} of them.
     * On by default: it stops the blend from quietly reshuffling the inside of a large uniform area, where the result
     * would be identical anyway but the work would not be.
     */
    public boolean isBoundariesOnly() {
        return boundariesOnly;
    }

    /** The radius, in blocks, of the height smoothing kernel. Zero switches height blending off. */
    public int getHeightRadius() {
        return heightRadius;
    }

    /** How far to move each height towards the smoothed value, from 0 (not at all) to 1 (all the way). */
    public float getHeightStrength() {
        return heightStrength;
    }

    /**
     * Only smooth a column when the terrain around it rises or falls by more than this many blocks. This keeps flat
     * ground perfectly flat and confines the smoothing to the cliffs and terrace edges that need it. Zero smooths
     * everything.
     */
    public float getHeightSlopeThreshold() {
        return heightSlopeThreshold;
    }

    /**
     * Whether a column that takes its terrain from a neighbour should take that neighbour's water level too. Off by
     * default, because it moves shorelines.
     */
    public boolean isBlendWaterLevel() {
        return blendWaterLevel;
    }

    /**
     * Added to the dimension seed to produce the noise used for the blend, so that two blends of the same map with
     * different offsets come out differently.
     */
    public long getSeedOffset() {
        return seedOffset;
    }

    /** Whether this settings object would change anything at all. */
    public boolean isNoOp() {
        final boolean noTerrain = (terrainMode == BlendMode.NONE) || (terrainRadius <= 0) || (terrainStrength <= 0f);
        final boolean noHeight = (heightRadius <= 0) || (heightStrength <= 0f);
        return noTerrain && noHeight;
    }

    public Builder toBuilder() {
        return new Builder()
                .terrainMode(terrainMode)
                .terrainRadius(terrainRadius)
                .terrainScale(terrainScale)
                .terrainStrength(terrainStrength)
                .boundariesOnly(boundariesOnly)
                .heightRadius(heightRadius)
                .heightStrength(heightStrength)
                .heightSlopeThreshold(heightSlopeThreshold)
                .blendWaterLevel(blendWaterLevel)
                .seedOffset(seedOffset);
    }

    @Override
    public String toString() {
        return "BlendSettings[terrain=" + terrainMode + " radius=" + terrainRadius + " scale=" + terrainScale
                + " strength=" + terrainStrength + " boundariesOnly=" + boundariesOnly
                + ", height radius=" + heightRadius + " strength=" + heightStrength
                + " slopeThreshold=" + heightSlopeThreshold + ']';
    }

    public static final class Builder {
        public Builder terrainMode(BlendMode terrainMode) {
            this.terrainMode = (terrainMode != null) ? terrainMode : BlendMode.NONE;
            return this;
        }

        public Builder terrainRadius(int terrainRadius) {
            if ((terrainRadius < 0) || (terrainRadius > MAX_RADIUS)) {
                throw new IllegalArgumentException("terrainRadius must be between 0 and " + MAX_RADIUS);
            }
            this.terrainRadius = terrainRadius;
            return this;
        }

        public Builder terrainScale(float terrainScale) {
            if (terrainScale <= 0f) {
                throw new IllegalArgumentException("terrainScale must be greater than zero");
            }
            this.terrainScale = terrainScale;
            return this;
        }

        public Builder terrainStrength(float terrainStrength) {
            this.terrainStrength = clamp(terrainStrength);
            return this;
        }

        public Builder boundariesOnly(boolean boundariesOnly) {
            this.boundariesOnly = boundariesOnly;
            return this;
        }

        public Builder heightRadius(int heightRadius) {
            if ((heightRadius < 0) || (heightRadius > MAX_RADIUS)) {
                throw new IllegalArgumentException("heightRadius must be between 0 and " + MAX_RADIUS);
            }
            this.heightRadius = heightRadius;
            return this;
        }

        public Builder heightStrength(float heightStrength) {
            this.heightStrength = clamp(heightStrength);
            return this;
        }

        public Builder heightSlopeThreshold(float heightSlopeThreshold) {
            if (heightSlopeThreshold < 0f) {
                throw new IllegalArgumentException("heightSlopeThreshold may not be negative");
            }
            this.heightSlopeThreshold = heightSlopeThreshold;
            return this;
        }

        public Builder blendWaterLevel(boolean blendWaterLevel) {
            this.blendWaterLevel = blendWaterLevel;
            return this;
        }

        public Builder seedOffset(long seedOffset) {
            this.seedOffset = seedOffset;
            return this;
        }

        public BlendSettings build() {
            return new BlendSettings(this);
        }

        private static float clamp(float value) {
            return Math.max(0f, Math.min(1f, value));
        }

        private BlendMode terrainMode = BlendMode.ORGANIC;
        private int terrainRadius = 8;
        private float terrainScale = 24f;
        private float terrainStrength = 1f;
        private boolean boundariesOnly = true;
        private int heightRadius = 2;
        private float heightStrength = 0.5f;
        private float heightSlopeThreshold = 1.5f;
        private boolean blendWaterLevel;
        private long seedOffset = DEFAULT_SEED_OFFSET;
    }

    private final BlendMode terrainMode;
    private final int terrainRadius, heightRadius;
    private final float terrainScale, terrainStrength, heightStrength, heightSlopeThreshold;
    private final boolean boundariesOnly, blendWaterLevel;
    private final long seedOffset;

    /**
     * The largest radius a blend may use. Beyond this a "blend" stops being a boundary treatment and starts being a
     * different map, and the margins the implementation has to read around each block of work become unreasonable.
     */
    public static final int MAX_RADIUS = 64;

    /** An arbitrary but fixed offset, so that the blend noise is unrelated to any of WorldPainter's other noise. */
    public static final long DEFAULT_SEED_OFFSET = 3141592653L;

    @Serial
    private static final long serialVersionUID = 1L;
}
