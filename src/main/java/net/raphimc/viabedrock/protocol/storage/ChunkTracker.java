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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.ChunkPosition;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntityImpl;
import com.viaversion.viaversion.api.minecraft.chunks.*;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.chunk.ChunkType1_21_5;
import com.viaversion.viaversion.libs.fastutil.ints.*;
import com.viaversion.viaversion.protocols.v1_21_9to1_21_11.packet.ClientboundPackets1_21_11;
import com.viaversion.viaversion.util.CompactArrayUtil;
import com.viaversion.viaversion.util.MathUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.chunk.BedrockChunk;
import net.raphimc.viabedrock.api.chunk.BlockEntityWithBlockState;
import net.raphimc.viabedrock.api.chunk.datapalette.BedrockBlockArray;
import net.raphimc.viabedrock.api.chunk.datapalette.BedrockDataPalette;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSection;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSectionImpl;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.HeightmapType;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.data.generated.java.RegistryKeys;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockEntityRewriter;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

import net.raphimc.viabedrock.protocol.data.BedrockMappingData;

// TODO: Feature: Block connections
public class ChunkTracker extends StoredObject {

    private static final byte[] FULL_LIGHT = new byte[ChunkSectionLight.LIGHT_LENGTH];

    static {
        Arrays.fill(FULL_LIGHT, (byte) 0xFF);
    }

    private final Dimension dimension;
    private final int minY;
    private final int worldHeight;
    private final Type<Chunk> chunkType;

    private final Map<Long, BedrockChunk> chunks = new HashMap<>();
    private final Set<Long> dirtyChunks = new HashSet<>();
    private final Set<Long> sentChunks = new HashSet<>();
    private final Map<Long, ChunkSection[]> cachedSections = new HashMap<>();
    private final Map<Long, byte[][]> cachedSkyLight = new HashMap<>();
    private final Map<Long, byte[][]> cachedBlockLight = new HashMap<>();
    private final Set<Long> pendingLightUpdates = new HashSet<>();

    private final Set<SubChunkPosition> subChunkRequests = new HashSet<>();
    private final Set<SubChunkPosition> pendingSubChunks = new HashSet<>();

    private int centerX = 0;
    private int centerZ = 0;
    private int radius;

    public ChunkTracker(final UserConnection user, final Dimension dimension) {
        super(user);
        this.dimension = dimension;

        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        final CompoundTag registries = gameSession.getJavaRegistries();
        final String dimensionKey = this.dimension.getKey();
        final CompoundTag dimensionRegistry = registries.getCompoundTag(RegistryKeys.DIMENSION_TYPE);
        final CompoundTag biomeRegistry = registries.getCompoundTag(RegistryKeys.WORLDGEN_BIOME);
        final CompoundTag dimensionTag = dimensionRegistry.getCompoundTag(dimensionKey);
        this.minY = dimensionTag.getNumberTag("min_y").asInt();
        this.worldHeight = dimensionTag.getNumberTag("height").asInt();
        this.chunkType = new ChunkType1_21_5(this.worldHeight >> 4, MathUtil.ceilLog2(BedrockProtocol.MAPPINGS.getJavaBlockStates().size()), MathUtil.ceilLog2(biomeRegistry.size()));

        final ChunkTracker oldChunkTracker = user.get(ChunkTracker.class);
        this.radius = oldChunkTracker != null ? oldChunkTracker.radius : user.get(ClientSettingsStorage.class).viewDistance();
    }

    public void setCenter(final int x, final int z) {
        this.centerX = x;
        this.centerZ = z;
        this.removeOutOfLoadDistanceChunks();
    }

    public void setRadius(final int radius) {
        this.radius = radius;
        this.removeOutOfLoadDistanceChunks();
    }

    public BedrockChunk createChunk(final int chunkX, final int chunkZ, final int nonNullSectionCount) {
        if (!this.isInLoadDistance(chunkX, chunkZ)) return null;
        if (!this.isInRenderDistance(chunkX, chunkZ)) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received chunk outside of render distance, but within load distance: " + chunkX + ", " + chunkZ);
            final EntityTracker entityTracker = this.user().get(EntityTracker.class);
            final PacketWrapper setChunkCacheCenter = PacketWrapper.create(ClientboundPackets1_21_11.SET_CHUNK_CACHE_CENTER, this.user());
            setChunkCacheCenter.write(Types.VAR_INT, (int) Math.floor(entityTracker.getClientPlayer().position().x()) >> 4); // chunk x
            setChunkCacheCenter.write(Types.VAR_INT, (int) Math.floor(entityTracker.getClientPlayer().position().z()) >> 4); // chunk z
            setChunkCacheCenter.send(BedrockProtocol.class);
        }

        final BedrockChunk chunk = new BedrockChunk(chunkX, chunkZ, new BedrockChunkSection[this.worldHeight >> 4]);
        for (int i = 0; i < nonNullSectionCount && i < chunk.getSections().length; i++) {
            chunk.getSections()[i] = new BedrockChunkSectionImpl();
        }
        for (int i = 0; i < chunk.getSections().length; i++) {
            if (chunk.getSections()[i] == null) {
                chunk.getSections()[i] = new BedrockChunkSectionImpl(true);
            }
        }
        this.chunks.put(ChunkPosition.chunkKey(chunk.getX(), chunk.getZ()), chunk);
        return chunk;
    }

    public void unloadChunk(final ChunkPosition chunkPos) {
        final long key = chunkPos.chunkKey();
        this.chunks.remove(key);
        this.sentChunks.remove(key);
        this.cachedSections.remove(key);
        this.cachedSkyLight.remove(key);
        this.cachedBlockLight.remove(key);
        this.pendingLightUpdates.remove(key);
        this.user().get(EntityTracker.class).removeItemFrame(chunkPos);

        final PacketWrapper unloadChunk = PacketWrapper.create(ClientboundPackets1_21_11.FORGET_LEVEL_CHUNK, this.user());
        unloadChunk.write(Types.CHUNK_POSITION, chunkPos); // chunk position
        unloadChunk.send(BedrockProtocol.class);
    }

    public BedrockChunk getChunk(final int chunkX, final int chunkZ) {
        if (!this.isInLoadDistance(chunkX, chunkZ)) return null;
        return this.chunks.get(ChunkPosition.chunkKey(chunkX, chunkZ));
    }

    public BedrockChunkSection getChunkSection(final int chunkX, final int subChunkY, final int chunkZ) {
        final BedrockChunk chunk = this.getChunk(chunkX, chunkZ);
        if (chunk == null) return null;

        final int sectionIndex = subChunkY + Math.abs(this.minY >> 4);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return null;

        return chunk.getSections()[sectionIndex];
    }

    public BedrockChunkSection getChunkSection(final BlockPosition blockPosition) {
        return this.getChunkSection(blockPosition.x() >> 4, blockPosition.y() >> 4, blockPosition.z() >> 4);
    }

    public int getBlockState(final BlockPosition blockPosition) {
        return this.getBlockState(0, blockPosition);
    }

    public int getBlockState(final int layer, final BlockPosition blockPosition) {
        final BedrockChunkSection chunkSection = this.getChunkSection(blockPosition);
        if (chunkSection == null) return this.airId();
        if (chunkSection.palettesCount(PaletteType.BLOCKS) <= layer) return this.airId();
        return chunkSection.palettes(PaletteType.BLOCKS).get(layer).idAt(blockPosition.x() & 15, blockPosition.y() & 15, blockPosition.z() & 15);
    }

    public int getJavaBlockState(final BlockPosition blockPosition) {
        final BedrockChunkSection chunkSection = this.getChunkSection(blockPosition);
        if (chunkSection == null) return 0;

        final int sectionX = blockPosition.x() & 15;
        final int sectionY = blockPosition.y() & 15;
        final int sectionZ = blockPosition.z() & 15;

        return this.getJavaBlockState(chunkSection, sectionX, sectionY, sectionZ);
    }

    public int getJavaBlockState(final BedrockChunkSection section, final int sectionX, final int sectionY, final int sectionZ) {
        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
        final List<DataPalette> blockPalettes = section.palettes(PaletteType.BLOCKS);

        final int layer0BlockState = blockPalettes.get(0).idAt(sectionX, sectionY, sectionZ);
        int remappedBlockState = blockStateRewriter.javaId(layer0BlockState);
        if (remappedBlockState == -1) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing block state: " + layer0BlockState);
            remappedBlockState = 0;
        }

        if (blockPalettes.size() > 1) {
            final int layer1BlockState = blockPalettes.get(1).idAt(sectionX, sectionY, sectionZ);
            if (CustomBlockTags.WATER.equals(blockStateRewriter.tag(layer1BlockState))) { // Waterlogging
                final int prevBlockState = remappedBlockState;
                remappedBlockState = blockStateRewriter.waterlog(remappedBlockState);
                if (remappedBlockState == -1) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing waterlogged block state: " + prevBlockState);
                    remappedBlockState = prevBlockState;
                }
            }
        }

        return remappedBlockState;
    }

    public BedrockBlockEntity getBlockEntity(final BlockPosition blockPosition) {
        final BedrockChunk chunk = this.getChunk(blockPosition.x() >> 4, blockPosition.z() >> 4);
        if (chunk == null) return null;
        return chunk.getBlockEntityAt(blockPosition);
    }

    public void addBlockEntity(final BedrockBlockEntity bedrockBlockEntity) {
        final BedrockChunk chunk = this.getChunk(bedrockBlockEntity.position().x() >> 4, bedrockBlockEntity.position().z() >> 4);
        if (chunk == null) return;

        chunk.removeBlockEntityAt(bedrockBlockEntity.position());
        chunk.blockEntities().add(bedrockBlockEntity);
    }

    public boolean isChunkLoaded(final ChunkPosition chunkPos) {
        if (!this.isInLoadDistance(chunkPos.chunkX(), chunkPos.chunkZ())) return false;
        return this.chunks.containsKey(chunkPos.chunkKey());
    }

    public boolean isInUnloadedChunkSection(final Position3f playerPosition) {
        final BlockPosition chunkSectionPosition = new BlockPosition((int) Math.floor(playerPosition.x()) >> 4, (int) Math.floor((playerPosition.y() - 1.62F)) >> 4, (int) Math.floor(playerPosition.z()) >> 4);
        final ChunkPosition chunkPos = new ChunkPosition(chunkSectionPosition.x(), chunkSectionPosition.z());
        if (!this.isChunkLoaded(chunkPos)) {
            return true;
        }
        final BedrockChunkSection chunkSection = this.getChunkSection(chunkSectionPosition.x(), chunkSectionPosition.y(), chunkSectionPosition.z());
        if (chunkSection == null) {
            return false;
        }
        if (chunkSection.hasPendingBlockUpdates()) {
            return true;
        }
        return this.dirtyChunks.contains(chunkPos.chunkKey());
    }

    public boolean isInLoadDistance(final int chunkX, final int chunkZ) {
        if (!this.isInRenderDistance(chunkX, chunkZ)) { // Bedrock accepts chunks outside the chunk render range and uses the player position as a center to determine if a chunk is allowed to be loaded
            final EntityTracker entityTracker = this.user().get(EntityTracker.class);
            if (entityTracker == null) return false;
            final int centerX = (int) Math.floor(entityTracker.getClientPlayer().position().x()) >> 4;
            final int centerZ = (int) Math.floor(entityTracker.getClientPlayer().position().z()) >> 4;
            return Math.abs(chunkX - centerX) <= this.radius && Math.abs(chunkZ - centerZ) <= this.radius;
        }

        return true;
    }

    public boolean isInRenderDistance(final int chunkX, final int chunkZ) {
        return Math.abs(chunkX - this.centerX) <= this.radius && Math.abs(chunkZ - this.centerZ) <= this.radius;
    }

    public void removeOutOfLoadDistanceChunks() {
        final Set<ChunkPosition> chunksToRemove = new HashSet<>();
        for (long chunkKey : this.chunks.keySet()) {
            final ChunkPosition chunkPos = new ChunkPosition(chunkKey);
            if (this.isInLoadDistance(chunkPos.chunkX(), chunkPos.chunkZ())) continue;

            chunksToRemove.add(chunkPos);
        }
        for (ChunkPosition chunkPos : chunksToRemove) {
            this.unloadChunk(chunkPos);
        }
    }

    public void requestSubChunks(final int chunkX, final int chunkZ, final int from, final int to) {
        for (int i = from; i < to; i++) {
            this.requestSubChunk(chunkX, i, chunkZ);
        }
    }

    public void requestSubChunk(final int chunkX, final int subChunkY, final int chunkZ) {
        if (!this.isInLoadDistance(chunkX, chunkZ)) return;
        this.subChunkRequests.add(new SubChunkPosition(chunkX, subChunkY, chunkZ));
    }

    public boolean mergeSubChunk(final int chunkX, final int subChunkY, final int chunkZ, final BedrockChunkSection other, final List<BedrockBlockEntity> blockEntities) {
        if (!this.isInLoadDistance(chunkX, chunkZ)) return false;

        final SubChunkPosition position = new SubChunkPosition(chunkX, subChunkY, chunkZ);
        if (!this.pendingSubChunks.contains(position)) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received sub chunk that was not requested: " + position);
            return false;
        }
        this.pendingSubChunks.remove(position);

        final BedrockChunk chunk = this.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received sub chunk for unloaded chunk: " + position);
            return false;
        }

        final BedrockChunkSection section = chunk.getSections()[subChunkY + Math.abs(this.minY >> 4)];
        section.mergeWith(this.handleBlockPalette(other));
        section.applyPendingBlockUpdates(this.airId());
        blockEntities.forEach(blockEntity -> chunk.removeBlockEntityAt(blockEntity.position()));
        chunk.blockEntities().addAll(blockEntities);
        return true;
    }

    public IntObjectPair<BlockEntity> handleBlockChange(final BlockPosition blockPosition, final int layer, final int blockState) {
        final BedrockChunkSection section = this.getChunkSection(blockPosition);
        if (section == null) {
            return null;
        }

        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        final int sectionX = blockPosition.x() & 15;
        final int sectionY = blockPosition.y() & 15;
        final int sectionZ = blockPosition.z() & 15;

        if (section.hasPendingBlockUpdates()) {
            section.addPendingBlockUpdate(sectionX, sectionY, sectionZ, layer, blockState);
            return null;
        }

        while (section.palettesCount(PaletteType.BLOCKS) <= layer) {
            final BedrockDataPalette palette = new BedrockDataPalette();
            palette.addId(this.airId());
            section.addPalette(PaletteType.BLOCKS, palette);
        }
        final DataPalette palette = section.palettes(PaletteType.BLOCKS).get(layer);
        final int prevBlockState = palette.idAt(sectionX, sectionY, sectionZ);
        final String prevTag = blockStateRewriter.tag(prevBlockState);
        final String tag = blockStateRewriter.tag(blockState);
        palette.setIdAt(sectionX, sectionY, sectionZ, blockState);

        int remappedBlockState = this.getJavaBlockState(section, sectionX, sectionY, sectionZ);
        if (!Objects.equals(prevTag, tag)) {
            this.getChunk(blockPosition.x() >> 4, blockPosition.z() >> 4).removeBlockEntityAt(blockPosition);
            entityTracker.removeItemFrame(blockPosition);
        }

        if (prevBlockState != blockState) {
            if (BlockEntityRewriter.isJavaBlockEntity(tag)) {
                final BedrockBlockEntity bedrockBlockEntity = this.getBlockEntity(blockPosition);
                BlockEntity javaBlockEntity = null;
                if (bedrockBlockEntity != null) {
                    javaBlockEntity = BlockEntityRewriter.toJava(this.user(), blockState, bedrockBlockEntity);
                    if (javaBlockEntity instanceof BlockEntityWithBlockState blockEntityWithBlockState) {
                        remappedBlockState = blockEntityWithBlockState.blockState();
                    }
                } else if (BedrockProtocol.MAPPINGS.getJavaBlockEntities().containsKey(tag)) {
                    final int javaType = BedrockProtocol.MAPPINGS.getJavaBlockEntities().get(tag);
                    javaBlockEntity = new BlockEntityImpl(BlockEntity.pack(sectionX, sectionZ), (short) blockPosition.y(), javaType, new CompoundTag());
                }

                if (javaBlockEntity != null && javaBlockEntity.tag() != null) {
                    return new IntObjectImmutablePair<>(remappedBlockState, javaBlockEntity);
                }
            } else if (CustomBlockTags.ITEM_FRAME.equals(tag)) {
                entityTracker.spawnItemFrame(blockPosition, blockStateRewriter.blockState(blockState));
            }
        }

        return new IntObjectImmutablePair<>(remappedBlockState, null);
    }

    public BedrockChunkSection handleBlockPalette(final BedrockChunkSection section) {
        this.replaceLegacyBlocks(section);
        this.resolvePersistentIds(section);
        return section;
    }

    public void sendChunkInNextTick(final int chunkX, final int chunkZ) {
        this.dirtyChunks.add(ChunkPosition.chunkKey(chunkX, chunkZ));
    }

    public void sendChunk(final int chunkX, final int chunkZ) {
        final BedrockChunk chunk = this.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }

        final Chunk remappedChunk = this.remapChunk(chunk);
        final ChunkSection[] sections = remappedChunk.getSections();
        final int sectionCount = sections.length;
        final int lightSectionCount = sectionCount + 2; // +1 below, +1 above
        final long chunkKey = ChunkPosition.chunkKey(chunkX, chunkZ);

        // Cache remapped sections for neighbor light calculations
        this.cachedSections.put(chunkKey, sections);

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
            skyLight = cached.skyLight();
            blockLight = cached.blockLight();
        } else {
            skyLight = this.computeSkyLight(sections, neighborSections, neighborCachedSkyLight);
            blockLight = this.computeBlockLight(sections, neighborSections, neighborCachedBlockLight);
            GlobalLightCache.getInstance().put(lightCacheKey, skyLight, blockLight);
        }

        // Cache computed light
        this.cachedSkyLight.put(chunkKey, skyLight);
        this.cachedBlockLight.put(chunkKey, blockLight);

        // Strip custom block data for vanilla clients (no FabricRock mod)
        if (!this.user().get(GameSessionStorage.class).hasFabricRock()) {
            this.stripCustomBlockData(remappedChunk);
        }

        // Build masks
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

        final PacketWrapper levelChunkWithLight = PacketWrapper.create(ClientboundPackets1_21_11.LEVEL_CHUNK_WITH_LIGHT, this.user());
        levelChunkWithLight.write(this.chunkType, remappedChunk); // chunk
        levelChunkWithLight.write(Types.LONG_ARRAY_PRIMITIVE, skyLightMask.toLongArray()); // sky light mask
        levelChunkWithLight.write(Types.LONG_ARRAY_PRIMITIVE, blockLightMask.toLongArray()); // block light mask
        levelChunkWithLight.write(Types.LONG_ARRAY_PRIMITIVE, emptySkyLightMask.toLongArray()); // empty sky light mask
        levelChunkWithLight.write(Types.LONG_ARRAY_PRIMITIVE, emptyBlockLightMask.toLongArray()); // empty block light mask
        levelChunkWithLight.write(Types.VAR_INT, skyLightArrays.size()); // sky light length
        for (byte[] array : skyLightArrays) {
            levelChunkWithLight.write(Types.BYTE_ARRAY_PRIMITIVE, array); // sky light
        }
        levelChunkWithLight.write(Types.VAR_INT, blockLightArrays.size()); // block light length
        for (byte[] array : blockLightArrays) {
            levelChunkWithLight.write(Types.BYTE_ARRAY_PRIMITIVE, array); // block light
        }
        levelChunkWithLight.send(BedrockProtocol.class);

        // Mark as sent and schedule neighbor light updates for next tick
        this.sentChunks.add(chunkKey);
        this.markNeighborLightDirty(chunkX, chunkZ);
    }

    private void stripCustomBlockData(final Chunk chunk) {
        final int vanillaBlockStateCount = BedrockProtocol.MAPPINGS.getVanillaBlockStateCount();
        final int vanillaBlockEntityCount = BedrockProtocol.MAPPINGS.getVanillaBlockEntityCount();

        // Replace custom block state IDs (>= vanillaBlockStateCount) with stone (ID 1)
        for (final ChunkSection section : chunk.getSections()) {
            if (section == null) continue;
            final DataPalette blockPalette = section.palette(PaletteType.BLOCKS);
            if (blockPalette == null) continue;
            for (int i = 0; i < blockPalette.size(); i++) {
                if (blockPalette.idByIndex(i) >= vanillaBlockStateCount) {
                    blockPalette.setIdByIndex(i, 1); // stone
                }
            }
        }

        // Remove custom block entities (typeId >= vanillaBlockEntityCount or typeId < 0)
        chunk.blockEntities().removeIf(be -> be.typeId() >= vanillaBlockEntityCount || be.typeId() < 0);
    }

    // neighborSections: [0]=-X, [1]=+X, [2]=-Z, [3]=+Z; elements may be null
    // neighborCachedSkyLight: cached sky light from already-sent neighbors; elements may be null
    private byte[][] computeSkyLight(final ChunkSection[] sections, final ChunkSection[][] neighborSections, final byte[][][] neighborCachedSkyLight) {
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

    private boolean shouldEnqueue(final byte[][] lightData, final int sIdx, final int x, final int y, final int z, final int level) {
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
    private void injectNeighborLight(final byte[][] lightData, final ChunkSection[] sections,
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
    private void spreadLight(final byte[][] lightData, final ChunkSection[] sections,
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
    private byte[][] computeBlockLight(final ChunkSection[] sections, final ChunkSection[][] neighborSections, final byte[][][] neighborCachedBlockLight) {
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

    private static final int[][] SHOULD_ENQUEUE_OFFSETS = {{-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final int[][] SPREAD_DIRS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final int[][] NEIGHBOR_OFFSETS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

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

    /**
     * Collect neighbor sections and cached light for the 4 horizontal neighbors.
     * Returns [neighborSections[4], neighborCachedSkyLight[4], neighborCachedBlockLight[4]].
     */
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

        final ChunkPosition pos = new ChunkPosition(neighborKey);
        final int nx = pos.chunkX();
        final int nz = pos.chunkZ();

        final ChunkSection[][] neighborsOfNeighbor = collectNeighborSections(nx, nz);
        final byte[][][] neighborSkyLightNeighbors = collectNeighborCachedSkyLight(nx, nz);
        final byte[][][] neighborBlockLightNeighbors = collectNeighborCachedBlockLight(nx, nz);

        // Try global light cache first
        final long lightCacheKey = computeCombinedLightCacheKey(neighborSections, neighborsOfNeighbor, neighborSkyLightNeighbors, neighborBlockLightNeighbors);
        final GlobalLightCache.LightCacheEntry cached = GlobalLightCache.getInstance().get(lightCacheKey);

        final byte[][] newSkyLight;
        final byte[][] newBlockLight;
        if (cached != null) {
            newSkyLight = cached.skyLight();
            newBlockLight = cached.blockLight();
        } else {
            newSkyLight = this.computeSkyLight(neighborSections, neighborsOfNeighbor, neighborSkyLightNeighbors);
            newBlockLight = this.computeBlockLight(neighborSections, neighborsOfNeighbor, neighborBlockLightNeighbors);
            GlobalLightCache.getInstance().put(lightCacheKey, newSkyLight, newBlockLight);
        }

        final byte[][] oldSkyLight = this.cachedSkyLight.get(neighborKey);
        final byte[][] oldBlockLight = this.cachedBlockLight.get(neighborKey);

        if (hasLightChanged(oldSkyLight, newSkyLight) || hasLightChanged(oldBlockLight, newBlockLight)) {
            this.cachedSkyLight.put(neighborKey, newSkyLight);
            this.cachedBlockLight.put(neighborKey, newBlockLight);
            this.sendLightUpdate(nx, nz, newSkyLight, newBlockLight);
        }
    }

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

        final PacketWrapper lightUpdate = PacketWrapper.create(ClientboundPackets1_21_11.LIGHT_UPDATE, this.user());
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

    public Dimension getDimension() {
        return this.dimension;
    }

    public int getMinY() {
        return this.minY;
    }

    public int getMaxY() {
        return this.worldHeight - Math.abs(this.minY);
    }

    public int getWorldHeight() {
        return this.worldHeight;
    }

    public int airId() {
        return this.user().get(BlockStateRewriter.class).bedrockId(BedrockBlockState.AIR);
    }

    public boolean isEmpty() {
        boolean empty = true;
        empty &= this.chunks.isEmpty();
        empty &= this.subChunkRequests.isEmpty() && this.pendingSubChunks.isEmpty();
        return empty;
    }

    private static final int MAX_CHUNKS_PER_TICK = 4;
    private static final int MAX_LIGHT_UPDATES_PER_TICK = 8;

    public void tick() {
        if (!this.dirtyChunks.isEmpty()) {
            int count = 0;
            final Iterator<Long> it = this.dirtyChunks.iterator();
            while (it.hasNext() && count < MAX_CHUNKS_PER_TICK) {
                final long dirtyChunk = it.next();
                it.remove();
                final ChunkPosition chunkPos = new ChunkPosition(dirtyChunk);
                this.sendChunk(chunkPos.chunkX(), chunkPos.chunkZ());
                count++;
            }
        }

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

        if (this.user().get(EntityTracker.class) == null || !this.user().get(EntityTracker.class).getClientPlayer().isInitiallySpawned()) {
            return;
        }

        this.subChunkRequests.removeIf(s -> !this.isInLoadDistance(s.chunkX, s.chunkZ));
        final BlockPosition basePosition = new BlockPosition(this.centerX, 0, this.centerZ);
        while (!this.subChunkRequests.isEmpty()) {
            final Set<SubChunkPosition> group = this.subChunkRequests.stream().limit(256).collect(Collectors.toSet());
            this.subChunkRequests.removeAll(group);
            this.pendingSubChunks.addAll(group);

            final PacketWrapper subChunkRequest = PacketWrapper.create(ServerboundBedrockPackets.SUB_CHUNK_REQUEST, this.user());
            subChunkRequest.write(BedrockTypes.VAR_INT, this.dimension.ordinal()); // dimension id
            subChunkRequest.write(BedrockTypes.POSITION_3I, basePosition); // base position
            subChunkRequest.write(BedrockTypes.INT_LE, group.size()); // sub chunk offset count
            for (SubChunkPosition subChunkPosition : group) {
                final BlockPosition offset = new BlockPosition(subChunkPosition.chunkX - basePosition.x(), subChunkPosition.subChunkY, subChunkPosition.chunkZ - basePosition.z());
                subChunkRequest.write(BedrockTypes.SUB_CHUNK_OFFSET, offset); // offset
            }
            subChunkRequest.sendToServer(BedrockProtocol.class);
        }
    }

    private Chunk remapChunk(final BedrockChunk chunk) {
        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
        final int airId = this.airId();

        final Chunk remappedChunk = new Chunk1_21_5(chunk.getX(), chunk.getZ(), new ChunkSection[chunk.getSections().length], new Heightmap[2], new ArrayList<>());

        final BedrockChunkSection[] bedrockSections = chunk.getSections();
        final ChunkSection[] remappedSections = remappedChunk.getSections();
        for (int idx = 0; idx < bedrockSections.length; idx++) {
            final BedrockChunkSection bedrockSection = bedrockSections[idx];
            final List<DataPalette> blockPalettes = bedrockSection.palettes(PaletteType.BLOCKS);
            final ChunkSection remappedSection = remappedSections[idx] = new ChunkSectionImpl(false);
            final DataPalette remappedBlockPalette = remappedSection.palette(PaletteType.BLOCKS);

            if (!blockPalettes.isEmpty()) {
                final DataPalette layer0 = blockPalettes.get(0);
                if (layer0.size() == 1) {
                    remappedBlockPalette.addId(layer0.idByIndex(0));
                } else {
                    this.transferPaletteData(layer0, remappedBlockPalette);
                }

                final String[] paletteIndexBlockStateTags = new String[remappedBlockPalette.size()];
                for (int i = 0; i < remappedBlockPalette.size(); i++) {
                    final int bedrockBlockState = remappedBlockPalette.idByIndex(i);
                    int javaBlockState = blockStateRewriter.javaId(bedrockBlockState);
                    if (javaBlockState == -1) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing block state: " + bedrockBlockState);
                        javaBlockState = 0;
                    }
                    remappedBlockPalette.setIdByIndex(i, javaBlockState);

                    paletteIndexBlockStateTags[i] = blockStateRewriter.tag(bedrockBlockState);
                }

                int nonAirBlockCount = 0;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = 0; y < 16; y++) {
                            final int paletteIndex = remappedBlockPalette.paletteIndexAt(remappedBlockPalette.index(x, y, z));
                            final int javaBlockState = remappedBlockPalette.idByIndex(paletteIndex);
                            if (javaBlockState != 0) {
                                nonAirBlockCount++;
                            }

                            final String tag = paletteIndexBlockStateTags[paletteIndex];
                            if (tag == null) continue;

                            final int absY = this.minY + idx * 16 + y;
                            final BlockPosition position = new BlockPosition(chunk.getX() * 16 + x, absY, chunk.getZ() * 16 + z);
                            if (BlockEntityRewriter.isJavaBlockEntity(tag)) {
                                final BedrockBlockEntity bedrockBlockEntity = chunk.getBlockEntityAt(position);
                                if (bedrockBlockEntity != null) {
                                    final BlockEntity javaBlockEntity = BlockEntityRewriter.toJava(this.user(), layer0.idAt(x, y, z), bedrockBlockEntity);
                                    if (javaBlockEntity instanceof BlockEntityWithBlockState blockEntityWithBlockState) {
                                        remappedBlockPalette.setIdAt(x, y, z, blockEntityWithBlockState.blockState());
                                    }
                                    if (javaBlockEntity != null && javaBlockEntity.tag() != null) {
                                        remappedChunk.blockEntities().add(javaBlockEntity);
                                    }
                                } else if (BedrockProtocol.MAPPINGS.getJavaBlockEntities().containsKey(tag)) {
                                    final int javaType = BedrockProtocol.MAPPINGS.getJavaBlockEntities().get(tag);
                                    final BlockEntity javaBlockEntity = new BlockEntityImpl(BlockEntity.pack(x, z), (short) absY, javaType, new CompoundTag());
                                    remappedChunk.blockEntities().add(javaBlockEntity);
                                }
                            } else if (CustomBlockTags.ITEM_FRAME.equals(tag)) {
                                this.user().get(EntityTracker.class).spawnItemFrame(position, blockStateRewriter.blockState(layer0.idAt(x, y, z)));
                            }
                        }
                    }
                }
                remappedSection.setNonAirBlocksCount(nonAirBlockCount);

                if (blockPalettes.size() > 1) {
                    final DataPalette layer1 = blockPalettes.get(1);
                    if (layer1.size() != 1 || layer1.idByIndex(0) != airId) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                for (int y = 0; y < 16; y++) {
                                    final int prevBlockState = layer0.idAt(x, y, z);
                                    if (prevBlockState == airId) continue;
                                    final int blockState = layer1.idAt(x, y, z);
                                    if (blockState == airId) continue;
                                    final int javaBlockState = remappedBlockPalette.idAt(x, y, z);

                                    if (CustomBlockTags.WATER.equals(blockStateRewriter.tag(blockState))) { // Waterlogging
                                        final int remappedBlockState = blockStateRewriter.waterlog(javaBlockState);
                                        if (remappedBlockState == -1) {
                                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing waterlogged block state: " + prevBlockState);
                                        } else {
                                            remappedBlockPalette.setIdAt(x, y, z, remappedBlockState);
                                        }
                                    } else {
                                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Invalid layer 2 block state. L1: " + prevBlockState + ", L2: " + blockState);
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                remappedBlockPalette.addId(0);
            }

            final DataPalette biomePalette = bedrockSection.palette(PaletteType.BIOMES);
            final DataPalette remappedBiomePalette = new DataPaletteImpl(ChunkSection.BIOME_SIZE);
            remappedSection.addPalette(PaletteType.BIOMES, remappedBiomePalette);

            if (biomePalette != null) {
                if (biomePalette.size() == 1) {
                    remappedBiomePalette.addId(biomePalette.idByIndex(0));
                } else {
                    for (int x = 0; x < 4; x++) {
                        for (int z = 0; z < 4; z++) {
                            for (int y = 0; y < 4; y++) {
                                final Int2IntMap subBiomes = new Int2IntOpenHashMap();
                                int maxBiomeId = -1;
                                int maxValue = -1;
                                for (int subX = 0; subX < 4; subX++) {
                                    for (int subZ = 0; subZ < 4; subZ++) {
                                        for (int subY = 0; subY < 4; subY++) {
                                            final int biomeId = biomePalette.idAt(x * 4 + subX, y * 4 + subY, z * 4 + subZ);
                                            final int value = subBiomes.getOrDefault(biomeId, 0) + 1;
                                            subBiomes.put(biomeId, value);
                                            if (value > maxValue) {
                                                maxBiomeId = biomeId;
                                                maxValue = value;
                                            }
                                        }
                                    }
                                }
                                remappedBiomePalette.setIdAt(x, y, z, maxBiomeId);
                            }
                        }
                    }
                }

                for (int i = 0; i < remappedBiomePalette.size(); i++) {
                    final int bedrockBiome = remappedBiomePalette.idByIndex(i);
                    final String bedrockBiomeName = BedrockProtocol.MAPPINGS.getBedrockBiomes().inverse().get(bedrockBiome);
                    final int javaBiome;
                    if (bedrockBiomeName == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing biome: " + bedrockBiome);
                        javaBiome = BedrockProtocol.MAPPINGS.getJavaBiomes().get("the_void");
                    } else {
                        javaBiome = BedrockProtocol.MAPPINGS.getJavaBiomes().get(bedrockBiomeName);
                    }
                    remappedBiomePalette.setIdByIndex(i, javaBiome);
                }
            } else {
                remappedBiomePalette.addId(0);
            }
        }

        final IntSet motionBlockingBlockStates = BedrockProtocol.MAPPINGS.getJavaHeightMapBlockStates().get("motion_blocking");
        final int[] worldSurface = new int[16 * 16];
        final int[] motionBlocking = new int[16 * 16];
        Arrays.fill(worldSurface, Integer.MIN_VALUE);
        Arrays.fill(motionBlocking, Integer.MIN_VALUE);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                final int index = z << 4 | x;
                FIND_Y:
                for (int idx = remappedSections.length - 1; idx >= 0; idx--) {
                    final DataPalette blockPalette = remappedSections[idx].palette(PaletteType.BLOCKS);
                    if (blockPalette.size() == 1 && blockPalette.idByIndex(0) == 0) continue;

                    for (int y = 15; y >= 0; y--) {
                        final int blockState = blockPalette.idAt(x, y, z);
                        if (blockState != 0) {
                            final int value = idx * 16 + y + 1;

                            if (worldSurface[index] == Integer.MIN_VALUE) {
                                worldSurface[index] = value;
                            }
                            if (motionBlocking[index] == Integer.MIN_VALUE && motionBlockingBlockStates.contains(blockState)) {
                                motionBlocking[index] = value;
                                break FIND_Y;
                            }
                        }
                    }
                }

                if (worldSurface[index] == Integer.MIN_VALUE) {
                    worldSurface[index] = this.minY;
                }
                if (motionBlocking[index] == Integer.MIN_VALUE) {
                    motionBlocking[index] = this.minY;
                }
            }
        }

        final int bitsPerEntry = MathUtil.ceilLog2(this.worldHeight + 1);
        remappedChunk.heightmaps()[0] = new Heightmap(HeightmapType.WORLD_SURFACE.ordinal(), CompactArrayUtil.createCompactArrayWithPadding(bitsPerEntry, worldSurface.length, i -> worldSurface[i]));
        remappedChunk.heightmaps()[1] = new Heightmap(HeightmapType.MOTION_BLOCKING.ordinal(), CompactArrayUtil.createCompactArrayWithPadding(bitsPerEntry, motionBlocking.length, i -> motionBlocking[i]));

        return remappedChunk;
    }

    private void resolvePersistentIds(final BedrockChunkSection bedrockSection) {
        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);

        final List<DataPalette> palettes = bedrockSection.palettes(PaletteType.BLOCKS);
        for (DataPalette palette : palettes) {
            if (palette instanceof BedrockDataPalette bedrockPalette) {
                if (bedrockPalette.usesPersistentIds()) {
                    bedrockPalette.addId(this.airId());
                    bedrockPalette.resolvePersistentIds(tag -> {
                        int remappedBlockState = blockStateRewriter.bedrockId((CompoundTag) tag);
                        if (remappedBlockState == -1) {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing block state: " + tag);
                            remappedBlockState = blockStateRewriter.bedrockId(BedrockBlockState.INFO_UPDATE);
                        }
                        return remappedBlockState;
                    });
                }
            }
        }
    }

    private void replaceLegacyBlocks(final BedrockChunkSection bedrockSection) {
        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);

        final List<DataPalette> palettes = bedrockSection.palettes(PaletteType.BLOCKS);
        for (DataPalette palette : palettes) {
            if (palette instanceof BedrockBlockArray blockArray) {
                final BedrockDataPalette dataPalette = new BedrockDataPalette();
                this.transferPaletteData(blockArray, dataPalette);
                for (int i = 0; i < dataPalette.size(); i++) {
                    final int blockState = dataPalette.idByIndex(i);
                    int remappedBlockState = blockStateRewriter.bedrockId(blockState);
                    if (remappedBlockState == -1) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing legacy block state: " + blockState);
                        remappedBlockState = this.airId();
                    }
                    dataPalette.setIdByIndex(i, remappedBlockState);
                }
                palettes.set(palettes.indexOf(palette), dataPalette);
            }
        }
    }

    /**
     * Transfers the palette data between two different palette types.
     *
     * @param source The source palette
     * @param target The target palette
     */
    private void transferPaletteData(final DataPalette source, final DataPalette target) {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    target.setIdAt(x, y, z, source.idAt(x, y, z));
                }
            }
        }
    }

    private record SubChunkPosition(int chunkX, int subChunkY, int chunkZ) {
    }

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
