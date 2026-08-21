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
package net.raphimc.viabedrock.experimental.block;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.ChunkPosition;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.Vector3f;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.objects.Object2IntMap;
import com.viaversion.viaversion.libs.fastutil.objects.Object2IntOpenHashMap;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomBlockTextureResourceRewriter;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Overlays converted custom-block item models onto unmapped Bedrock blocks.
 * Java cannot register new block states from a resource pack, so the world
 * keeps a collision placeholder while an item display shows the converted cube.
 */
public class CustomBlockDisplayTracker extends StoredObject {

    private static final byte BILLBOARD_FIXED = 0;
    private static final byte ITEM_DISPLAY_FIXED = 5;
    private static final BlockState BARRIER = new BlockState("barrier", java.util.Collections.singletonMap("waterlogged", "false"));

    private final Object2IntMap<BlockPosition> displays = new Object2IntOpenHashMap<>();
    private final Int2ObjectMap<String> identifiersById = new Int2ObjectOpenHashMap<>();

    public CustomBlockDisplayTracker(final UserConnection user) {
        super(user);
        this.displays.defaultReturnValue(-1);
    }

    public int placeholderJavaBlockState() {
        final Integer id = BedrockProtocol.MAPPINGS.getJavaBlockStates().get(BARRIER);
        return id != null ? id : -1;
    }

    public boolean shouldOverlay(final int bedrockRuntimeId) {
        final UserConnection user = this.user();
        final ResourcePackStorage packs = user.get(ResourcePackStorage.class);
        if (packs == null || !packs.isLoadedOnJavaClient()) {
            return false;
        }
        final BlockStateRewriter rewriter = user.get(BlockStateRewriter.class);
        if (rewriter == null) {
            return false;
        }
        final BlockState bedrockState = rewriter.blockState(bedrockRuntimeId);
        if (bedrockState == null) {
            return false;
        }
        return CustomBlockTextureResourceRewriter.hasConvertedTexture(packs, bedrockState.namespacedIdentifier());
    }

    public void sync(final BlockPosition position, final int bedrockRuntimeId) {
        if (!this.shouldOverlay(bedrockRuntimeId)) {
            this.remove(position);
            return;
        }
        final BlockState bedrockState = this.user().get(BlockStateRewriter.class).blockState(bedrockRuntimeId);
        this.spawnOrUpdate(position, bedrockState.namespacedIdentifier());
    }

    public void remove(final BlockPosition position) {
        final int javaId = this.displays.removeInt(position);
        if (javaId == -1) {
            return;
        }
        this.identifiersById.remove(javaId);
        final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
        removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{javaId});
        removeEntities.send(BedrockProtocol.class);
    }

    public void removeChunk(final ChunkPosition chunkPos) {
        final List<BlockPosition> toRemove = new ArrayList<>();
        for (BlockPosition position : this.displays.keySet()) {
            if ((position.x() >> 4) == chunkPos.chunkX() && (position.z() >> 4) == chunkPos.chunkZ()) {
                toRemove.add(position);
            }
        }
        for (BlockPosition position : toRemove) {
            this.remove(position);
        }
    }

    private void spawnOrUpdate(final BlockPosition position, final String identifier) {
        int javaId = this.displays.getInt(position);
        if (javaId == -1) {
            javaId = this.user().get(EntityTracker.class).getNextJavaEntityId();
            this.displays.put(position, javaId);
            final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, this.user());
            addEntity.write(Types.VAR_INT, javaId);
            addEntity.write(Types.UUID, UUID.randomUUID());
            addEntity.write(Types.VAR_INT, EntityTypes1_21_11.ITEM_DISPLAY.getId());
            addEntity.write(Types.DOUBLE, position.x() + 0.5D);
            addEntity.write(Types.DOUBLE, position.y() + 0.5D);
            addEntity.write(Types.DOUBLE, position.z() + 0.5D);
            addEntity.write(Types.LOW_PRECISION_VECTOR, Vector3d.ZERO);
            addEntity.write(Types.BYTE, (byte) 0);
            addEntity.write(Types.BYTE, (byte) 0);
            addEntity.write(Types.BYTE, (byte) 0);
            addEntity.write(Types.VAR_INT, 0);
            addEntity.send(BedrockProtocol.class);
        } else if (identifier.equals(this.identifiersById.get(javaId))) {
            return;
        }
        this.identifiersById.put(javaId, identifier);

        final StructuredDataContainer data = ProtocolConstants.createStructuredDataContainer();
        data.set(StructuredDataKey.ITEM_MODEL, CustomBlockTextureResourceRewriter.getItemModel(identifier));
        data.set(StructuredDataKey.CUSTOM_MODEL_DATA1_21_4, CustomBlockTextureResourceRewriter.getCustomModelData("0"));
        final StructuredItem item = new StructuredItem(
                BedrockProtocol.MAPPINGS.getJavaItems().get("minecraft:paper"), 1, data);

        final Entity probe = new Entity(this.user(), 0L, 0L, null, javaId, UUID.randomUUID(), EntityTypes1_21_11.ITEM_DISPLAY);
        final List<EntityData> entityData = new ArrayList<>();
        entityData.add(new EntityData(probe.getJavaEntityDataIndex(EntityDataFields.ITEM_STACK),
                VersionedTypes.V26_1.entityDataTypes.itemType, item));
        entityData.add(new EntityData(probe.getJavaEntityDataIndex(EntityDataFields.ITEM_DISPLAY),
                VersionedTypes.V26_1.entityDataTypes.byteType, ITEM_DISPLAY_FIXED));
        entityData.add(new EntityData(probe.getJavaEntityDataIndex(EntityDataFields.BILLBOARD_RENDER_CONSTRAINTS),
                VersionedTypes.V26_1.entityDataTypes.byteType, BILLBOARD_FIXED));
        entityData.add(new EntityData(probe.getJavaEntityDataIndex(EntityDataFields.SCALE),
                VersionedTypes.V26_1.entityDataTypes.vector3FType, new Vector3f(1F, 1F, 1F)));
        entityData.add(new EntityData(probe.getJavaEntityDataIndex(EntityDataFields.TRANSLATION),
                VersionedTypes.V26_1.entityDataTypes.vector3FType, new Vector3f(0F, 0F, 0F)));
        entityData.add(new EntityData(probe.getJavaEntityDataIndex(EntityDataFields.SHADOW_RADIUS),
                VersionedTypes.V26_1.entityDataTypes.floatType, 0F));
        entityData.add(new EntityData(probe.getJavaEntityDataIndex(EntityDataFields.SHADOW_STRENGTH),
                VersionedTypes.V26_1.entityDataTypes.floatType, 0F));

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, javaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, entityData);
        setEntityData.send(BedrockProtocol.class);
    }

}
