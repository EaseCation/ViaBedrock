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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.Vector3f;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.mcstructs.text.TextFormatting;
import com.viaversion.viaversion.protocols.v1_21_9to1_21_11.packet.ClientboundPackets1_21_11;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.PlayerEntity;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.java.PlayerTeamAction;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.TeamCollisionRule;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.TeamVisibility;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.*;

/**
 * Tracks and manages virtual TEXT_DISPLAY entities for Bedrock multiline nametags.
 * <p>
 * Bedrock edition natively supports multiline nametags (via \n), but Java edition's
 * CUSTOM_NAME ignores newlines and renders single-line. This tracker creates TEXT_DISPLAY
 * entities (which do support multiline) above entities that have multiline nametags.
 * <p>
 * Fully decoupled from upstream code — uses ProtocolUtil.appendClientbound to hook
 * into existing packet handlers without modifying them.
 */
public class MultilineNametagTracker extends StoredObject {

    private final Map<Long, NametagDisplayInfo> displays = new HashMap<>();

    public MultilineNametagTracker(final UserConnection user) {
        super(user);
    }

    // ---- Packet handler registration ----

    public static void registerHandlers(final BedrockProtocol protocol) {
        // 1. ADD_ENTITY — detect multiline nametags on entity spawn
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.ADD_ENTITY, wrapper -> {
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            wrapper.resetReader();
            wrapper.read(BedrockTypes.VAR_LONG); // entity unique id
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity != null) {
                tracker.handleEntityDataUpdate(entity);
            }
        });

        // 2. ADD_PLAYER — detect multiline nametags on player spawn
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.ADD_PLAYER, wrapper -> {
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            wrapper.resetReader();
            wrapper.read(BedrockTypes.UUID); // uuid
            wrapper.read(BedrockTypes.STRING); // username
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity != null) {
                tracker.handleEntityDataUpdate(entity);
            }
        });

        // 3. SET_ENTITY_DATA — detect nametag changes
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.SET_ENTITY_DATA, wrapper -> {
            if (wrapper.isCancelled()) return;
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            wrapper.resetReader();
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity != null) {
                tracker.handleEntityDataUpdate(entity);
            }
        });

        // 4. MOVE_ENTITY_ABSOLUTE — position sync
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.MOVE_ENTITY_ABSOLUTE, wrapper -> {
            if (wrapper.isCancelled()) return;
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            wrapper.resetReader();
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity != null) {
                tracker.handlePositionUpdate(entity);
            }
        });

        // 5. MOVE_ENTITY_DELTA — position sync
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.MOVE_ENTITY_DELTA, wrapper -> {
            if (wrapper.isCancelled()) return;
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            wrapper.resetReader();
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity != null) {
                tracker.handlePositionUpdate(entity);
            }
        });

        // 6. MOVE_PLAYER — player position sync
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.MOVE_PLAYER, wrapper -> {
            if (wrapper.isCancelled()) return;
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            wrapper.resetReader();
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            if (entityTracker == null) return;
            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity != null && entity != entityTracker.getClientPlayer()) {
                tracker.handlePositionUpdate(entity);
            }
        });

        // 7. REMOVE_ENTITY — cleanup TEXT_DISPLAY
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.REMOVE_ENTITY, wrapper -> {
            if (wrapper.isCancelled()) return;
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker == null) return;
            wrapper.resetReader();
            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id
            tracker.handleEntityRemoved(entityUniqueId);
        });

        // 8. CHANGE_DIMENSION — clear all state
        // Note: Do NOT check isCancelled() here — upstream uses send()+cancel() pattern,
        // so the dimension change DID happen. Java client clears all entities, so we must clear our tracking state.
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.CHANGE_DIMENSION, wrapper -> {
            final MultilineNametagTracker tracker = wrapper.user().get(MultilineNametagTracker.class);
            if (tracker != null) {
                tracker.clearAll();
            }
        });
    }

    // ---- Core logic ----

    /**
     * Handles entity data updates — checks for multiline nametags and creates/updates/removes TEXT_DISPLAY.
     */
    public void handleEntityDataUpdate(final Entity entity) {
        final String name = getEntityName(entity);
        final boolean isMultiline = isMultilineName(name);
        final boolean alwaysShow = isNametagAlwaysShown(entity);
        final NametagDisplayInfo existing = displays.get(entity.uniqueId());

        if (isMultiline && alwaysShow) {
            if (existing == null) {
                // Create new TEXT_DISPLAY
                createTextDisplay(entity, name);
            } else {
                // Update existing TEXT_DISPLAY
                updateTextDisplay(entity, existing, name);
            }
        } else {
            if (existing != null) {
                // Remove TEXT_DISPLAY (name became single-line, empty, or hidden)
                removeTextDisplay(existing);
                displays.remove(entity.uniqueId());
            }
        }
    }

    /**
     * Syncs TEXT_DISPLAY position when the host entity moves.
     */
    public void handlePositionUpdate(final Entity entity) {
        final NametagDisplayInfo info = displays.get(entity.uniqueId());
        if (info == null || entity.position() == null) return;
        sendPositionSync(entity, info);
    }

    /**
     * Cleans up TEXT_DISPLAY when the host entity is removed.
     */
    public void handleEntityRemoved(final long entityUniqueId) {
        final NametagDisplayInfo info = displays.remove(entityUniqueId);
        if (info != null) {
            removeTextDisplay(info);
        }
    }

    /**
     * Clears all tracked state (e.g., on dimension change).
     * Java client automatically clears all entities on dimension change, so no REMOVE_ENTITIES needed.
     */
    public void clearAll() {
        displays.clear();
    }

    // ---- TEXT_DISPLAY management ----

    private void createTextDisplay(final Entity entity, final String name) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        final int javaId = entityTracker.getNextJavaEntityId();
        final UUID uuid = UUID.randomUUID();
        final NametagDisplayInfo info = new NametagDisplayInfo(javaId, uuid, entity.uniqueId(), name);
        displays.put(entity.uniqueId(), info);

        // 1. Send corrective SET_ENTITY_DATA to hide the host entity's original CUSTOM_NAME
        sendClearHostNametag(entity);

        // 2. Spawn TEXT_DISPLAY entity
        final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets1_21_11.ADD_ENTITY, this.user());
        addEntity.write(Types.VAR_INT, javaId); // entity id
        addEntity.write(Types.UUID, uuid); // uuid
        addEntity.write(Types.VAR_INT, EntityTypes1_21_11.TEXT_DISPLAY.getId()); // type id
        addEntity.write(Types.DOUBLE, (double) entity.position().x()); // x
        addEntity.write(Types.DOUBLE, (double) entity.position().y()); // y
        addEntity.write(Types.DOUBLE, (double) entity.position().z()); // z
        addEntity.write(Types.MOVEMENT_VECTOR, Vector3d.ZERO); // velocity
        addEntity.write(Types.BYTE, (byte) 0); // pitch
        addEntity.write(Types.BYTE, (byte) 0); // yaw
        addEntity.write(Types.BYTE, (byte) 0); // head yaw
        addEntity.write(Types.VAR_INT, 0); // data
        addEntity.send(BedrockProtocol.class);

        // 3. Configure TEXT_DISPLAY entity data
        sendTextDisplayEntityData(info, entity, name);
    }

    private void updateTextDisplay(final Entity entity, final NametagDisplayInfo info, final String name) {
        final boolean nameChanged = !name.equals(info.lastNameText);
        final float currentHeight = getEffectiveHeight(entity);
        final float currentScale = getEffectiveScale(entity);
        final boolean translationChanged = currentHeight != info.lastHeight || currentScale != info.lastScale;

        if (nameChanged || translationChanged) {
            // Re-send corrective packet to ensure host nametag stays hidden
            sendClearHostNametag(entity);

            // Update TEXT_DISPLAY
            final List<EntityData> displayData = new ArrayList<>();
            if (nameChanged) {
                info.lastNameText = name;
                final Tag textNbt = TextUtil.stringToNbt(name);
                displayData.add(new EntityData(
                        textDisplayIndex(EntityDataFields.TEXT),
                        VersionedTypes.V1_21_11.entityDataTypes().componentType,
                        textNbt));
            }
            if (translationChanged) {
                info.lastHeight = currentHeight;
                info.lastScale = currentScale;
                displayData.add(new EntityData(
                        textDisplayIndex(EntityDataFields.TRANSLATION),
                        VersionedTypes.V1_21_11.entityDataTypes().vector3FType,
                        new Vector3f(0f, currentHeight * currentScale + 0.5f, 0f)));
            }
            if (!displayData.isEmpty()) {
                final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets1_21_11.SET_ENTITY_DATA, this.user());
                setEntityData.write(Types.VAR_INT, info.textDisplayJavaId);
                setEntityData.write(VersionedTypes.V1_21_11.entityDataList, displayData);
                setEntityData.send(BedrockProtocol.class);
            }
        }
    }

    private void removeTextDisplay(final NametagDisplayInfo info) {
        final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets1_21_11.REMOVE_ENTITIES, this.user());
        removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{info.textDisplayJavaId});
        removeEntities.send(BedrockProtocol.class);
    }

    private void sendTextDisplayEntityData(final NametagDisplayInfo info, final Entity entity, final String name) {
        info.lastNameText = name;
        info.lastHeight = getEffectiveHeight(entity);
        info.lastScale = getEffectiveScale(entity);

        final Tag textNbt = TextUtil.stringToNbt(name);
        final List<EntityData> displayData = new ArrayList<>();

        // TEXT — the multiline text content
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TEXT),
                VersionedTypes.V1_21_11.entityDataTypes().componentType,
                textNbt));

        // BILLBOARD_RENDER_CONSTRAINTS = 3 (CENTER — always face the player, like vanilla nametags)
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.BILLBOARD_RENDER_CONSTRAINTS),
                VersionedTypes.V1_21_11.entityDataTypes().byteType,
                (byte) 3));

        // TRANSLATION — vertical offset above entity's bounding box
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TRANSLATION),
                VersionedTypes.V1_21_11.entityDataTypes().vector3FType,
                new Vector3f(0f, info.lastHeight * info.lastScale + 0.5f, 0f)));

        // BACKGROUND_COLOR = 0x40000000 (semi-transparent black, matches vanilla nametag style)
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.BACKGROUND_COLOR),
                VersionedTypes.V1_21_11.entityDataTypes().varIntType,
                0x40000000));

        // TEXT_OPACITY = -1 (0xFF = fully opaque)
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.TEXT_OPACITY),
                VersionedTypes.V1_21_11.entityDataTypes().byteType,
                (byte) -1));

        // VIEW_RANGE = 1.0 (default)
        displayData.add(new EntityData(
                textDisplayIndex(EntityDataFields.VIEW_RANGE),
                VersionedTypes.V1_21_11.entityDataTypes().floatType,
                1.0f));

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets1_21_11.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, info.textDisplayJavaId);
        setEntityData.write(VersionedTypes.V1_21_11.entityDataList, displayData);
        setEntityData.send(BedrockProtocol.class);
    }

    private void sendPositionSync(final Entity entity, final NametagDisplayInfo info) {
        final PacketWrapper posSync = PacketWrapper.create(ClientboundPackets1_21_11.ENTITY_POSITION_SYNC, this.user());
        posSync.write(Types.VAR_INT, info.textDisplayJavaId); // entity id
        posSync.write(Types.DOUBLE, (double) entity.position().x()); // x
        // Subtract eyeOffset: MOVE_PLAYER stores head position (feet + eyeOffset) but we need feet position.
        // For non-player entities eyeOffset=0 so this is a no-op.
        posSync.write(Types.DOUBLE, (double) (entity.position().y() - entity.eyeOffset())); // y
        posSync.write(Types.DOUBLE, (double) entity.position().z()); // z
        posSync.write(Types.DOUBLE, 0D); // velocity x
        posSync.write(Types.DOUBLE, 0D); // velocity y
        posSync.write(Types.DOUBLE, 0D); // velocity z
        posSync.write(Types.FLOAT, 0F); // yaw
        posSync.write(Types.FLOAT, 0F); // pitch
        posSync.write(Types.BOOLEAN, false); // on ground
        posSync.send(BedrockProtocol.class);
    }

    /**
     * Sends a corrective SET_ENTITY_DATA to hide the host entity's original nametag.
     * For non-player entities: clears CUSTOM_NAME and sets CUSTOM_NAME_VISIBLE=false.
     * For player entities: sends a team update to hide the team-based nametag.
     */
    private void sendClearHostNametag(final Entity entity) {
        if (entity instanceof PlayerEntity) {
            // Player entities use team system for nametags — send corrective team update
            final PacketWrapper setPlayerTeam = PacketWrapper.create(ClientboundPackets1_21_11.SET_PLAYER_TEAM, this.user());
            setPlayerTeam.write(Types.STRING, "vb_" + entity.javaId()); // team name
            setPlayerTeam.write(Types.BYTE, (byte) PlayerTeamAction.CHANGE.ordinal()); // mode
            setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt("vb_" + entity.javaId())); // display name
            setPlayerTeam.write(Types.BYTE, (byte) 3); // flags
            setPlayerTeam.write(Types.VAR_INT, TeamVisibility.NEVER.ordinal()); // name tag visibility = NEVER
            setPlayerTeam.write(Types.VAR_INT, TeamCollisionRule.NEVER.ordinal()); // collision rule
            setPlayerTeam.write(Types.VAR_INT, TextFormatting.RESET.getOrdinal()); // color
            setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt("")); // prefix
            setPlayerTeam.write(Types.TAG, TextUtil.stringToNbt("")); // suffix
            setPlayerTeam.send(BedrockProtocol.class);
        } else {
            // Non-player entities: clear CUSTOM_NAME via SET_ENTITY_DATA
            final List<EntityData> clearData = new ArrayList<>();
            clearData.add(new EntityData(
                    entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME),
                    VersionedTypes.V1_21_11.entityDataTypes().optionalComponentType,
                    null));
            clearData.add(new EntityData(
                    entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                    VersionedTypes.V1_21_11.entityDataTypes().booleanType,
                    false));
            final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets1_21_11.SET_ENTITY_DATA, this.user());
            setEntityData.write(Types.VAR_INT, entity.javaId());
            setEntityData.write(VersionedTypes.V1_21_11.entityDataList, clearData);
            setEntityData.send(BedrockProtocol.class);
        }
    }

    // ---- Utilities ----

    private static String getEntityName(final Entity entity) {
        final EntityData nameData = entity.entityData().get(ActorDataIDs.NAME);
        final EntityData nameRawData = entity.entityData().get(ActorDataIDs.NAME_RAW_TEXT);
        // Prefer NAME_RAW_TEXT over NAME if both present
        if (nameRawData != null) {
            final String raw = (String) nameRawData.getValue();
            if (raw != null && !TextUtil.stripFormatting(raw).isEmpty()) return raw;
        }
        if (nameData != null) {
            final String name = (String) nameData.getValue();
            if (name != null && !TextUtil.stripFormatting(name).isEmpty()) return name;
        }
        return null;
    }

    private static boolean isMultilineName(final String name) {
        return name != null && name.contains("\n");
    }

    private static boolean isNametagAlwaysShown(final Entity entity) {
        final EntityData alwaysShowData = entity.entityData().get(ActorDataIDs.NAMETAG_ALWAYS_SHOW);
        if (alwaysShowData != null) {
            return ((Number) alwaysShowData.getValue()).byteValue() == 1;
        }
        // Default: true for entities with a name (Bedrock default behavior)
        return true;
    }

    private static float getEffectiveHeight(final Entity entity) {
        final EntityData heightData = entity.entityData().get(ActorDataIDs.RESERVED_054);
        if (heightData != null) {
            return ((Number) heightData.getValue()).floatValue();
        }
        return 1.8f; // Default height for most entities
    }

    private static float getEffectiveScale(final Entity entity) {
        final EntityData scaleData = entity.entityData().get(ActorDataIDs.RESERVED_038);
        if (scaleData != null) {
            final float scale = ((Number) scaleData.getValue()).floatValue();
            if (scale > 0f) return scale;
        }
        return 1.0f;
    }

    /**
     * Gets the entity data field index for TEXT_DISPLAY entity type.
     */
    private static int textDisplayIndex(final String fieldName) {
        final int index = BedrockProtocol.MAPPINGS.getJavaEntityDataFields()
                .get(EntityTypes1_21_11.TEXT_DISPLAY).indexOf(fieldName);
        if (index == -1) {
            throw new IllegalStateException("Unknown TEXT_DISPLAY entity data field: " + fieldName);
        }
        return index;
    }

    // ---- Inner class ----

    private static class NametagDisplayInfo {
        final int textDisplayJavaId;
        final UUID textDisplayUuid;
        final long entityUniqueId;
        String lastNameText;
        float lastHeight;
        float lastScale;

        NametagDisplayInfo(final int textDisplayJavaId, final UUID textDisplayUuid, final long entityUniqueId, final String nameText) {
            this.textDisplayJavaId = textDisplayJavaId;
            this.textDisplayUuid = textDisplayUuid;
            this.entityUniqueId = entityUniqueId;
            this.lastNameText = nameText;
        }
    }

}
