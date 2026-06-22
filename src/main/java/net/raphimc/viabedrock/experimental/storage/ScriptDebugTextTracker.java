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

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.Vector3f;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ScriptModuleDebugUtilities_ScriptDebugShapeType;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Translates the Bedrock Script Debug Drawer ({@code PRIMITIVE_SHAPES} packet) into Java
 * TEXT_DISPLAY entities, for {@code Text} type shapes only.
 * <p>
 * Bedrock servers (e.g. CodeFunCore via SynapseAPI's {@code addShape}/{@code removeShape})
 * draw floating debug text panels using the Script Debug Drawer. Java edition has no equivalent,
 * so without this translation those panels are invisible to Java clients. Each {@code Text} shape
 * becomes a virtual TEXT_DISPLAY entity, mirroring the approach used by {@link MultilineNametagTracker}.
 * <p>
 * Non-text shapes (Line/Box/Sphere/Circle/Arrow) are parsed (to keep the multi-entry stream
 * aligned) but otherwise dropped.
 * <p>
 * The packet is a delta stream keyed by shape id: an entry with a present {@code type} is an
 * add/replace; an entry with an absent {@code type} is a removal. Shapes may have a finite
 * lifespan ({@code totalTimeLeft}), may be attached to an entity ({@code attachedEntityRuntimeId}),
 * and are all cleared on dimension change (the Java client clears all entities then anyway).
 */
public class ScriptDebugTextTracker extends StoredObject {

    private static final int DEFAULT_BACKGROUND_COLOR = 0x40000000; // semi-transparent black, like vanilla nametags
    private static final byte BILLBOARD_FIXED = 0;
    private static final byte BILLBOARD_CENTER = 3; // face camera on all axes
    private static final byte STYLE_SEE_THROUGH = 0x02; // render through blocks
    private static final float DEFAULT_VIEW_RANGE_BLOCKS = 64.0f; // view_range == 1.0 ~= 64 blocks
    private static final String PASSENGER_SOURCE = "script-debug-text";

    private final Map<Long, ShapeInfo> shapes = new HashMap<>();
    private long tickCounter = 0;

    public ScriptDebugTextTracker(final UserConnection user) {
        super(user);
    }

    // ---- Packet handler registration ----

    public static void registerHandlers(final BedrockProtocol protocol) {
        // PRIMITIVE_SHAPES has no upstream Java mapping, so appendClientbound registers a fresh
        // null-mapped handler. We fully consume the Bedrock packet and cancel (no Java passthrough).
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.PRIMITIVE_SHAPES, wrapper -> {
            final ScriptDebugTextTracker tracker = wrapper.user().get(ScriptDebugTextTracker.class);
            if (tracker != null) {
                try {
                    tracker.handlePrimitiveShapes(wrapper);
                } catch (final Exception e) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to handle PRIMITIVE_SHAPES packet", e);
                }
            }
            wrapper.cancel();
        });

        // CHANGE_DIMENSION — clear all tracked shapes (Java client clears all entities on dimension change)
        ProtocolUtil.appendClientbound(protocol, ClientboundBedrockPackets.CHANGE_DIMENSION, wrapper -> {
            final ScriptDebugTextTracker tracker = wrapper.user().get(ScriptDebugTextTracker.class);
            if (tracker != null) {
                tracker.clearAll();
            }
        });
    }

    // ---- Packet parsing (Bedrock 1.26.20 PrimitiveShapesPacket wire format) ----

    private void handlePrimitiveShapes(final PacketWrapper wrapper) {
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // entry count
        for (int i = 0; i < count; i++) {
            final long id = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // shape id
            final Byte typeByte = readOptional(wrapper, Types.BYTE); // optional type (ordinal)
            final Position3f location = readOptional(wrapper, BedrockTypes.POSITION_3F);
            final Float scale = readOptional(wrapper, BedrockTypes.FLOAT_LE);
            readOptional(wrapper, BedrockTypes.POSITION_3F); // rotation (Euler) — not applied (text faces camera)
            final Float totalTimeLeft = readOptional(wrapper, BedrockTypes.FLOAT_LE);
            final Float maxRenderDistance = readOptional(wrapper, BedrockTypes.FLOAT_LE);
            readOptional(wrapper, BedrockTypes.INT_LE); // color — text color is driven by § codes in the text
            readOptional(wrapper, BedrockTypes.VAR_INT); // dimension — single dimension per Java connection
            final Long attachedRuntimeId = readOptional(wrapper, BedrockTypes.UNSIGNED_VAR_LONG);
            wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // payloadType (always written, ignored — we dispatch on type)

            if (typeByte == null) {
                // Absent type = removal.
                remove(id);
                continue;
            }

            final ScriptModuleDebugUtilities_ScriptDebugShapeType type = ScriptModuleDebugUtilities_ScriptDebugShapeType.getByValue(typeByte & 0xFF);
            if (type == null) {
                // Unknown shape type: we cannot know its payload length, so we cannot safely
                // continue parsing the remaining entries. Abort this packet.
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown debug shape type: " + (typeByte & 0xFF) + ", skipping rest of PRIMITIVE_SHAPES packet");
                return;
            }

            // Read the type-specific payload (must always be consumed to keep the stream aligned).
            String text = null;
            boolean textRotation = false;
            Integer backgroundColor = null;
            boolean depthTest = false;
            switch (type) {
                case Text -> {
                    text = wrapper.read(BedrockTypes.STRING); // text (not optional)
                    textRotation = wrapper.read(Types.BOOLEAN);
                    backgroundColor = readOptional(wrapper, BedrockTypes.INT_LE);
                    depthTest = wrapper.read(Types.BOOLEAN);
                    wrapper.read(Types.BOOLEAN); // showBackface
                    wrapper.read(Types.BOOLEAN); // showTextBackface
                }
                case Arrow -> {
                    readOptional(wrapper, BedrockTypes.POSITION_3F); // lineEndLocation
                    readOptional(wrapper, BedrockTypes.FLOAT_LE); // arrowHeadLength
                    readOptional(wrapper, BedrockTypes.FLOAT_LE); // arrowHeadRadius
                    readOptional(wrapper, Types.BYTE); // numSegments
                }
                case Box -> wrapper.read(BedrockTypes.POSITION_3F); // boxBound
                case Line -> wrapper.read(BedrockTypes.POSITION_3F); // lineEndLocation
                case Sphere, Circle -> wrapper.read(Types.BYTE); // numSegments
            }

            if (type == ScriptModuleDebugUtilities_ScriptDebugShapeType.Text) {
                upsertText(id, location, scale, totalTimeLeft, maxRenderDistance, attachedRuntimeId, text != null ? text : "", textRotation, backgroundColor, depthTest);
            } else {
                // Non-text shape: not rendered. If this id previously held a text display, remove it.
                if (shapes.containsKey(id)) {
                    remove(id);
                }
            }
        }
    }

    private static <T> T readOptional(final PacketWrapper wrapper, final Type<T> type) {
        return wrapper.read(Types.BOOLEAN) ? wrapper.read(type) : null;
    }

    // ---- Shape lifecycle ----

    private void upsertText(final long id, final Position3f location, final Float scale,
                            final Float totalTimeLeft, final Float maxRenderDistance, final Long attachedRuntimeId,
                            final String text, final boolean textRotation, final Integer backgroundColor, final boolean depthTest) {
        final ShapeInfo existing = shapes.get(id);
        if (existing != null && existing.matches(text, scale, location, attachedRuntimeId)) {
            return; // unchanged re-send — nothing to do
        }
        if (existing != null) {
            remove(id); // changed — recreate cleanly (avoids partial-update edge cases)
        }
        create(id, location, scale, totalTimeLeft, maxRenderDistance, attachedRuntimeId, text, textRotation, backgroundColor, depthTest);
    }

    private void create(final long id, final Position3f location, final Float scale,
                        final Float totalTimeLeft, final Float maxRenderDistance, final Long attachedRuntimeId,
                        final String text, final boolean textRotation, final Integer backgroundColor, final boolean depthTest) {
        if (TextUtil.stripFormatting(text).isEmpty() && !text.contains("\n")) {
            // Nothing visible to show.
            return;
        }
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) return;

        final int javaId = entityTracker.getNextJavaEntityId();
        final UUID uuid = UUID.randomUUID();
        final ShapeInfo info = new ShapeInfo(javaId, uuid, text, scale, location, attachedRuntimeId);

        // Resolve attachment: if attached to a (known) entity, ride it via SET_PASSENGERS so the
        // client keeps it in sync; otherwise spawn at the shape's absolute world location.
        double spawnX = location != null ? location.x() : 0;
        double spawnY = location != null ? location.y() : 0;
        double spawnZ = location != null ? location.z() : 0;
        int hostJavaId = -1;
        if (attachedRuntimeId != null) {
            final Entity host = entityTracker.getEntityByRid(attachedRuntimeId);
            if (host != null) {
                hostJavaId = host.javaId();
                final Position3f hostPos = host.position();
                if (hostPos != null) {
                    spawnX = hostPos.x();
                    spawnY = hostPos.y();
                    spawnZ = hostPos.z();
                }
            }
        }
        info.attachedHostJavaId = hostJavaId;

        // Finite lifespan → schedule auto-removal so the display does not linger forever.
        if (totalTimeLeft != null && totalTimeLeft > 0 && totalTimeLeft < Float.MAX_VALUE) {
            info.expiryTick = tickCounter + Math.round(totalTimeLeft * 20.0);
        }

        shapes.put(id, info);

        final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, this.user());
        addEntity.write(Types.VAR_INT, javaId); // entity id
        addEntity.write(Types.UUID, uuid); // uuid
        addEntity.write(Types.VAR_INT, EntityTypes1_21_11.TEXT_DISPLAY.getId()); // type id
        addEntity.write(Types.DOUBLE, spawnX); // x
        addEntity.write(Types.DOUBLE, spawnY); // y
        addEntity.write(Types.DOUBLE, spawnZ); // z
        addEntity.write(Types.MOVEMENT_VECTOR, Vector3d.ZERO); // velocity
        addEntity.write(Types.BYTE, (byte) 0); // pitch
        addEntity.write(Types.BYTE, (byte) 0); // yaw
        addEntity.write(Types.BYTE, (byte) 0); // head yaw
        addEntity.write(Types.VAR_INT, 0); // data
        addEntity.send(BedrockProtocol.class);

        sendEntityData(info, text, scale, maxRenderDistance, textRotation, backgroundColor, depthTest, hostJavaId != -1);

        if (hostJavaId != -1) {
            // When attached, the shape's location is an offset from the entity; SET_PASSENGERS keeps
            // the display synced to the host. The passenger attachment point differs from the entity
            // root, so the offset is approximate, but the display correctly follows the entity.
            refreshHostPassengers(hostJavaId);
        }
    }

    private void sendEntityData(final ShapeInfo info, final String text, final Float scale, final Float maxRenderDistance,
                                final boolean textRotation, final Integer backgroundColor, final boolean depthTest, final boolean attached) {
        final List<EntityData> data = new ArrayList<>();

        // TEXT — content (TextUtil restores § color codes and \n line breaks into a component)
        data.add(new EntityData(textDisplayIndex(EntityDataFields.TEXT), VersionedTypes.V26_1.entityDataTypes().componentType, TextUtil.stringToNbt(text)));

        // BILLBOARD — Bedrock text faces the camera unless useRotation is set.
        data.add(new EntityData(textDisplayIndex(EntityDataFields.BILLBOARD_RENDER_CONSTRAINTS), VersionedTypes.V26_1.entityDataTypes().byteType, textRotation ? BILLBOARD_FIXED : BILLBOARD_CENTER));

        // BACKGROUND_COLOR — explicit override or the vanilla-like semi-transparent black.
        data.add(new EntityData(textDisplayIndex(EntityDataFields.BACKGROUND_COLOR), VersionedTypes.V26_1.entityDataTypes().varIntType, backgroundColor != null ? backgroundColor : DEFAULT_BACKGROUND_COLOR));

        // TEXT_OPACITY — fully opaque.
        data.add(new EntityData(textDisplayIndex(EntityDataFields.TEXT_OPACITY), VersionedTypes.V26_1.entityDataTypes().byteType, (byte) -1));

        // VIEW_RANGE — Bedrock maximumRenderDistance is in blocks; Java view_range is a multiplier of ~64 blocks.
        final float viewRange = maxRenderDistance != null && maxRenderDistance > 0 ? maxRenderDistance / DEFAULT_VIEW_RANGE_BLOCKS : 1.0f;
        data.add(new EntityData(textDisplayIndex(EntityDataFields.VIEW_RANGE), VersionedTypes.V26_1.entityDataTypes().floatType, viewRange));

        // STYLE_FLAGS — see_through when the shape always renders (depthTest == false).
        data.add(new EntityData(textDisplayIndex(EntityDataFields.STYLE_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, depthTest ? (byte) 0 : STYLE_SEE_THROUGH));

        // SCALE — uniform scale when provided.
        if (scale != null && scale > 0) {
            data.add(new EntityData(textDisplayIndex(EntityDataFields.SCALE), VersionedTypes.V26_1.entityDataTypes().vector3FType, new Vector3f(scale, scale, scale)));
        }

        // TRANSLATION — when attached, apply the shape's location as the offset from the passenger
        // attachment point. When absolute, the entity is already at the shape location, so no offset.
        final Vector3f translation;
        if (attached && info.location != null) {
            translation = new Vector3f(info.location.x(), info.location.y(), info.location.z());
        } else {
            translation = new Vector3f(0f, 0f, 0f);
        }
        data.add(new EntityData(textDisplayIndex(EntityDataFields.TRANSLATION), VersionedTypes.V26_1.entityDataTypes().vector3FType, translation));

        final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, this.user());
        setEntityData.write(Types.VAR_INT, info.javaId);
        setEntityData.write(VersionedTypes.V26_1.entityDataList, data);
        setEntityData.send(BedrockProtocol.class);
    }

    private void remove(final long id) {
        final ShapeInfo info = shapes.remove(id);
        if (info == null) return;
        if (info.attachedHostJavaId != -1) {
            refreshHostPassengers(info.attachedHostJavaId);
        }
        final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, this.user());
        removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{info.javaId});
        removeEntities.send(BedrockProtocol.class);
    }

    public void clearAll() {
        // Java client clears all entities on dimension change, so just drop tracking state.
        shapes.clear();
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.clearSource(PASSENGER_SOURCE);
        }
    }

    // ---- Tick (finite lifespan expiry) ----

    public void tick() {
        tickCounter++;
        if (shapes.isEmpty()) return;
        List<Long> expired = null;
        for (final Map.Entry<Long, ShapeInfo> entry : shapes.entrySet()) {
            final Long expiryTick = entry.getValue().expiryTick;
            if (expiryTick != null && tickCounter >= expiryTick) {
                if (expired == null) expired = new ArrayList<>();
                expired.add(entry.getKey());
            }
        }
        if (expired != null) {
            for (final Long id : expired) {
                remove(id);
            }
        }
    }

    // ---- Helpers ----

    private void refreshHostPassengers(final int vehicleJavaId) {
        final int[] passengerJavaIds = shapes.values().stream()
                .filter(shape -> shape.attachedHostJavaId == vehicleJavaId)
                .mapToInt(shape -> shape.javaId)
                .toArray();
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.setVirtualPassengers(PASSENGER_SOURCE, vehicleJavaId, passengerJavaIds);
        }
    }

    private static int textDisplayIndex(final String fieldName) {
        final int index = BedrockProtocol.MAPPINGS.getJavaEntityDataFields()
                .get(EntityTypes1_21_11.TEXT_DISPLAY).indexOf(fieldName);
        if (index == -1) {
            throw new IllegalStateException("Unknown TEXT_DISPLAY entity data field: " + fieldName);
        }
        return index;
    }

    // ---- Inner classes ----

    private static final class ShapeInfo {
        final int javaId;
        final UUID uuid;
        final String text;
        final Float scale;
        final Position3f location;
        final Long attachedRuntimeId;
        int attachedHostJavaId = -1;
        Long expiryTick; // null = infinite

        ShapeInfo(final int javaId, final UUID uuid, final String text, final Float scale, final Position3f location, final Long attachedRuntimeId) {
            this.javaId = javaId;
            this.uuid = uuid;
            this.text = text;
            this.scale = scale;
            this.location = location;
            this.attachedRuntimeId = attachedRuntimeId;
        }

        boolean matches(final String text, final Float scale, final Position3f location, final Long attachedRuntimeId) {
            return java.util.Objects.equals(this.text, text)
                    && java.util.Objects.equals(this.scale, scale)
                    && java.util.Objects.equals(this.location, location)
                    && java.util.Objects.equals(this.attachedRuntimeId, attachedRuntimeId);
        }
    }

}
