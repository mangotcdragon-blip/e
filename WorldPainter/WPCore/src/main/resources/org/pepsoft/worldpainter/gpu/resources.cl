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
 *
 * ---------------------------------------------------------------------------------------------------------------
 *
 * The two kernels that between them account for nearly all of the arithmetic in a WorldPainter export.
 *
 * Both decide, for every block below the surface, which material goes there, and both do it by evaluating a stack of
 * Perlin noise fields. On the CPU that is a dozen noise evaluations per block for the Resources layer and three for
 * the Stone Mix subsurface, repeated for every one of the hundreds of millions of underground blocks in a map. Here
 * one work item handles one block.
 *
 * Neither kernel touches a chunk: they produce an index into a palette the host assembled, and the host turns that
 * into blocks. That keeps all of the version specific block juggling (deepslate ore variants, nether gold) on the
 * Java side where it already lives.
 */

#include "perlin.cl"

/* Written to a block the kernel decided nothing should be placed in. */
#define WP_NO_MATERIAL 0xFF

/*
 * The Resources layer: ores, dirt and gravel pockets scattered through the stone.
 *
 * Mirrors ResourcesExporter.render(). For each block, the materials are tried in order and the first whose noise
 * field exceeds its chance for that column's resources value wins, exactly as the Java loop's break does.
 *
 * perms       materialCount permutation tables of 256 entries each, one per material, in material order
 * columns     five ints per column: world X, world Y, lowest y, highest y (inclusive), resources layer value
 * chances     materialCount * 16 thresholds, indexed [material * 16 + resourcesValue]
 * minLevels   the lowest y each material may occur at
 * maxLevels   the highest y each material may occur at
 * dirtScale   1 for materials sampled at the "small blobs" scale (dirt and gravel), 0 for the "tiny blobs" scale
 * result      one byte per block: the index of the material to place, or WP_NO_MATERIAL
 */
__kernel void wp_resources_layer(__global const ushort *perms,
                                 __global const int *columns,
                                 __global const float *chances,
                                 __global const int *minLevels,
                                 __global const int *maxLevels,
                                 __global const uchar *dirtScale,
                                 __global uchar *result,
                                 const int materialCount,
                                 const int columnCount,
                                 const int yBase,
                                 const int depth,
                                 const float tinyBlobs,
                                 const float smallBlobs) {
    const int gid = get_global_id(0);
    if (gid >= (columnCount * depth)) {
        return;
    }
    result[gid] = WP_NO_MATERIAL;

    const int columnIndex = gid / depth;
    const int y = yBase + (gid - (columnIndex * depth));
    const int columnBase = columnIndex * 5;

    if ((y < columns[columnBase + 2]) || (y > columns[columnBase + 3])) {
        return;
    }
    const int resourcesValue = columns[columnBase + 4];
    if ((resourcesValue <= 0) || (resourcesValue > 15)) {
        return;
    }

    const int worldX = columns[columnBase];
    const int worldY = columns[columnBase + 1];

    /* The Java code divides an int by a float constant, so the division happens in single precision and only then
       widens to double. Doing it in double would give a slightly different value and, occasionally, a different block. */
    const double dx = (double) ((float) worldX / tinyBlobs);
    const double dy = (double) ((float) worldY / tinyBlobs);
    const double dz = (double) ((float) y / tinyBlobs);
    const double dirtX = (double) ((float) worldX / smallBlobs);
    const double dirtY = (double) ((float) worldY / smallBlobs);
    const double dirtZ = (double) ((float) y / smallBlobs);

    for (int i = 0; i < materialCount; i++) {
        const float chance = chances[(i * 16) + resourcesValue];
        if ((chance <= 0.5f) && (y >= minLevels[i]) && (y <= maxLevels[i])) {
            __global const ushort *perm = perms + (i * 256);
            const float noise = dirtScale[i] ? wp_perlin3(perm, dirtX, dirtY, dirtZ) : wp_perlin3(perm, dx, dy, dz);
            if (noise >= chance) {
                result[gid] = (uchar) i;
                return;
            }
        }
    }
}

/*
 * The Stone Mix subsurface terrain: stone or deepslate with patches of granite, diorite, andesite and tuff.
 *
 * Mirrors the Terrain.STONE_MIX branch of WorldPainterChunkFactory.applySubSurface(). The palette indices match
 * org.pepsoft.worldpainter.gpu.StoneMixKernel.PALETTE.
 *
 * The band from y = -4 to y = -1 is deliberately left to the host: there the Java code consults a shared
 * java.util.Random, whose value depends on how many blocks have been generated before it and by which thread, and
 * which therefore cannot be reproduced here. Those four layers are marked WP_STONE_MIX_HOST and generated on the CPU,
 * which keeps an accelerated export identical to an unaccelerated one.
 *
 * perms    three permutation tables of 256 entries: granite, diorite, andesite
 * columns  four ints per column: world X, world Y, lowest y, highest y (inclusive)
 */
#define WP_STONE_MIX_HOST 0xFE

__kernel void wp_stone_mix(__global const ushort *perms,
                           __global const int *columns,
                           __global uchar *result,
                           const int columnCount,
                           const int yBase,
                           const int depth,
                           const int layerOffset,
                           const float smallBlobs,
                           const float graniteChance,
                           const float dioriteChance,
                           const float andesiteChance) {
    const int gid = get_global_id(0);
    if (gid >= (columnCount * depth)) {
        return;
    }
    result[gid] = WP_NO_MATERIAL;

    const int columnIndex = gid / depth;
    const int y = yBase + (gid - (columnIndex * depth));
    const int columnBase = columnIndex * 4;

    if ((y < columns[columnBase + 2]) || (y > columns[columnBase + 3])) {
        return;
    }

    const int z = y + layerOffset;
    if ((z < 0) && (z >= -4)) {
        /* Depends on a shared Random on the Java side; the host generates these four layers itself. */
        result[gid] = WP_STONE_MIX_HOST;
        return;
    }

    const double dx = (double) ((float) columns[columnBase] / smallBlobs);
    const double dy = (double) ((float) columns[columnBase + 1] / smallBlobs);
    const double dz = (double) ((float) z / smallBlobs);

    /* Above y = 0 stone and its variants, below it deepslate and tuff. The noise fields, and therefore the shapes of
       the patches, are the same in both. */
    const int deepslate = (z < 0) ? 4 : 0;
    if (wp_perlin3(perms, dx, dy, dz) > graniteChance) {
        result[gid] = (uchar) (1 + deepslate);
    } else if (wp_perlin3(perms + 256, dx, dy, dz) > dioriteChance) {
        result[gid] = (uchar) (2 + deepslate);
    } else if (wp_perlin3(perms + 512, dx, dy, dz) > andesiteChance) {
        result[gid] = (uchar) (3 + deepslate);
    } else {
        result[gid] = (uchar) deepslate;
    }
}
