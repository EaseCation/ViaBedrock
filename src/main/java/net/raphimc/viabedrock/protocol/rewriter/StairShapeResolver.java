/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.rewriter;

import com.google.common.collect.BiMap;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.chunks.Chunk;
import com.viaversion.viaversion.api.minecraft.chunks.ChunkSection;
import com.viaversion.viaversion.api.minecraft.chunks.DataPalette;
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSection;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

import java.util.*;

public class StairShapeResolver {

    private static final int SHAPE_STRAIGHT = 0;
    private static final int SHAPE_INNER_LEFT = 1;
    private static final int SHAPE_INNER_RIGHT = 2;
    private static final int SHAPE_OUTER_LEFT = 3;
    private static final int SHAPE_OUTER_RIGHT = 4;

    private static final String[] SHAPE_NAMES = {"straight", "inner_left", "inner_right", "outer_left", "outer_right"};

    private static final BlockFace[] HORIZONTAL_FACES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private static final Int2ObjectMap<StairProperties> STAIR_PROPERTIES = new Int2ObjectOpenHashMap<>();

    public static void init(final BiMap<BlockState, Integer> javaBlockStates) {
        STAIR_PROPERTIES.clear();

        // Group stair block states by (namespacedIdentifier + facing + half + waterlogged)
        // For each group, collect the 5 shape variant IDs
        final Map<String, Map<String, Integer>> stairsByGroup = new HashMap<>();

        for (Map.Entry<BlockState, Integer> entry : javaBlockStates.entrySet()) {
            final BlockState state = entry.getKey();
            if (!state.identifier().endsWith("_stairs")) continue;

            final Map<String, String> props = state.properties();
            if (!props.containsKey("shape") || !props.containsKey("facing") || !props.containsKey("half")) continue;

            // Group key: identifier + facing + half + waterlogged
            final String groupKey = state.namespacedIdentifier() + "|" + props.get("facing") + "|" + props.get("half") + "|" + props.getOrDefault("waterlogged", "false");
            final String shape = props.get("shape");

            stairsByGroup.computeIfAbsent(groupKey, k -> new HashMap<>()).put(shape, entry.getValue());
        }

        // Build the lookup table
        for (Map.Entry<String, Map<String, Integer>> groupEntry : stairsByGroup.entrySet()) {
            final Map<String, Integer> shapeToId = groupEntry.getValue();

            // Verify all 5 shapes are present
            final int[] shapeIds = new int[5];
            boolean complete = true;
            for (int i = 0; i < 5; i++) {
                final Integer id = shapeToId.get(SHAPE_NAMES[i]);
                if (id == null) {
                    complete = false;
                    break;
                }
                shapeIds[i] = id;
            }
            if (!complete) continue;

            // Parse group key to extract properties
            final String[] parts = groupEntry.getKey().split("\\|");
            final BlockFace facing = parseFacing(parts[1]);
            final boolean bottom = parts[2].equals("bottom");
            final boolean waterlogged = parts[3].equals("true");

            // Register all 5 shape variants with the same StairProperties
            final StairProperties properties = new StairProperties(facing, bottom, waterlogged, shapeIds);
            for (int id : shapeIds) {
                STAIR_PROPERTIES.put(id, properties);
            }
        }
    }

    /**
     * Returns the StairProperties for a given Java block state ID, or null if not a stair.
     */
    public static StairProperties getStairProperties(final int javaBlockStateId) {
        return STAIR_PROPERTIES.get(javaBlockStateId);
    }

    /**
     * Fix stair shapes for all stair blocks in a remapped chunk.
     * Should be called after block state remapping and waterlogging, but before heightmap calculation.
     */
    public static void fixChunkStairShapes(final ChunkTracker tracker, final Chunk chunk, final int chunkX, final int chunkZ, final int minY) {
        final ChunkSection[] sections = chunk.getSections();

        for (int sIdx = 0; sIdx < sections.length; sIdx++) {
            final ChunkSection section = sections[sIdx];
            if (section == null) continue;

            final DataPalette palette = section.palette(PaletteType.BLOCKS);
            if (palette == null) continue;

            // Quick check: does this palette contain any stair blocks?
            boolean hasStairs = false;
            for (int i = 0; i < palette.size(); i++) {
                if (STAIR_PROPERTIES.containsKey(palette.idByIndex(i))) {
                    hasStairs = true;
                    break;
                }
            }
            if (!hasStairs) continue;

            // Iterate all blocks in this section
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int blockStateId = palette.idAt(x, y, z);
                        final StairProperties stair = STAIR_PROPERTIES.get(blockStateId);
                        if (stair == null) continue;

                        final int absX = chunkX * 16 + x;
                        final int absY = minY + sIdx * 16 + y;
                        final int absZ = chunkZ * 16 + z;
                        final BlockPosition pos = new BlockPosition(absX, absY, absZ);

                        final int shapeIndex = computeShape(tracker, chunk, sections, pos, chunkX, chunkZ, minY, stair);
                        if (shapeIndex != SHAPE_STRAIGHT) {
                            palette.setIdAt(x, y, z, stair.shapeIds[shapeIndex]);
                        }
                    }
                }
            }
        }
    }

    /**
     * Fix the shape of a single stair block at the given position.
     * Returns the corrected Java block state ID (unchanged if not a stair or already straight).
     */
    public static int fixSingleStairShape(final ChunkTracker chunkTracker, final BlockPosition position, final int javaBlockStateId) {
        final StairProperties stair = STAIR_PROPERTIES.get(javaBlockStateId);
        if (stair == null) return javaBlockStateId;

        final int shapeIndex = computeShapeFromTracker(chunkTracker, position, stair);
        return stair.shapeIds[shapeIndex];
    }

    /**
     * Check neighbors of a changed block and send stair shape updates if needed.
     */
    public static void updateNeighborStairShapes(final UserConnection user, final ChunkTracker chunkTracker, final BlockPosition center) {
        for (BlockFace face : HORIZONTAL_FACES) {
            final BlockPosition neighborPos = center.getRelative(face);
            final int neighborState = safeGetJavaBlockState(chunkTracker, neighborPos);
            final StairProperties neighborStair = STAIR_PROPERTIES.get(neighborState);
            if (neighborStair == null) continue;

            final int correctShape = computeShapeFromTracker(chunkTracker, neighborPos, neighborStair);
            final int correctId = neighborStair.shapeIds[correctShape];

            // The tracker returns shape=straight, so we need to compare with the computed correct shape
            // If the correct shape is not straight, we need to send an update
            // We always send the update since the client may have the wrong shape from the initial mapping
            if (correctId != neighborStair.shapeIds[SHAPE_STRAIGHT] || neighborState != neighborStair.shapeIds[SHAPE_STRAIGHT]) {
                PacketFactory.sendJavaBlockUpdate(user, neighborPos, correctId);
            }
        }
    }

    // --- Shape computation ---

    /**
     * Compute the shape index for a stair block within a chunk being remapped.
     * Uses the chunk's remapped sections for in-chunk lookups and falls back to ChunkTracker for cross-chunk.
     */
    private static int computeShape(final ChunkTracker tracker, final Chunk currentChunk, final ChunkSection[] sections,
                                    final BlockPosition position, final int chunkX, final int chunkZ, final int minY,
                                    final StairProperties stair) {
        final BlockFace facing = stair.facing;

        // Check the block in front (facing direction)
        final BlockPosition frontPos = position.getRelative(facing);
        final StairProperties frontStair = getStairAt(tracker, currentChunk, sections, frontPos, chunkX, chunkZ, minY);
        if (frontStair != null && frontStair.bottom == stair.bottom) {
            final BlockFace frontFacing = frontStair.facing;
            if (facing.axis() != frontFacing.axis()) {
                // Check opposite direction
                final BlockPosition oppositePos = position.getRelative(frontFacing.opposite());
                final StairProperties oppositeStair = getStairAt(tracker, currentChunk, sections, oppositePos, chunkX, chunkZ, minY);
                if (oppositeStair == null || oppositeStair.facing != stair.facing || oppositeStair.bottom != stair.bottom) {
                    return frontFacing == rotateCounterClockwise(facing) ? SHAPE_OUTER_LEFT : SHAPE_OUTER_RIGHT;
                }
            }
        }

        // Check the block behind (opposite of facing direction)
        final BlockPosition backPos = position.getRelative(facing.opposite());
        final StairProperties backStair = getStairAt(tracker, currentChunk, sections, backPos, chunkX, chunkZ, minY);
        if (backStair != null && backStair.bottom == stair.bottom) {
            final BlockFace backFacing = backStair.facing;
            if (facing.axis() != backFacing.axis()) {
                // Check the direction the back stair is facing
                final BlockPosition checkPos = position.getRelative(backFacing);
                final StairProperties checkStair = getStairAt(tracker, currentChunk, sections, checkPos, chunkX, chunkZ, minY);
                if (checkStair == null || checkStair.facing != stair.facing || checkStair.bottom != stair.bottom) {
                    return backFacing == rotateCounterClockwise(facing) ? SHAPE_INNER_LEFT : SHAPE_INNER_RIGHT;
                }
            }
        }

        return SHAPE_STRAIGHT;
    }

    /**
     * Compute the shape index for a stair block using ChunkTracker for all neighbor lookups.
     * Used for single-block updates.
     */
    private static int computeShapeFromTracker(final ChunkTracker tracker, final BlockPosition position, final StairProperties stair) {
        final BlockFace facing = stair.facing;

        // Check the block in front (facing direction)
        final BlockPosition frontPos = position.getRelative(facing);
        final StairProperties frontStair = STAIR_PROPERTIES.get(safeGetJavaBlockState(tracker,frontPos));
        if (frontStair != null && frontStair.bottom == stair.bottom) {
            final BlockFace frontFacing = frontStair.facing;
            if (facing.axis() != frontFacing.axis()) {
                final BlockPosition oppositePos = position.getRelative(frontFacing.opposite());
                final StairProperties oppositeStair = STAIR_PROPERTIES.get(safeGetJavaBlockState(tracker,oppositePos));
                if (oppositeStair == null || oppositeStair.facing != stair.facing || oppositeStair.bottom != stair.bottom) {
                    return frontFacing == rotateCounterClockwise(facing) ? SHAPE_OUTER_LEFT : SHAPE_OUTER_RIGHT;
                }
            }
        }

        // Check the block behind (opposite of facing direction)
        final BlockPosition backPos = position.getRelative(facing.opposite());
        final StairProperties backStair = STAIR_PROPERTIES.get(safeGetJavaBlockState(tracker,backPos));
        if (backStair != null && backStair.bottom == stair.bottom) {
            final BlockFace backFacing = backStair.facing;
            if (facing.axis() != backFacing.axis()) {
                final BlockPosition checkPos = position.getRelative(backFacing);
                final StairProperties checkStair = STAIR_PROPERTIES.get(safeGetJavaBlockState(tracker,checkPos));
                if (checkStair == null || checkStair.facing != stair.facing || checkStair.bottom != stair.bottom) {
                    return backFacing == rotateCounterClockwise(facing) ? SHAPE_INNER_LEFT : SHAPE_INNER_RIGHT;
                }
            }
        }

        return SHAPE_STRAIGHT;
    }

    /**
     * Get the StairProperties of a block at the given position, checking the current chunk first, then falling back to ChunkTracker.
     */
    private static StairProperties getStairAt(final ChunkTracker tracker, final Chunk currentChunk, final ChunkSection[] sections,
                                              final BlockPosition pos, final int chunkX, final int chunkZ, final int minY) {
        final int blockStateId = getBlockStateFromChunkOrTracker(tracker, sections, pos, chunkX, chunkZ, minY);
        return STAIR_PROPERTIES.get(blockStateId);
    }

    /**
     * Get the Java block state ID at a position, using the current chunk's remapped data if possible,
     * otherwise falling back to ChunkTracker.
     */
    private static int getBlockStateFromChunkOrTracker(final ChunkTracker tracker, final ChunkSection[] sections,
                                                       final BlockPosition pos, final int chunkX, final int chunkZ, final int minY) {
        final int posChunkX = pos.x() >> 4;
        final int posChunkZ = pos.z() >> 4;

        if (posChunkX == chunkX && posChunkZ == chunkZ) {
            // Within the current chunk - read from remapped sections
            final int sIdx = (pos.y() - minY) >> 4;
            if (sIdx < 0 || sIdx >= sections.length) return 0;
            final ChunkSection section = sections[sIdx];
            if (section == null) return 0;
            final DataPalette palette = section.palette(PaletteType.BLOCKS);
            if (palette == null) return 0;
            return palette.idAt(pos.x() & 15, pos.y() & 15, pos.z() & 15);
        } else {
            // Cross-chunk boundary
            return safeGetJavaBlockState(tracker, pos);
        }
    }

    /**
     * Safely get the Java block state at a position via ChunkTracker, returning 0 (air) if the section has no palette data.
     */
    private static int safeGetJavaBlockState(final ChunkTracker tracker, final BlockPosition pos) {
        final BedrockChunkSection section = tracker.getChunkSection(pos);
        if (section == null || section.palettesCount(PaletteType.BLOCKS) == 0) return 0;
        return tracker.getJavaBlockState(pos);
    }

    // --- Utility methods ---

    private static BlockFace rotateCounterClockwise(final BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case WEST -> BlockFace.SOUTH;
            default -> face;
        };
    }

    private static BlockFace parseFacing(final String facing) {
        return switch (facing) {
            case "north" -> BlockFace.NORTH;
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            default -> throw new IllegalArgumentException("Invalid facing: " + facing);
        };
    }

    // --- Data classes ---

    public static class StairProperties {
        final BlockFace facing;
        final boolean bottom;
        final boolean waterlogged;
        final int[] shapeIds; // [straight, inner_left, inner_right, outer_left, outer_right]

        StairProperties(final BlockFace facing, final boolean bottom, final boolean waterlogged, final int[] shapeIds) {
            this.facing = facing;
            this.bottom = bottom;
            this.waterlogged = waterlogged;
            this.shapeIds = shapeIds;
        }
    }

}
