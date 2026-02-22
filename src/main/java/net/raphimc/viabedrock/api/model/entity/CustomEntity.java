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
package net.raphimc.viabedrock.api.model.entity;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.Quaternion;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.Vector3f;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.minecraft.item.data.ItemModel;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_9to1_21_11.packet.ClientboundPackets1_21_11;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.render.RenderControllerEvaluator;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.resourcepack.EntityDefinitions;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.api.util.MathUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.model.animation.ServerEntityTicker;
import net.raphimc.viabedrock.protocol.model.animation.SimpleBoneModel;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomEntityResourceRewriter;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.ResourcePacksStorage;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.util.*;
import java.util.logging.Level;

public class CustomEntity extends Entity {

    /**
     * LOD tiers: distance (blocks) -> update interval (ticks).
     * interpolation_duration matches update interval for seamless transitions.
     */
    private static final int[][] LOD_TIERS = {
            {16, 4},    // < 16 blocks: update every 4 ticks
            {32, 8},    // < 32 blocks: every 8 ticks
            {48, 12},   // < 48 blocks: every 12 ticks
            {64, 20},   // < 64 blocks: every 20 ticks
    };
    private static final int LOD_FREEZE_DISTANCE = 64;

    private final EntityDefinitions.EntityDefinition entityDefinition;
    private final List<BoneDisplayEntity> boneEntities = new ArrayList<>();
    private boolean spawned;
    private boolean collisionBoxSent;

    // Server-side animation (only used when VBU mod is NOT present)
    private ServerEntityTicker serverTicker;
    private int ticksSinceLastUpdate;
    private int currentLodInterval = 4;

    public CustomEntity(final UserConnection user, final long uniqueId, final long runtimeId, final String type, final int javaId, final EntityDefinitions.EntityDefinition entityDefinition) {
        super(user, uniqueId, runtimeId, type, javaId, UUID.randomUUID(), EntityTypes1_21_11.INTERACTION);
        this.entityDefinition = entityDefinition;
    }

    @Override
    public void tick() {
        super.tick();

        // Server-side animation only when VBU is not present
        if (this.serverTicker == null || !this.spawned || this.boneEntities.isEmpty()) {
            return;
        }

        this.ticksSinceLastUpdate++;
        if (this.ticksSinceLastUpdate < this.currentLodInterval) {
            return;
        }
        this.ticksSinceLastUpdate = 0;

        // Update LOD tier based on distance to client player
        updateLodTier();

        // Run animation tick
        final MutableObjectBinding queryBindings = buildQueryBindings();
        final Map<String, SimpleBoneModel.WorldTransform> transforms = this.serverTicker.tick(queryBindings);
        if (transforms == null || transforms.isEmpty()) {
            return;
        }

        // Send updated transforms to all bone Display Entities
        sendBoneTransformUpdates(transforms);
    }

    @Override
    public void setPosition(final Position3f position) {
        super.setPosition(position);

        if (!this.spawned) {
            this.initServerTicker();
            this.evaluateRenderControllerChange();
            this.spawn();
        } else {
            this.boneEntities.forEach(BoneDisplayEntity::updatePositionAndRotation);
        }
    }

    @Override
    public void setRotation(final Position3f rotation) {
        super.setRotation(rotation);

        if (this.spawned) {
            this.boneEntities.forEach(BoneDisplayEntity::updatePositionAndRotation);
        }
    }

    @Override
    public void remove() {
        super.remove();
        this.despawn();
        this.serverTicker = null;
    }

    @Override
    protected boolean translateEntityData(final ActorDataIDs id, final EntityData entityData, final List<EntityData> javaEntityData) {
        if (id == ActorDataIDs.RESERVED_038 && this.spawned) {
            final ChannelStorage channelStorage = this.user.get(ChannelStorage.class);
            if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
                ViaBedrockUtilityInterface.spawnCustomEntity(this.user, this.javaUuid(), this.entityDefinition.identifier(), this.entityData());
            }
            this.updateCollisionBox(javaEntityData);
            this.collisionBoxSent = true;
            return true;
        }
        if ((id == ActorDataIDs.RESERVED_053 || id == ActorDataIDs.RESERVED_054) && this.spawned) {
            this.updateCollisionBox(javaEntityData);
            this.collisionBoxSent = true;
            return true;
        }
        return super.translateEntityData(id, entityData, javaEntityData);
    }

    @Override
    protected void onEntityDataChanged() {
        super.onEntityDataChanged();

        if (!this.collisionBoxSent && this.spawned) {
            this.sendInitialCollisionBox();
            this.collisionBoxSent = true;
        }

        if (this.evaluateRenderControllerChange()) {
            this.despawn();
            this.spawn();
        } else {
            final ChannelStorage channelStorage = this.user.get(ChannelStorage.class);
            if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
                ViaBedrockUtilityInterface.spawnCustomEntity(this.user, this.javaUuid(), this.entityDefinition.identifier(), this.entityData());
            }
        }
    }

    // ---- Server ticker initialization ----

    private void initServerTicker() {
        final ChannelStorage channelStorage = this.user.get(ChannelStorage.class);
        if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
            return; // VBU mod handles animations client-side
        }

        final ResourcePacksStorage resourcePacksStorage = this.user.get(ResourcePacksStorage.class);

        // Find the first geometry model for this entity
        for (Map.Entry<String, String> entry : this.entityDefinition.entityData().getGeometries().entrySet()) {
            final BedrockGeometryModel geometry = resourcePacksStorage.getModels().entityModels().get(entry.getValue());
            if (geometry == null) continue;

            final SimpleBoneModel boneModel = new SimpleBoneModel(geometry);

            // Build a PackManager from bedrockmotion definitions
            // The PackManager is stored in ResourcePacksStorage's converterData during resource pack processing
            final PackManager packManager = (PackManager) resourcePacksStorage.getConverterData().get("bedrockmotion_pack_manager");
            if (packManager == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                        "BedrockMotion PackManager not found, server-side animation disabled");
                return;
            }

            this.serverTicker = new ServerEntityTicker(
                    this.entityDefinition.entityData(), boneModel, packManager);
            break;
        }
    }

    // ---- Spawn / Despawn ----

    private void spawn() {
        this.spawned = true;

        final EntityTracker entityTracker = this.user.get(EntityTracker.class);
        final ResourcePacksStorage resourcePacksStorage = this.user.get(ResourcePacksStorage.class);
        final ChannelStorage channelStorage = this.user.get(ChannelStorage.class);

        // VBU mod present: delegate to client-side rendering
        if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
            ViaBedrockUtilityInterface.spawnCustomEntity(this.user, this.javaUuid(), this.entityDefinition.identifier(), this.entityData());
            return;
        }

        // Determine current render controller models
        final List<RenderControllerEvaluator.EvaluatedModel> models =
                this.serverTicker != null ? this.serverTicker.getCurrentModels() : List.of();
        if (models.isEmpty()) {
            return;
        }

        // For each evaluated model, spawn per-bone Display Entities
        for (RenderControllerEvaluator.EvaluatedModel model : models) {
            final String baseKey = this.entityDefinition.identifier() + "_" + model.key();

            @SuppressWarnings("unchecked")
            final List<String> boneNames = (List<String>) resourcePacksStorage.getConverterData().get("ce_" + baseKey + "_bones");
            if (boneNames == null || boneNames.isEmpty()) continue;

            for (String boneName : boneNames) {
                final String boneKey = baseKey + "_" + boneName;
                final Object scaleObj = resourcePacksStorage.getConverterData().get("ce_" + boneKey + "_scale");
                if (scaleObj == null) continue;
                final float scale = (float) scaleObj;

                // Compute rest offset from bone's rest pivot
                final org.joml.Vector3f restPivot = this.serverTicker != null
                        ? this.serverTicker.getBoneModel().getRestPivot(boneName)
                        : new org.joml.Vector3f();
                final Vector3f restOffset = computeRestOffset(restPivot);

                final BoneDisplayEntity boneEntity = new BoneDisplayEntity(
                        entityTracker.getNextJavaEntityId(), boneName, scale, restOffset);
                this.boneEntities.add(boneEntity);

                final List<EntityData> javaEntityData = new ArrayList<>();

                // Item model with per-bone custom model data
                final StructuredDataContainer data = ProtocolConstants.createStructuredDataContainer();
                data.set(StructuredDataKey.ITEM_MODEL, new ItemModel(CustomEntityResourceRewriter.ITEM_MODEL_KEY));
                data.set(StructuredDataKey.CUSTOM_MODEL_DATA1_21_4, CustomEntityResourceRewriter.getCustomModelData(boneKey));
                final StructuredItem item = new StructuredItem(
                        BedrockProtocol.MAPPINGS.getJavaItems().get("minecraft:paper"), 1, data);
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.ITEM_STACK),
                        VersionedTypes.V1_21_11.entityDataTypes.itemType, item));

                // Scale
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.SCALE),
                        VersionedTypes.V1_21_11.entityDataTypes.vector3FType,
                        new Vector3f(scale, scale, scale)));

                // Translation: rest pose offset
                // Formula: TRANSLATION = bonePivotWorld_java - R * P_offset
                // At rest: R = identity, bonePivotWorld_java = bedrockPivotToJavaBlocks(restPivot)
                // So: TRANSLATION = bedrockPivotToJavaBlocks(restPivot) - restOffset
                final Vector3f pivotJava = bedrockPivotToJavaBlocks(restPivot);
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSLATION),
                        VersionedTypes.V1_21_11.entityDataTypes.vector3FType,
                        new Vector3f(pivotJava.x() - restOffset.x(),
                                pivotJava.y() - restOffset.y(),
                                pivotJava.z() - restOffset.z())));

                // LEFT_ROTATION: identity quaternion initially
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.LEFT_ROTATION),
                        VersionedTypes.V1_21_11.entityDataTypes.quaternionType,
                        new Quaternion(0F, 0F, 0F, 1F)));

                // Enable interpolation for smooth animation
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSFORMATION_INTERPOLATION_DURATION),
                        VersionedTypes.V1_21_11.entityDataTypes.varIntType,
                        this.currentLodInterval));
                javaEntityData.add(new EntityData(
                        boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS),
                        VersionedTypes.V1_21_11.entityDataTypes.varIntType,
                        0));

                // Spawn ADD_ENTITY packet
                final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets1_21_11.ADD_ENTITY, this.user);
                addEntity.write(Types.VAR_INT, boneEntity.javaId());
                addEntity.write(Types.UUID, boneEntity.javaUuid());
                addEntity.write(Types.VAR_INT, boneEntity.javaType().getId());
                addEntity.write(Types.DOUBLE, (double) this.position.x());
                addEntity.write(Types.DOUBLE, (double) this.position.y());
                addEntity.write(Types.DOUBLE, (double) this.position.z());
                addEntity.write(Types.MOVEMENT_VECTOR, Vector3d.ZERO);
                addEntity.write(Types.BYTE, MathUtil.float2Byte(this.rotation.x()));
                addEntity.write(Types.BYTE, MathUtil.float2Byte(this.rotation.y()));
                addEntity.write(Types.BYTE, MathUtil.float2Byte(this.rotation.z()));
                addEntity.write(Types.VAR_INT, 0);
                addEntity.send(BedrockProtocol.class);

                // Set entity data
                final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets1_21_11.SET_ENTITY_DATA, this.user);
                setEntityData.write(Types.VAR_INT, boneEntity.javaId());
                setEntityData.write(VersionedTypes.V1_21_11.entityDataList, javaEntityData);
                setEntityData.send(BedrockProtocol.class);
            }
        }
    }

    private void despawn() {
        this.spawned = false;
        final int[] entityIds = new int[boneEntities.size()];
        for (int i = 0; i < boneEntities.size(); i++) {
            entityIds[i] = boneEntities.get(i).javaId();
        }
        this.boneEntities.clear();
        if (entityIds.length > 0) {
            final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets1_21_11.REMOVE_ENTITIES, this.user);
            removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, entityIds);
            removeEntities.send(BedrockProtocol.class);
        }
    }

    // ---- Animation updates ----

    private void sendBoneTransformUpdates(Map<String, SimpleBoneModel.WorldTransform> transforms) {
        for (BoneDisplayEntity boneEntity : this.boneEntities) {
            final SimpleBoneModel.WorldTransform transform = transforms.get(boneEntity.boneName);
            if (transform == null) continue;

            final List<EntityData> javaEntityData = new ArrayList<>();

            // Convert world pivot from Bedrock model units to Java blocks (with X flip)
            final Vector3f pivotJava = bedrockPivotToJavaBlocks(transform.position());

            // Convert rotation quaternion from Bedrock to Java space (X flip)
            final Quaternion rotJava = bedrockQuatToJava(transform.rotation());

            // TRANSLATION = bonePivotWorld_java - R * P_offset
            // This ensures cubes rotate around the bone's pivot, not the model origin.
            // R * P_offset: rotate the rest offset by the current rotation
            final org.joml.Quaternionf rj = new org.joml.Quaternionf(rotJava.x(), rotJava.y(), rotJava.z(), rotJava.w());
            final org.joml.Vector3f rotatedOffset = new org.joml.Vector3f(
                    boneEntity.restOffset.x(), boneEntity.restOffset.y(), boneEntity.restOffset.z());
            rj.transform(rotatedOffset);

            javaEntityData.add(new EntityData(
                    boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSLATION),
                    VersionedTypes.V1_21_11.entityDataTypes.vector3FType,
                    new Vector3f(pivotJava.x() - rotatedOffset.x,
                            pivotJava.y() - rotatedOffset.y,
                            pivotJava.z() - rotatedOffset.z)));

            // Rotation
            javaEntityData.add(new EntityData(
                    boneEntity.getJavaEntityDataIndex(EntityDataFields.LEFT_ROTATION),
                    VersionedTypes.V1_21_11.entityDataTypes.quaternionType,
                    rotJava));

            // Scale from animation (model scale * animation scale)
            final org.joml.Vector3f animScale = transform.scale();
            final float baseScale = boneEntity.modelScale;
            javaEntityData.add(new EntityData(
                    boneEntity.getJavaEntityDataIndex(EntityDataFields.SCALE),
                    VersionedTypes.V1_21_11.entityDataTypes.vector3FType,
                    new Vector3f(baseScale * animScale.x, baseScale * animScale.y, baseScale * animScale.z)));

            // Set interpolation: duration = LOD interval, start = 0 (now)
            javaEntityData.add(new EntityData(
                    boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSFORMATION_INTERPOLATION_DURATION),
                    VersionedTypes.V1_21_11.entityDataTypes.varIntType,
                    this.currentLodInterval));
            javaEntityData.add(new EntityData(
                    boneEntity.getJavaEntityDataIndex(EntityDataFields.TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS),
                    VersionedTypes.V1_21_11.entityDataTypes.varIntType,
                    0));

            final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets1_21_11.SET_ENTITY_DATA, this.user);
            setEntityData.write(Types.VAR_INT, boneEntity.javaId());
            setEntityData.write(VersionedTypes.V1_21_11.entityDataList, javaEntityData);
            setEntityData.send(BedrockProtocol.class);
        }
    }

    // ---- LOD system ----

    private void updateLodTier() {
        final EntityTracker entityTracker = this.user.get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
        if (clientPlayer == null || clientPlayer.position() == null || this.position == null) {
            this.currentLodInterval = LOD_TIERS[0][1]; // default to closest tier
            return;
        }

        final float dx = this.position.x() - clientPlayer.position().x();
        final float dy = this.position.y() - clientPlayer.position().y();
        final float dz = this.position.z() - clientPlayer.position().z();
        final float distSq = dx * dx + dy * dy + dz * dz;

        for (int[] tier : LOD_TIERS) {
            if (distSq < tier[0] * tier[0]) {
                this.currentLodInterval = tier[1];
                return;
            }
        }

        // Beyond max distance: freeze animation
        this.currentLodInterval = Integer.MAX_VALUE;
    }

    // ---- Query bindings ----

    private MutableObjectBinding buildQueryBindings() {
        final MutableObjectBinding queryBinding = new MutableObjectBinding();

        if (this.entityData.containsKey(ActorDataIDs.VARIANT)) {
            queryBinding.set("variant", Value.of(this.entityData.get(ActorDataIDs.VARIANT).<Integer>value()));
        }
        if (this.entityData.containsKey(ActorDataIDs.MARK_VARIANT)) {
            queryBinding.set("mark_variant", Value.of(this.entityData.get(ActorDataIDs.MARK_VARIANT).<Integer>value()));
        }
        if (this.entityData.containsKey(ActorDataIDs.SKIN_ID)) {
            queryBinding.set("skin_id", Value.of(this.entityData.get(ActorDataIDs.SKIN_ID).<Integer>value()));
        }

        final Set<ActorFlags> entityFlags = this.entityFlags();
        for (Map.Entry<ActorFlags, String> entry : BedrockProtocol.MAPPINGS.getBedrockEntityFlagMoLangQueries().entrySet()) {
            if (entityFlags.contains(entry.getKey())) {
                queryBinding.set(entry.getValue(), Value.of(true));
            }
        }
        if (entityFlags.contains(ActorFlags.ONFIRE)) {
            queryBinding.set("is_onfire", Value.of(true));
        }

        return queryBinding;
    }

    // ---- Render controller evaluation ----

    private boolean evaluateRenderControllerChange() {
        if (this.serverTicker == null) {
            return false;
        }
        return this.serverTicker.evaluateRenderControllers(buildQueryBindings());
    }

    // ---- Collision box ----

    private void sendInitialCollisionBox() {
        final List<EntityData> javaEntityData = new ArrayList<>();
        this.updateCollisionBox(javaEntityData);
        if (!javaEntityData.isEmpty()) {
            final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets1_21_11.SET_ENTITY_DATA, this.user);
            setEntityData.write(Types.VAR_INT, this.javaId()); // entity id
            setEntityData.write(VersionedTypes.V1_21_11.entityDataList, javaEntityData); // entity data
            setEntityData.send(BedrockProtocol.class);
        }
    }

    private static final float DEFAULT_WIDTH = 0.6F;
    private static final float DEFAULT_HEIGHT = 1.8F;

    private void updateCollisionBox(final List<EntityData> javaEntityData) {
        float scale = 1.0F;
        if (this.entityData.containsKey(ActorDataIDs.RESERVED_038)) {
            scale = this.entityData.get(ActorDataIDs.RESERVED_038).<Float>value();
        }

        float width = this.entityData.containsKey(ActorDataIDs.RESERVED_053)
                ? this.entityData.get(ActorDataIDs.RESERVED_053).<Float>value()
                : DEFAULT_WIDTH;
        float height = this.entityData.containsKey(ActorDataIDs.RESERVED_054)
                ? this.entityData.get(ActorDataIDs.RESERVED_054).<Float>value()
                : DEFAULT_HEIGHT;
        width *= scale;
        height *= scale;

        javaEntityData.add(new EntityData(
                this.getJavaEntityDataIndex(EntityDataFields.WIDTH),
                VersionedTypes.V1_21_11.entityDataTypes().floatType, width));
        javaEntityData.add(new EntityData(
                this.getJavaEntityDataIndex(EntityDataFields.HEIGHT),
                VersionedTypes.V1_21_11.entityDataTypes().floatType, height));
    }

    // ---- Coordinate conversion helpers ----

    /**
     * Compute the rest offset (P_offset) for a bone in Java Display Entity blocks.
     * This is where the bone's cubes are centered in the item model's rendered space.
     * <p>
     * CubeConverter's coordinate mapping:
     * - Java X = -Bedrock X + 8 (model units), so in blocks: -bpx/16 + 0.5
     * - Java Y = Bedrock Y, so in blocks: bpy/16
     * - Java Z = Bedrock Z + 8 (model units), so in blocks: bpz/16 + 0.5
     */
    private static Vector3f computeRestOffset(final org.joml.Vector3f restPivot) {
        return new Vector3f(
                -restPivot.x / 16f + 0.5f,
                restPivot.y / 16f,
                restPivot.z / 16f + 0.5f);
    }

    /**
     * Convert a bone's animated world pivot from Bedrock model units to Java blocks.
     * Applies Bedrock→Java X-axis flip.
     */
    private static Vector3f bedrockPivotToJavaBlocks(final org.joml.Vector3f bedrockPivot) {
        return new Vector3f(
                -bedrockPivot.x / 16f,
                bedrockPivot.y / 16f,
                bedrockPivot.z / 16f);
    }

    /**
     * Convert a Bedrock-space rotation quaternion to Java space (X-axis flip).
     * Under X reflection: rotations around Y and Z axes reverse direction.
     * Quaternion: (qx, qy, qz, qw) → (qx, -qy, -qz, qw)
     */
    private static Quaternion bedrockQuatToJava(final org.joml.Quaternionf q) {
        return new Quaternion(q.x, -q.y, -q.z, q.w);
    }

    // ---- Inner class: per-bone Display Entity ----

    private class BoneDisplayEntity extends Entity {

        final String boneName;
        final float modelScale;
        /** Rest offset in Java blocks — where this bone's cubes are centered in the item model */
        final Vector3f restOffset;

        public BoneDisplayEntity(final int javaId, final String boneName, final float modelScale, final Vector3f restOffset) {
            super(CustomEntity.this.user, 0L, 0L, null, javaId, UUID.randomUUID(), EntityTypes1_21_11.ITEM_DISPLAY);
            this.boneName = boneName;
            this.modelScale = modelScale;
            this.restOffset = restOffset;
        }

        public void updatePositionAndRotation() {
            final PacketWrapper entityPositionSync = PacketWrapper.create(ClientboundPackets1_21_11.ENTITY_POSITION_SYNC, this.user);
            entityPositionSync.write(Types.VAR_INT, this.javaId());
            entityPositionSync.write(Types.DOUBLE, (double) CustomEntity.this.position.x());
            entityPositionSync.write(Types.DOUBLE, (double) CustomEntity.this.position.y());
            entityPositionSync.write(Types.DOUBLE, (double) CustomEntity.this.position.z());
            entityPositionSync.write(Types.DOUBLE, 0D);
            entityPositionSync.write(Types.DOUBLE, 0D);
            entityPositionSync.write(Types.DOUBLE, 0D);
            entityPositionSync.write(Types.FLOAT, CustomEntity.this.rotation.y());
            entityPositionSync.write(Types.FLOAT, CustomEntity.this.rotation.x());
            entityPositionSync.write(Types.BOOLEAN, CustomEntity.this.onGround);
            entityPositionSync.send(BedrockProtocol.class);
        }
    }

}
