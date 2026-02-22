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
package net.raphimc.viabedrock.experimental.light;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.ChunkPosition;
import com.viaversion.viaversion.api.minecraft.chunks.*;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_9to1_21_11.packet.ClientboundPackets1_21_11;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;

import java.util.*;

/**
 * Async light computation engine implementing ChunkLightProvider.
 * Manages all light-related state and computation that was previously in ChunkTracker.
 * <p>
 * Light is computed asynchronously using GlobalLightCache's thread pool,
 * with results delivered back to the Netty event loop for safe packet sending.
 */
public class AsyncLightEngine implements ChunkLightProvider {

    private static final int MAX_LIGHT_UPDATES_PER_TICK = 8;
    private static final int[][] SHOULD_ENQUEUE_OFFSETS = {{-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final int[][] SPREAD_DIRS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final int[][] NEIGHBOR_OFFSETS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private final UserConnection user;

    private final Set<Long> sentChunks = new HashSet<>();
    private final Map<Long, ChunkSection[]> cachedSections = new HashMap<>();
    private final Map<Long, byte[][]> cachedSkyLight = new HashMap<>();
    private final Map<Long, byte[][]> cachedBlockLight = new HashMap<>();
    private final Set<Long> pendingLightUpdates = new HashSet<>();
    private final Set<Long> pendingAsyncLight = new HashSet<>();

    public AsyncLightEngine(final UserConnection user) {
        this.user = user;
    }

    @Override
    public boolean processAndSendChunk(final ChunkTracker tracker, final int chunkX, final int chunkZ, final Chunk chunk) {
        final ChunkSection[] sections = chunk.getSections();
        final int sectionCount = sections.length;
        final int lightSectionCount = sectionCount + 2;
        final long chunkKey = ChunkPosition.chunkKey(chunkX, chunkZ);

        // Cache remapped sections for neighbor light calculations
        this.cachedSections.put(chunkKey, sections);

        // Strip custom block data BEFORE hash (so vanilla/FabricRock get different cache keys)
        if (!this.user.get(GameSessionStorage.class).hasFabricRock()) {
            tracker.stripCustomBlockData(chunk);
        }

        // Collect neighbor data for cross-chunk light propagation
        final ChunkSection[][] neighborSections = collectNeighborSections(chunkX, chunkZ);
        final byte[][][] neighborCachedSkyLight = collectNeighborCachedSkyLight(chunkX, chunkZ);
        final byte[][][] neighborCachedBlockLight = collectNeighborCachedBlockLight(chunkX, chunkZ);

        // Try global light cache first
        final long lightCacheKey = computeCombinedLightCacheKey(sections, neighborSections, neighborCachedSkyLight, neighborCachedBlockLight);
        final GlobalLightCache.LightCacheEntry cached = GlobalLightCache.getInstance().get(lightCacheKey);

        final byte[][] skyLight;
        final byte[][] blockLight;

        if (cached != null) {
            // Cache hit: use real light
            skyLight = cached.skyLight();
            blockLight = cached.blockLight();
            this.cachedSkyLight.put(chunkKey, skyLight);
            this.cachedBlockLight.put(chunkKey, blockLight);

            tracker.sendChunkWithLight(chunk, skyLight, blockLight, lightSectionCount);
            this.sentChunks.add(chunkKey);
            this.markNeighborLightDirty(chunkX, chunkZ);
        } else {
            // Cache miss: send with placeholder light, compute async
            skyLight = generatePlaceholderSkyLight(lightSectionCount);
            blockLight = new byte[lightSectionCount][];
            this.cachedSkyLight.put(chunkKey, skyLight);
            this.cachedBlockLight.put(chunkKey, blockLight);

            tracker.sendChunkWithLight(chunk, skyLight, blockLight, lightSectionCount);
            this.sentChunks.add(chunkKey);
            // Don't markNeighborLightDirty here — placeholder light would pollute neighbor calculations

            // Submit async light computation
            this.pendingAsyncLight.add(chunkKey);
            GlobalLightCache.getInstance().submitAsync(() -> {
                final byte[][] realSkyLight = computeSkyLight(sections, neighborSections, neighborCachedSkyLight);
                final byte[][] realBlockLight = computeBlockLight(sections, neighborSections, neighborCachedBlockLight);
                GlobalLightCache.getInstance().put(lightCacheKey, realSkyLight, realBlockLight);

                user.getChannel().eventLoop().execute(() -> {
                    if (!user.getChannel().isActive()) return;
                    if (!this.sentChunks.contains(chunkKey)) return;
                    if (this.cachedSections.get(chunkKey) != sections) return; // chunk was overwritten
                    this.pendingAsyncLight.remove(chunkKey);
                    this.cachedSkyLight.put(chunkKey, realSkyLight);
                    this.cachedBlockLight.put(chunkKey, realBlockLight);
                    this.sendLightUpdate(chunkX, chunkZ, realSkyLight, realBlockLight);
                    this.markNeighborLightDirty(chunkX, chunkZ);
                });
            });
        }

        return true;
    }

    @Override
    public void onChunkUnload(final long chunkKey) {
        this.sentChunks.remove(chunkKey);
        this.cachedSections.remove(chunkKey);
        this.cachedSkyLight.remove(chunkKey);
        this.cachedBlockLight.remove(chunkKey);
        this.pendingLightUpdates.remove(chunkKey);
        this.pendingAsyncLight.remove(chunkKey);
    }

    @Override
    public void tick() {
        if (!this.pendingLightUpdates.isEmpty()) {
            int lightBudget = MAX_LIGHT_UPDATES_PER_TICK;
            final Iterator<Long> it = this.pendingLightUpdates.iterator();
            while (it.hasNext() && lightBudget > 0) {
                final long chunkKey = it.next();
                it.remove();
                lightBudget--;
                this.updateSingleNeighborLight(chunkKey);
            }
        }
    }

    // --- Light computation methods ---

    private static byte[][] generatePlaceholderSkyLight(final int lightSectionCount) {
        final byte[][] skyLight = new byte[lightSectionCount][];
        for (int i = 0; i < lightSectionCount; i++) {
            skyLight[i] = new byte[ChunkSectionLight.LIGHT_LENGTH];
            Arrays.fill(skyLight[i], (byte) 0xFF);
        }
        return skyLight;
    }

    // neighborSections: [0]=-X, [1]=+X, [2]=-Z, [3]=+Z; elements may be null
    // neighborCachedSkyLight: cached sky light from already-sent neighbors; elements may be null
    private static byte[][] computeSkyLight(final ChunkSection[] sections, final ChunkSection[][] neighborSections, final byte[][][] neighborCachedSkyLight) {
        final int sectionCount = sections.length;
        final int lightSectionCount = sectionCount + 2;
        final BedrockMappingData mappings = BedrockProtocol.MAPPINGS;

        // lightData[0] = below bottom section, lightData[1..sectionCount] = actual sections, lightData[sectionCount+1] = above top
        final byte[][] lightData = new byte[lightSectionCount][];
        for (int i = 0; i < lightSectionCount; i++) {
            lightData[i] = new byte[ChunkSectionLight.LIGHT_LENGTH];
        }

        // Above-top section: all 15
        Arrays.fill(lightData[sectionCount + 1], (byte) 0xFF);

        // Phase 1: Column-based sky light initialization (top to bottom)
        final int[][] skyLevel = new int[16][16];
        for (int[] row : skyLevel) Arrays.fill(row, 15);

        for (int sIdx = sectionCount - 1; sIdx >= 0; sIdx--) {
            final DataPalette blockPalette = sections[sIdx].palette(PaletteType.BLOCKS);
            final byte[] sectionLight = lightData[sIdx + 1];

            if (blockPalette.size() == 1 && blockPalette.idByIndex(0) == 0) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int level = skyLevel[x][z];
                        if (level > 0) {
                            for (int y = 15; y >= 0; y--) {
                                setNibble(sectionLight, x, y, z, level);
                            }
                        }
                    }
                }
                continue;
            }

            for (int y = 15; y >= 0; y--) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int level = skyLevel[x][z];
                        if (level <= 0) continue;

                        final int blockState = blockPalette.idAt(x, y, z);
                        final int filter = mappings.getFilterLight(blockState);

                        if (filter > 0) {
                            level = Math.max(0, level - Math.max(1, filter));
                        }

                        skyLevel[x][z] = level;
                        if (level > 0) {
                            setNibble(sectionLight, x, y, z, level);
                        }
                    }
                }
            }
        }

        // Below-bottom section: continue from skyLevel
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int level = skyLevel[x][z];
                if (level > 0) {
                    for (int y = 15; y >= 0; y--) {
                        setNibble(lightData[0], x, y, z, level);
                    }
                }
            }
        }

        // Phase 2: BFS horizontal propagation
        final IntQueue queue = new IntQueue();

        // Inject neighbor cached sky light at borders
        injectNeighborLight(lightData, sections, neighborCachedSkyLight, queue, mappings);

        // Seed the queue from internal positions
        for (int sIdx = 0; sIdx < sectionCount; sIdx++) {
            final byte[] sectionLight = lightData[sIdx + 1];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int level = getNibble(sectionLight, x, y, z);
                        if (level <= 1) continue;

                        if (shouldEnqueue(lightData, sIdx, x, y, z, level)) {
                            queue.enqueue(encodeLightPos(sIdx, x, y, z));
                        }
                    }
                }
            }
        }

        // BFS propagation
        spreadLight(lightData, sections, neighborSections, queue, mappings);

        return lightData;
    }

    private static boolean shouldEnqueue(final byte[][] lightData, final int sIdx, final int x, final int y, final int z, final int level) {
        for (int[] off : SHOULD_ENQUEUE_OFFSETS) {
            final int nx = x + off[0];
            final int nz = z + off[2];
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
            final int neighborLevel = getNibble(lightData[sIdx + 1], nx, y, nz);
            if (neighborLevel < level - 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inject neighbor cached light values at chunk borders as BFS seeds.
     * neighborCachedLight: [0]=-X, [1]=+X, [2]=-Z, [3]=+Z; elements may be null.
     * Each element is byte[][] with the same lightSectionCount layout as lightData.
     */
    private static void injectNeighborLight(final byte[][] lightData, final ChunkSection[] sections,
            final byte[][][] neighborCachedLight, final IntQueue queue, final BedrockMappingData mappings) {
        if (neighborCachedLight == null) return;
        final int sectionCount = sections.length;

        // Direction definitions: [neighborIdx, neighborBorderCoord, targetBorderCoord, isXAxis]
        // -X: neighbor's x=15 → our x=0;  +X: neighbor's x=0 → our x=15
        // -Z: neighbor's z=15 → our z=0;  +Z: neighbor's z=0 → our z=15
        final int[][] directions = {
            {0, 15, 0, 1},   // -X
            {1, 0, 15, 1},   // +X
            {2, 15, 0, 0},   // -Z
            {3, 0, 15, 0},   // +Z
        };

        for (int[] dir : directions) {
            final int neighborIdx = dir[0];
            final int neighborBorder = dir[1];
            final int targetBorder = dir[2];
            final boolean isXAxis = dir[3] == 1;

            if (neighborCachedLight[neighborIdx] == null) continue;

            for (int sIdx = 0; sIdx < sectionCount; sIdx++) {
                final byte[] neighborLight = neighborCachedLight[neighborIdx][sIdx + 1]; // +1 for below-bottom offset
                if (neighborLight == null) continue;

                final DataPalette currentPalette = sections[sIdx].palette(PaletteType.BLOCKS);

                for (int a = 0; a < 16; a++) {
                    for (int y = 0; y < 16; y++) {
                        final int nBlockX = isXAxis ? neighborBorder : a;
                        final int nBlockZ = isXAxis ? a : neighborBorder;
                        final int tBlockX = isXAxis ? targetBorder : a;
                        final int tBlockZ = isXAxis ? a : targetBorder;

                        final int neighborLightValue = getNibble(neighborLight, nBlockX, y, nBlockZ);
                        if (neighborLightValue <= 1) continue;

                        final int currentBlockState = currentPalette.idAt(tBlockX, y, tBlockZ);
                        final int filter = mappings.getFilterLight(currentBlockState);
                        final int newLevel = neighborLightValue - Math.max(1, filter);
                        if (newLevel <= 0) continue;

                        if (lightData[sIdx + 1] == null) {
                            lightData[sIdx + 1] = new byte[ChunkSectionLight.LIGHT_LENGTH];
                        }
                        final int current = getNibble(lightData[sIdx + 1], tBlockX, y, tBlockZ);
                        if (newLevel > current) {
                            setNibble(lightData[sIdx + 1], tBlockX, y, tBlockZ, newLevel);
                            queue.enqueue(encodeLightPos(sIdx, tBlockX, y, tBlockZ));
                        }
                    }
                }
            }
        }
    }

    /**
     * BFS light propagation shared by both sky light and block light.
     * Spreads light within the current chunk. When hitting chunk borders,
     * reads neighbor filterLight to correctly attenuate but does NOT write to neighbor light data.
     */
    private static void spreadLight(final byte[][] lightData, final ChunkSection[] sections,
            final ChunkSection[][] neighborSections, final IntQueue queue, final BedrockMappingData mappings) {
        final int sectionCount = sections.length;

        while (!queue.isEmpty()) {
            final int encoded = queue.dequeue();
            final int sIdx = (encoded >> 12) & 0xFF;
            final int x = encoded & 0xF;
            final int y = (encoded >> 4) & 0xF;
            final int z = (encoded >> 8) & 0xF;
            final int level = getNibble(lightData[sIdx + 1], x, y, z);
            if (level <= 1) continue;

            for (int[] dir : SPREAD_DIRS) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                int nz = z + dir[2];
                int nSIdx = sIdx;

                // Handle vertical section boundary
                if (ny < 0) {
                    nSIdx--;
                    ny = 15;
                } else if (ny > 15) {
                    nSIdx++;
                    ny = 0;
                }

                if (nSIdx < 0 || nSIdx >= sectionCount) continue;

                // Handle horizontal chunk boundary
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    // Light exits the chunk — don't write, but this is fine.
                    // Neighbor updates will be handled by updateNeighborLight().
                    continue;
                }

                final int blockState = sections[nSIdx].palette(PaletteType.BLOCKS).idAt(nx, ny, nz);
                final int filter = mappings.getFilterLight(blockState);
                final int newLevel = level - Math.max(1, filter);
                if (newLevel <= 0) continue;

                if (lightData[nSIdx + 1] == null) {
                    lightData[nSIdx + 1] = new byte[ChunkSectionLight.LIGHT_LENGTH];
                }

                final int current = getNibble(lightData[nSIdx + 1], nx, ny, nz);
                if (newLevel > current) {
                    setNibble(lightData[nSIdx + 1], nx, ny, nz, newLevel);
                    queue.enqueue(encodeLightPos(nSIdx, nx, ny, nz));
                }
            }
        }
    }

    // neighborSections: [0]=-X, [1]=+X, [2]=-Z, [3]=+Z; elements may be null
    // neighborCachedBlockLight: cached block light from already-sent neighbors; elements may be null
    private static byte[][] computeBlockLight(final ChunkSection[] sections, final ChunkSection[][] neighborSections, final byte[][][] neighborCachedBlockLight) {
        final int sectionCount = sections.length;
        final int lightSectionCount = sectionCount + 2;
        final BedrockMappingData mappings = BedrockProtocol.MAPPINGS;

        // null means empty (no block light); only allocate sections that have light sources
        final byte[][] lightData = new byte[lightSectionCount][];

        // Phase 1: Find all light-emitting blocks in this chunk
        final IntQueue queue = new IntQueue();

        for (int sIdx = 0; sIdx < sectionCount; sIdx++) {
            final DataPalette blockPalette = sections[sIdx].palette(PaletteType.BLOCKS);

            boolean hasEmitter = false;
            for (int i = 0; i < blockPalette.size(); i++) {
                if (mappings.getEmitLight(blockPalette.idByIndex(i)) > 0) {
                    hasEmitter = true;
                    break;
                }
            }
            if (!hasEmitter) continue;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int blockState = blockPalette.idAt(x, y, z);
                        final int emission = mappings.getEmitLight(blockState);
                        if (emission > 0) {
                            if (lightData[sIdx + 1] == null) {
                                lightData[sIdx + 1] = new byte[ChunkSectionLight.LIGHT_LENGTH];
                            }
                            setNibble(lightData[sIdx + 1], x, y, z, emission);
                            queue.enqueue(encodeLightPos(sIdx, x, y, z));
                        }
                    }
                }
            }
        }

        // Inject neighbor cached block light at borders
        injectNeighborLight(lightData, sections, neighborCachedBlockLight, queue, mappings);

        if (queue.isEmpty()) {
            return lightData;
        }

        // Phase 2: BFS propagation (shared with sky light)
        spreadLight(lightData, sections, neighborSections, queue, mappings);

        return lightData;
    }

    // --- Neighbor light management ---

    private ChunkSection[][] collectNeighborSections(final int chunkX, final int chunkZ) {
        final ChunkSection[][] neighborSections = new ChunkSection[4][];
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            final long neighborKey = ChunkPosition.chunkKey(nx, nz);
            neighborSections[i] = this.cachedSections.get(neighborKey);
        }
        return neighborSections;
    }

    private byte[][][] collectNeighborCachedSkyLight(final int chunkX, final int chunkZ) {
        final byte[][][] result = new byte[4][][];
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            result[i] = this.cachedSkyLight.get(ChunkPosition.chunkKey(nx, nz));
        }
        return result;
    }

    private byte[][][] collectNeighborCachedBlockLight(final int chunkX, final int chunkZ) {
        final byte[][][] result = new byte[4][][];
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            result[i] = this.cachedBlockLight.get(ChunkPosition.chunkKey(nx, nz));
        }
        return result;
    }

    /**
     * Mark already-sent neighbors as needing a light update. The actual re-computation
     * is deferred to tick() where updates are de-duplicated and batched.
     */
    private void markNeighborLightDirty(final int chunkX, final int chunkZ) {
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            final long neighborKey = ChunkPosition.chunkKey(nx, nz);
            if (this.sentChunks.contains(neighborKey) && this.cachedSections.containsKey(neighborKey)) {
                this.pendingLightUpdates.add(neighborKey);
            }
        }
    }

    /**
     * Re-compute light for a single already-sent neighbor chunk and send LIGHT_UPDATE if changed.
     */
    private void updateSingleNeighborLight(final long neighborKey) {
        final ChunkSection[] neighborSections = this.cachedSections.get(neighborKey);
        if (neighborSections == null) return;
        if (this.pendingAsyncLight.contains(neighborKey)) return; // async already pending

        final ChunkPosition pos = new ChunkPosition(neighborKey);
        final int nx = pos.chunkX();
        final int nz = pos.chunkZ();

        final ChunkSection[][] neighborsOfNeighbor = collectNeighborSections(nx, nz);
        final byte[][][] neighborSkyLightNeighbors = collectNeighborCachedSkyLight(nx, nz);
        final byte[][][] neighborBlockLightNeighbors = collectNeighborCachedBlockLight(nx, nz);

        final long lightCacheKey = computeCombinedLightCacheKey(neighborSections, neighborsOfNeighbor, neighborSkyLightNeighbors, neighborBlockLightNeighbors);
        final GlobalLightCache.LightCacheEntry cached = GlobalLightCache.getInstance().get(lightCacheKey);

        if (cached != null) {
            // Cache hit: synchronous compare and update
            final byte[][] oldSkyLight = this.cachedSkyLight.get(neighborKey);
            final byte[][] oldBlockLight = this.cachedBlockLight.get(neighborKey);
            if (hasLightChanged(oldSkyLight, cached.skyLight()) || hasLightChanged(oldBlockLight, cached.blockLight())) {
                this.cachedSkyLight.put(neighborKey, cached.skyLight());
                this.cachedBlockLight.put(neighborKey, cached.blockLight());
                this.sendLightUpdate(nx, nz, cached.skyLight(), cached.blockLight());
            }
        } else {
            // Cache miss: async computation
            this.pendingAsyncLight.add(neighborKey);
            GlobalLightCache.getInstance().submitAsync(() -> {
                final byte[][] newSkyLight = computeSkyLight(neighborSections, neighborsOfNeighbor, neighborSkyLightNeighbors);
                final byte[][] newBlockLight = computeBlockLight(neighborSections, neighborsOfNeighbor, neighborBlockLightNeighbors);
                GlobalLightCache.getInstance().put(lightCacheKey, newSkyLight, newBlockLight);

                user.getChannel().eventLoop().execute(() -> {
                    if (!user.getChannel().isActive()) return;
                    if (!this.sentChunks.contains(neighborKey)) return;
                    if (this.cachedSections.get(neighborKey) != neighborSections) return; // chunk was overwritten
                    this.pendingAsyncLight.remove(neighborKey);
                    final byte[][] oldSky = this.cachedSkyLight.get(neighborKey);
                    final byte[][] oldBlock = this.cachedBlockLight.get(neighborKey);
                    if (hasLightChanged(oldSky, newSkyLight) || hasLightChanged(oldBlock, newBlockLight)) {
                        this.cachedSkyLight.put(neighborKey, newSkyLight);
                        this.cachedBlockLight.put(neighborKey, newBlockLight);
                        this.sendLightUpdate(nx, nz, newSkyLight, newBlockLight);
                        this.markNeighborLightDirty(nx, nz);
                    }
                });
            });
        }
    }

    // --- Packet sending ---

    /**
     * Send a LIGHT_UPDATE packet to update a chunk's light without resending block data.
     */
    private void sendLightUpdate(final int chunkX, final int chunkZ, final byte[][] skyLight, final byte[][] blockLight) {
        final int lightSectionCount = skyLight.length;

        final BitSet skyLightMask = new BitSet();
        final BitSet blockLightMask = new BitSet();
        final BitSet emptySkyLightMask = new BitSet();
        final BitSet emptyBlockLightMask = new BitSet();

        final List<byte[]> skyLightArrays = new ArrayList<>();
        final List<byte[]> blockLightArrays = new ArrayList<>();

        for (int i = 0; i < lightSectionCount; i++) {
            skyLightMask.set(i);
            skyLightArrays.add(skyLight[i]);

            if (blockLight[i] != null) {
                blockLightMask.set(i);
                blockLightArrays.add(blockLight[i]);
            } else {
                emptyBlockLightMask.set(i);
            }
        }

        final PacketWrapper lightUpdate = PacketWrapper.create(ClientboundPackets1_21_11.LIGHT_UPDATE, this.user);
        lightUpdate.write(Types.VAR_INT, chunkX); // chunk x
        lightUpdate.write(Types.VAR_INT, chunkZ); // chunk z
        lightUpdate.write(Types.LONG_ARRAY_PRIMITIVE, skyLightMask.toLongArray()); // sky light mask
        lightUpdate.write(Types.LONG_ARRAY_PRIMITIVE, blockLightMask.toLongArray()); // block light mask
        lightUpdate.write(Types.LONG_ARRAY_PRIMITIVE, emptySkyLightMask.toLongArray()); // empty sky light mask
        lightUpdate.write(Types.LONG_ARRAY_PRIMITIVE, emptyBlockLightMask.toLongArray()); // empty block light mask
        lightUpdate.write(Types.VAR_INT, skyLightArrays.size()); // sky light length
        for (byte[] array : skyLightArrays) {
            lightUpdate.write(Types.BYTE_ARRAY_PRIMITIVE, array); // sky light
        }
        lightUpdate.write(Types.VAR_INT, blockLightArrays.size()); // block light length
        for (byte[] array : blockLightArrays) {
            lightUpdate.write(Types.BYTE_ARRAY_PRIMITIVE, array); // block light
        }
        lightUpdate.send(BedrockProtocol.class);
    }

    // --- Utility methods ---

    private static int encodeLightPos(final int sectionIdx, final int x, final int y, final int z) {
        return (sectionIdx << 12) | (z << 8) | (y << 4) | x;
    }

    private static int getNibble(final byte[] lightArray, final int x, final int y, final int z) {
        final int index = (y << 8) | (z << 4) | x;
        final int byteIndex = index >> 1;
        if ((index & 1) == 0) {
            return lightArray[byteIndex] & 0xF;
        } else {
            return (lightArray[byteIndex] >> 4) & 0xF;
        }
    }

    private static void setNibble(final byte[] lightArray, final int x, final int y, final int z, final int value) {
        final int index = (y << 8) | (z << 4) | x;
        final int byteIndex = index >> 1;
        if ((index & 1) == 0) {
            lightArray[byteIndex] = (byte) ((lightArray[byteIndex] & 0xF0) | (value & 0xF));
        } else {
            lightArray[byteIndex] = (byte) ((lightArray[byteIndex] & 0x0F) | ((value & 0xF) << 4));
        }
    }

    private static boolean hasLightChanged(final byte[][] oldLight, final byte[][] newLight) {
        if (oldLight == null || newLight == null) return oldLight != newLight;
        if (oldLight.length != newLight.length) return true;
        for (int i = 0; i < oldLight.length; i++) {
            if (oldLight[i] == null && newLight[i] == null) continue;
            if (oldLight[i] == null || newLight[i] == null) return true;
            if (!Arrays.equals(oldLight[i], newLight[i])) return true;
        }
        return false;
    }

    // --- Hash functions for light cache ---

    private static long computeCombinedLightCacheKey(
            final ChunkSection[] sections,
            final ChunkSection[][] neighborSections,
            final byte[][][] neighborCachedSkyLight,
            final byte[][][] neighborCachedBlockLight) {
        long hash = 0xcbf29ce484222325L; // FNV-1a offset basis
        hash = hashSections(hash, sections);
        if (neighborSections != null) {
            for (final ChunkSection[] ns : neighborSections) {
                hash = (ns != null) ? hashSections(hash, ns) : fnv1a(hash, 0);
            }
        }
        hash = hashNeighborLight(hash, neighborCachedSkyLight);
        hash = hashNeighborLight(hash, neighborCachedBlockLight);
        return hash;
    }

    private static long hashSections(long hash, final ChunkSection[] sections) {
        hash = fnv1a(hash, sections.length);
        for (final ChunkSection section : sections) {
            if (section == null) {
                hash = fnv1a(hash, 0);
                continue;
            }
            final DataPalette palette = section.palette(PaletteType.BLOCKS);
            if (palette.size() == 1) {
                hash = fnv1a(hash, palette.idByIndex(0));
            } else {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            hash = fnv1a(hash, palette.idAt(x, y, z));
                        }
                    }
                }
            }
        }
        return hash;
    }

    private static long hashNeighborLight(long hash, final byte[][][] neighborLight) {
        if (neighborLight == null) return fnv1a(hash, 0);
        for (final byte[][] nl : neighborLight) {
            hash = hashLightData(hash, nl);
        }
        return hash;
    }

    private static long hashLightData(long hash, final byte[][] lightData) {
        if (lightData == null) return fnv1a(hash, 0);
        for (final byte[] section : lightData) {
            if (section == null) {
                hash = fnv1a(hash, 0);
                continue;
            }
            hash = fnv1a(hash, Arrays.hashCode(section));
        }
        return hash;
    }

    private static long fnv1a(long hash, final int value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    // --- IntQueue ---

    private static final class IntQueue {
        private int[] data;
        private int head, tail;

        IntQueue() {
            this.data = new int[1024];
        }

        void enqueue(int value) {
            if (tail == data.length) {
                if (head > data.length / 4) {
                    System.arraycopy(data, head, data, 0, tail - head);
                    tail -= head;
                    head = 0;
                } else {
                    data = Arrays.copyOf(data, data.length * 2);
                }
            }
            data[tail++] = value;
        }

        int dequeue() {
            return data[head++];
        }

        boolean isEmpty() {
            return head >= tail;
        }
    }

}
