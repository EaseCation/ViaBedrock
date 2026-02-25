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
import com.viaversion.viaversion.libs.fastutil.ints.*;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSection;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

import java.util.*;

public class BlockConnectionResolver {

    private enum ConnectionType { FENCE, NETHER_FENCE, PANE }

    private static final BlockFace[] HORIZONTAL_FACES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    // Java block state ID -> ConnectableProperties
    private static final Int2ObjectMap<ConnectableProperties> CONNECTABLE_BLOCKS = new Int2ObjectOpenHashMap<>();
    // Java block state IDs of solid blocks that fences/panes can connect to
    private static final IntSet SOLID_BLOCK_STATES = new IntOpenHashSet();
    // Java block state IDs of fence gates, mapped to their facing direction
    private static final Int2ObjectMap<BlockFace> FENCE_GATE_FACING = new Int2ObjectOpenHashMap<>();
    // Java block state IDs of wall blocks
    private static final IntSet WALL_STATES = new IntOpenHashSet();
    // Java block state IDs of stair blocks (connect like solid for fences)
    private static final IntSet STAIR_STATES = new IntOpenHashSet();

    public static void init(final BiMap<BlockState, Integer> javaBlockStates) {
        CONNECTABLE_BLOCKS.clear();
        SOLID_BLOCK_STATES.clear();
        FENCE_GATE_FACING.clear();
        WALL_STATES.clear();
        STAIR_STATES.clear();

        // Group connectable blocks by (identifier + waterlogged) to collect 16 connection variants
        final Map<String, Map<Integer, Integer>> connectableGroups = new HashMap<>();
        // Track connection type per group
        final Map<String, ConnectionType> groupTypes = new HashMap<>();

        for (Map.Entry<BlockState, Integer> entry : javaBlockStates.entrySet()) {
            final BlockState state = entry.getKey();
            final String id = state.identifier();
            final Map<String, String> props = state.properties();
            final int stateId = entry.getValue();

            // Identify fence gates
            if (id.endsWith("_fence_gate")) {
                final String facing = props.get("facing");
                if (facing != null) {
                    FENCE_GATE_FACING.put(stateId, parseFacing(facing));
                }
                continue;
            }

            // Identify walls
            if (id.endsWith("_wall")) {
                WALL_STATES.add(stateId);
                continue;
            }

            // Identify stairs
            if (id.endsWith("_stairs")) {
                STAIR_STATES.add(stateId);
                continue;
            }

            // Identify connectable blocks (fences, panes, iron bars)
            ConnectionType type = null;
            if (id.equals("nether_brick_fence")) {
                type = ConnectionType.NETHER_FENCE;
            } else if (id.endsWith("_fence")) {
                type = ConnectionType.FENCE;
            } else if (id.endsWith("_glass_pane") || id.equals("glass_pane") || id.equals("iron_bars")) {
                type = ConnectionType.PANE;
            }

            if (type != null && props.containsKey("east") && props.containsKey("north")
                    && props.containsKey("south") && props.containsKey("west")) {
                final String groupKey = state.namespacedIdentifier() + "|" + props.getOrDefault("waterlogged", "false");
                groupTypes.putIfAbsent(groupKey, type);

                final int connectionIndex = encodeConnections(
                        "true".equals(props.get("east")),
                        "true".equals(props.get("north")),
                        "true".equals(props.get("south")),
                        "true".equals(props.get("west"))
                );
                connectableGroups.computeIfAbsent(groupKey, k -> new HashMap<>()).put(connectionIndex, stateId);
                continue;
            }

            // Build solid block set - check if this block is solid
            if (isSolidBlock(id)) {
                SOLID_BLOCK_STATES.add(stateId);
            }
        }

        // Build ConnectableProperties for each group
        for (Map.Entry<String, Map<Integer, Integer>> groupEntry : connectableGroups.entrySet()) {
            final Map<Integer, Integer> indexToId = groupEntry.getValue();
            final ConnectionType type = groupTypes.get(groupEntry.getKey());

            // Verify all 16 variants are present
            final int[] connectionIds = new int[16];
            boolean complete = true;
            for (int i = 0; i < 16; i++) {
                final Integer id = indexToId.get(i);
                if (id == null) {
                    complete = false;
                    break;
                }
                connectionIds[i] = id;
            }
            if (!complete) continue;

            final ConnectableProperties properties = new ConnectableProperties(type, connectionIds);
            for (int stateId : connectionIds) {
                CONNECTABLE_BLOCKS.put(stateId, properties);
            }
        }
    }

    /**
     * Fix connection states for all connectable blocks in a remapped chunk.
     */
    public static void fixChunkConnections(final ChunkTracker tracker, final Chunk chunk,
                                           final int chunkX, final int chunkZ, final int minY) {
        final ChunkSection[] sections = chunk.getSections();

        for (int sIdx = 0; sIdx < sections.length; sIdx++) {
            final ChunkSection section = sections[sIdx];
            if (section == null) continue;

            final DataPalette palette = section.palette(PaletteType.BLOCKS);
            if (palette == null) continue;

            // Quick check: does this palette contain any connectable blocks?
            boolean hasConnectable = false;
            for (int i = 0; i < palette.size(); i++) {
                if (CONNECTABLE_BLOCKS.containsKey(palette.idByIndex(i))) {
                    hasConnectable = true;
                    break;
                }
            }
            if (!hasConnectable) continue;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int blockStateId = palette.idAt(x, y, z);
                        final ConnectableProperties props = CONNECTABLE_BLOCKS.get(blockStateId);
                        if (props == null) continue;

                        final int absX = chunkX * 16 + x;
                        final int absY = minY + sIdx * 16 + y;
                        final int absZ = chunkZ * 16 + z;
                        final BlockPosition pos = new BlockPosition(absX, absY, absZ);

                        final int connectionIndex = computeConnections(tracker, chunk, sections, pos, chunkX, chunkZ, minY, props);
                        if (connectionIndex != 0) {
                            palette.setIdAt(x, y, z, props.connectionIds[connectionIndex]);
                        }
                    }
                }
            }
        }
    }

    /**
     * Fix the connection state of a single block at the given position.
     */
    public static int fixSingleBlockConnection(final ChunkTracker chunkTracker, final BlockPosition position, final int javaBlockStateId) {
        final ConnectableProperties props = CONNECTABLE_BLOCKS.get(javaBlockStateId);
        if (props == null) return javaBlockStateId;

        final int connectionIndex = computeConnectionsFromTracker(chunkTracker, position, props);
        return props.connectionIds[connectionIndex];
    }

    /**
     * Check neighbors of a changed block and send connection updates if needed.
     */
    public static void updateNeighborConnections(final UserConnection user, final ChunkTracker chunkTracker, final BlockPosition center) {
        for (BlockFace face : HORIZONTAL_FACES) {
            final BlockPosition neighborPos = center.getRelative(face);
            final int neighborState = safeGetJavaBlockState(chunkTracker, neighborPos);
            final ConnectableProperties neighborProps = CONNECTABLE_BLOCKS.get(neighborState);
            if (neighborProps == null) continue;

            final int correctIndex = computeConnectionsFromTracker(chunkTracker, neighborPos, neighborProps);
            final int correctId = neighborProps.connectionIds[correctIndex];

            if (correctId != neighborState) {
                PacketFactory.sendJavaBlockUpdate(user, neighborPos, correctId);
            }
        }
    }

    // --- Connection computation ---

    private static int computeConnections(final ChunkTracker tracker, final Chunk currentChunk, final ChunkSection[] sections,
                                          final BlockPosition position, final int chunkX, final int chunkZ, final int minY,
                                          final ConnectableProperties props) {
        boolean east = connects(props.type, BlockFace.EAST, getBlockStateFromChunkOrTracker(tracker, sections, position.getRelative(BlockFace.EAST), chunkX, chunkZ, minY));
        boolean north = connects(props.type, BlockFace.NORTH, getBlockStateFromChunkOrTracker(tracker, sections, position.getRelative(BlockFace.NORTH), chunkX, chunkZ, minY));
        boolean south = connects(props.type, BlockFace.SOUTH, getBlockStateFromChunkOrTracker(tracker, sections, position.getRelative(BlockFace.SOUTH), chunkX, chunkZ, minY));
        boolean west = connects(props.type, BlockFace.WEST, getBlockStateFromChunkOrTracker(tracker, sections, position.getRelative(BlockFace.WEST), chunkX, chunkZ, minY));
        return encodeConnections(east, north, south, west);
    }

    private static int computeConnectionsFromTracker(final ChunkTracker tracker, final BlockPosition position,
                                                     final ConnectableProperties props) {
        boolean east = connects(props.type, BlockFace.EAST, safeGetJavaBlockState(tracker, position.getRelative(BlockFace.EAST)));
        boolean north = connects(props.type, BlockFace.NORTH, safeGetJavaBlockState(tracker, position.getRelative(BlockFace.NORTH)));
        boolean south = connects(props.type, BlockFace.SOUTH, safeGetJavaBlockState(tracker, position.getRelative(BlockFace.SOUTH)));
        boolean west = connects(props.type, BlockFace.WEST, safeGetJavaBlockState(tracker, position.getRelative(BlockFace.WEST)));
        return encodeConnections(east, north, south, west);
    }

    /**
     * Determine if a block of the given connection type connects to the neighbor block state.
     */
    private static boolean connects(final ConnectionType selfType, final BlockFace face, final int neighborState) {
        if (neighborState == 0) return false;

        // Check if neighbor is a connectable block of compatible type
        final ConnectableProperties neighborProps = CONNECTABLE_BLOCKS.get(neighborState);
        if (neighborProps != null) {
            return switch (selfType) {
                case FENCE -> neighborProps.type == ConnectionType.FENCE;
                case NETHER_FENCE -> neighborProps.type == ConnectionType.NETHER_FENCE;
                case PANE -> neighborProps.type == ConnectionType.PANE;
            };
        }

        // Fence gate check (only for fence types)
        if (selfType == ConnectionType.FENCE || selfType == ConnectionType.NETHER_FENCE) {
            final BlockFace gateFacing = FENCE_GATE_FACING.get(neighborState);
            if (gateFacing != null) {
                // Fence gate connects when its facing axis is perpendicular to the connection direction
                return face.axis() != gateFacing.axis();
            }
        }

        // Wall check (only for pane type)
        if (selfType == ConnectionType.PANE) {
            if (WALL_STATES.contains(neighborState)) return true;
        }

        // Stair blocks connect like solid blocks
        if (STAIR_STATES.contains(neighborState)) return true;

        // Solid block check
        return SOLID_BLOCK_STATES.contains(neighborState);
    }

    // --- Block state lookup ---

    private static int getBlockStateFromChunkOrTracker(final ChunkTracker tracker, final ChunkSection[] sections,
                                                       final BlockPosition pos, final int chunkX, final int chunkZ, final int minY) {
        final int posChunkX = pos.x() >> 4;
        final int posChunkZ = pos.z() >> 4;

        if (posChunkX == chunkX && posChunkZ == chunkZ) {
            final int sIdx = (pos.y() - minY) >> 4;
            if (sIdx < 0 || sIdx >= sections.length) return 0;
            final ChunkSection section = sections[sIdx];
            if (section == null) return 0;
            final DataPalette palette = section.palette(PaletteType.BLOCKS);
            if (palette == null) return 0;
            return palette.idAt(pos.x() & 15, pos.y() & 15, pos.z() & 15);
        } else {
            return safeGetJavaBlockState(tracker, pos);
        }
    }

    private static int safeGetJavaBlockState(final ChunkTracker tracker, final BlockPosition pos) {
        final BedrockChunkSection section = tracker.getChunkSection(pos);
        if (section == null || section.palettesCount(PaletteType.BLOCKS) == 0) return 0;
        return tracker.getJavaBlockState(pos);
    }

    // --- Utility methods ---

    private static int encodeConnections(boolean east, boolean north, boolean south, boolean west) {
        int index = 0;
        if (east) index |= 1;
        if (north) index |= 2;
        if (south) index |= 4;
        if (west) index |= 8;
        return index;
    }

    private static BlockFace parseFacing(final String facing) {
        return switch (facing) {
            case "north" -> BlockFace.NORTH;
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    private static boolean isSolidBlock(final String identifier) {
        // Air and fluids
        if (identifier.equals("air") || identifier.equals("cave_air") || identifier.equals("void_air")
                || identifier.equals("water") || identifier.equals("lava")) return false;

        // Non-solid suffixes
        for (String suffix : NON_SOLID_SUFFIXES) {
            if (identifier.endsWith(suffix)) return false;
        }

        // Non-solid specific blocks
        if (NON_SOLID_BLOCKS.contains(identifier)) return false;

        return true;
    }

    private static final String[] NON_SOLID_SUFFIXES = {
            "_fence", "_fence_gate", "_wall",
            "_pane",
            "_door", "_trapdoor",
            "_sign", "_hanging_sign", "_banner",
            "_button", "_pressure_plate",
            "_carpet", "_candle",
            "_torch", "_lantern", "_chain",
            "_rod", "_rail",
            "_flower", "_plant", "_sapling", "_mushroom",
            "_coral", "_coral_fan",
            "_pickle",
            "_head", "_skull",
            "_campfire",
            "_bed",
            "_cake",
            "_pot", "_cauldron",
            "_anvil",
            "_vine",
            "_fern", "_grass", "_bush",
            "_roots", "_sprouts",
            "_fungus",
            "_dripleaf",
            "_azalea",
            "_propagule",
            "_amethyst_cluster", "_amethyst_bud",
    };

    private static final Set<String> NON_SOLID_BLOCKS = Set.of(
            "barrier", "light",
            "enchanting_table", "brewing_stand",
            "hopper", "bell", "grindstone", "lectern",
            "composter", "stonecutter",
            "scaffolding",
            "honey_block", "slime_block",
            "spawner", "conduit",
            "end_portal_frame", "end_portal", "nether_portal",
            "daylight_detector",
            "farmland", "dirt_path", "soul_sand", "mud",
            "snow",
            "cobweb", "string",
            "ladder", "lever",
            "tripwire", "tripwire_hook",
            "redstone_wire", "redstone_torch", "redstone_wall_torch",
            "repeater", "comparator",
            "piston", "piston_head", "sticky_piston", "moving_piston",
            "chest", "trapped_chest", "ender_chest",
            "shulker_box", "white_shulker_box", "orange_shulker_box", "magenta_shulker_box",
            "light_blue_shulker_box", "yellow_shulker_box", "lime_shulker_box", "pink_shulker_box",
            "gray_shulker_box", "light_gray_shulker_box", "cyan_shulker_box", "purple_shulker_box",
            "blue_shulker_box", "brown_shulker_box", "green_shulker_box", "red_shulker_box",
            "black_shulker_box",
            "dragon_egg",
            "turtle_egg", "sniffer_egg", "frogspawn",
            "bamboo",
            "cactus", "sugar_cane", "kelp", "seagrass", "tall_seagrass",
            "lily_pad", "moss_carpet", "pink_petals",
            "wheat", "carrots", "potatoes", "beetroots",
            "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
            "sweet_berry_bush", "cave_vines", "cave_vines_plant",
            "nether_wart", "chorus_plant", "chorus_flower",
            "cocoa", "torchflower_crop", "pitcher_crop", "pitcher_plant",
            "fire", "soul_fire",
            "structure_void",
            "end_rod",
            "lightning_rod",
            "pointed_dripstone",
            "decorated_pot",
            "trial_spawner", "vault",
            "heavy_core",
            "pale_hanging_moss",
            "open_eyeblossom", "closed_eyeblossom",
            "creaking_heart"
    );

    // --- Data classes ---

    private static class ConnectableProperties {
        final ConnectionType type;
        final int[] connectionIds; // [0..15] indexed by 4-bit (east|north|south|west)

        ConnectableProperties(final ConnectionType type, final int[] connectionIds) {
            this.type = type;
            this.connectionIds = connectionIds;
        }
    }

}
