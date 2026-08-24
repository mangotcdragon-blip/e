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

/**
 * How the boundary between two terrain types is softened.
 *
 * <p>All three modes work the same way: instead of reading the terrain of the column being painted, the blender reads
 * the terrain of a nearby column, displaced by a small amount. Where the terrain is uniform that changes nothing;
 * along an edge between two terrain types it makes the two interlock. What differs between the modes is where the
 * displacement comes from, and that is what gives each its character.
 */
public enum BlendMode {
    /**
     * No blending: terrain boundaries stay exactly where they are. Useful for turning blending off without having to
     * take it out of a settings object.
     */
    NONE("None", "leave terrain boundaries exactly as they are"),

    /**
     * Displace by a smoothly varying (Perlin) amount, so that neighbouring columns are displaced in nearly the same
     * direction. The edge keeps its shape but grows organic fingers and inlets, the way a real coastline or forest
     * edge does. This is the mode to reach for first.
     */
    ORGANIC("Organic", "interlock the two terrains along wandering, natural looking fingers"),

    /**
     * Displace each column independently, by a random amount. Columns near the edge take on the other terrain with a
     * probability that falls off with distance, which produces the speckled gradient you would get by hand with a
     * low opacity brush. Good for a beach fading into grass, or gravel into stone.
     */
    SPECKLED("Speckled", "scatter the two terrains into each other, thinning out with distance"),

    /**
     * Both at once: a smooth displacement with a small amount of per-column jitter on top. The fingers of
     * {@link #ORGANIC} with softened edges of their own. The most natural looking, and the slowest, though on a GPU
     * the difference is not measurable.
     */
    COMBINED("Combined", "wandering fingers with softened, speckled edges");

    BlendMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Whether this mode displaces neighbouring columns by similar amounts.
     */
    public boolean isCoherent() {
        return (this == ORGANIC) || (this == COMBINED);
    }

    /**
     * Whether this mode displaces each column independently of its neighbours.
     */
    public boolean isStochastic() {
        return (this == SPECKLED) || (this == COMBINED);
    }

    @Override
    public String toString() {
        return displayName;
    }

    private final String displayName, description;
}
