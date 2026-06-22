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
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.longs.LongArrayList;
import com.viaversion.viaversion.libs.fastutil.longs.LongList;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.PlayerEntity;
import net.raphimc.viabedrock.experimental.model.PlayerAuthInputContext;
import net.raphimc.viabedrock.experimental.riding.RidingAnchorHelper;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class RidingTracker extends StoredObject {

    private static final byte LINK_REMOVE = 0;
    private static final byte LINK_RIDE = 1;
    private static final byte LINK_PASSENGER = 2;
    private static final ActorDataIDs SEAT_OFFSET_DATA = ActorDataIDs.RESERVED_056; // Synapse SEAT_OFFSET = 56
    private static final float JAVA_PLAYER_VEHICLE_ATTACHMENT_Y = 0.6F; // PlayerEntity.VEHICLE_ATTACHMENT_POS

    private final Long2ObjectMap<LongList> vehiclePassengers = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<AnchorState> anchorsByPassenger = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<Position3f> seatOffsets = new Long2ObjectOpenHashMap<>();
    private Long localVehicleUniqueId;
    private boolean ridingShiftDown;
    private MoveVehicleInput lastMoveVehicleInput;

    public RidingTracker(final UserConnection user) {
        super(user);
    }

    public Entity localVehicle() {
        if (this.localVehicleUniqueId == null) {
            return null;
        }

        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return null;
        }

        final Entity vehicle = entityTracker.getEntityByUid(this.localVehicleUniqueId);
        if (vehicle == null) {
            this.clearLocalRiding();
        }
        return vehicle;
    }

    public boolean updateRidingShift(final boolean shiftDown) {
        final boolean pressed = shiftDown && !this.ridingShiftDown;
        this.ridingShiftDown = shiftDown;
        return pressed;
    }

    public boolean isLocalRiding() {
        return this.localVehicle() != null;
    }

    public void setLastMoveVehicleInput(final double x, final double y, final double z, final float yaw, final float pitch, final boolean onGround) {
        this.lastMoveVehicleInput = new MoveVehicleInput(new Position3f((float) x, (float) y, (float) z), yaw, pitch, onGround);
    }

    public void applyAuthInput(final ClientPlayerEntity clientPlayer, final PlayerAuthInputContext context) {
        final Entity vehicle = this.localVehicle();
        if (vehicle == null) {
            return;
        }

        final Position3f basePosition = vehicle.position();
        context.setPosition(new Position3f(basePosition.x(), basePosition.y() + clientPlayer.eyeOffset(), basePosition.z()));
        context.setDelta(Position3f.ZERO);

        final MoveVehicleInput vehicleInput = this.lastMoveVehicleInput;
        final float vehiclePitch = vehicleInput != null ? vehicleInput.pitch() : vehicle.rotation().x();
        final float vehicleYaw = vehicleInput != null ? vehicleInput.yaw() : vehicle.rotation().y();
        clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.IsInClientPredictedVehicle);
        context.setPredictedVehicle(vehicle.uniqueId(), vehiclePitch, vehicleYaw);
    }

    public void handleLink(final EntityLink link) {
        final long vehicleUniqueId = link.fromEntityUniqueId();
        final long passengerUniqueId = link.toEntityUniqueId();
        final byte type = link.type();

        if (type == LINK_REMOVE) {
            this.removePassenger(vehicleUniqueId, passengerUniqueId);
            this.updateLocalVehicle(passengerUniqueId, null);
            return;
        }

        if (type != LINK_RIDE && type != LINK_PASSENGER) {
            return;
        }

        this.removePassengerFromOtherVehicles(passengerUniqueId, vehicleUniqueId);
        final LongList passengers = this.vehiclePassengers.computeIfAbsent(vehicleUniqueId, k -> new LongArrayList());
        passengers.rem(passengerUniqueId);
        passengers.add(passengerUniqueId);
        this.updateLocalVehicle(passengerUniqueId, vehicleUniqueId);
        this.refreshVehicle(vehicleUniqueId);
    }

    public void onEntityAdded(final Entity entity) {
        this.updateSeatOffset(entity);
    }

    public void onEntityDataChanged(final Entity entity) {
        this.updateSeatOffset(entity);
        this.refreshVehicle(entity.uniqueId());
        for (final Long2ObjectMap.Entry<LongList> entry : this.vehiclePassengers.long2ObjectEntrySet()) {
            if (contains(entry.getValue(), entity.uniqueId())) {
                this.refreshVehicle(entry.getLongKey());
            }
        }
        final AnchorState anchor = this.anchorsByPassenger.get(entity.uniqueId());
        if (anchor != null) {
            this.refreshVehicle(anchor.vehicleUniqueId);
        }
    }

    public void onEntityMoved(final Entity entity) {
        this.refreshVehicle(entity.uniqueId());
    }

    public void onEntityRemoved(final Entity entity) {
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        final LongList passengers = this.vehiclePassengers.remove(entity.uniqueId());
        if (passengers != null) {
            for (int i = 0; i < passengers.size(); i++) {
                this.removeAnchor(passengers.getLong(i));
            }
            if (passengerTracker != null) {
                passengerTracker.clearVehicle(entity.javaId());
            }
        }

        this.removeAnchor(entity.uniqueId());
        this.seatOffsets.remove(entity.uniqueId());

        final LongList changedVehicles = new LongArrayList();
        for (final Long2ObjectMap.Entry<LongList> entry : this.vehiclePassengers.long2ObjectEntrySet()) {
            if (entry.getValue().rem(entity.uniqueId())) {
                changedVehicles.add(entry.getLongKey());
            }
        }
        for (final long vehicleUniqueId : changedVehicles) {
            final LongList vehiclePassengers = this.vehiclePassengers.get(vehicleUniqueId);
            if (vehiclePassengers != null && vehiclePassengers.isEmpty()) {
                this.vehiclePassengers.remove(vehicleUniqueId);
            }
            this.refreshVehicle(vehicleUniqueId);
        }

        if (this.localVehicleUniqueId != null && (this.localVehicleUniqueId == entity.uniqueId() || isLocalPlayer(entity))) {
            this.clearLocalRiding();
        }
    }

    private void removePassenger(final long vehicleUniqueId, final long passengerUniqueId) {
        final LongList passengers = this.vehiclePassengers.get(vehicleUniqueId);
        if (passengers != null) {
            passengers.rem(passengerUniqueId);
            if (passengers.isEmpty()) {
                this.vehiclePassengers.remove(vehicleUniqueId);
            }
        }
        this.removeAnchor(passengerUniqueId);
        this.refreshVehicle(vehicleUniqueId);
    }

    private void refreshVehicle(final long vehicleUniqueId) {
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (passengerTracker == null || entityTracker == null) {
            return;
        }

        final Entity vehicle = entityTracker.getEntityByUid(vehicleUniqueId);
        if (vehicle == null) {
            return;
        }

        final LongList passengerUids = this.vehiclePassengers.get(vehicleUniqueId);
        if (passengerUids == null || passengerUids.isEmpty()) {
            passengerTracker.setBedrockPassengers(vehicle.javaId());
            return;
        }

        if (usesVanillaRiding(vehicle)) {
            final com.viaversion.viaversion.libs.fastutil.ints.IntArrayList passengerJavaIds = new com.viaversion.viaversion.libs.fastutil.ints.IntArrayList(passengerUids.size());
            for (int i = 0; i < passengerUids.size(); i++) {
                final long passengerUniqueId = passengerUids.getLong(i);
                this.removeAnchor(passengerUniqueId);

                final Entity passenger = entityTracker.getEntityByUid(passengerUniqueId);
                if (passenger != null) {
                    passengerJavaIds.add(passenger.javaId());
                }
            }
            passengerTracker.setBedrockPassengers(vehicle.javaId(), passengerJavaIds.toIntArray());
            return;
        }

        passengerTracker.setBedrockPassengers(vehicle.javaId());
        for (int i = 0; i < passengerUids.size(); i++) {
            final Entity passenger = entityTracker.getEntityByUid(passengerUids.getLong(i));
            if (passenger != null) {
                this.ensureAnchor(vehicle, passenger);
            }
        }
    }

    private void ensureAnchor(final Entity vehicle, final Entity passenger) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (entityTracker == null || passengerTracker == null) {
            return;
        }

        AnchorState anchor = this.anchorsByPassenger.get(passenger.uniqueId());
        if (anchor == null) {
            anchor = new AnchorState(
                    entityTracker.getNextJavaEntityId(),
                    UUID.nameUUIDFromBytes(("viabedrock:riding-anchor:" + passenger.uniqueId()).getBytes(StandardCharsets.UTF_8)),
                    vehicle.uniqueId());
            this.anchorsByPassenger.put(passenger.uniqueId(), anchor);
        }
        anchor.vehicleUniqueId = vehicle.uniqueId();

        final Position3f rawOffset = this.rawSeatOffset(passenger);
        final float anchorYOffset = this.passengerAnchorYOffset(passenger);
        final Position3f position = vehicle.position().add(this.seatOffset(vehicle, passenger, rawOffset, anchorYOffset));
        if (!anchor.spawned) {
            RidingAnchorHelper.spawn(this.user(), anchor.javaId, anchor.uuid, position);
            anchor.spawned = true;
        }
        passengerTracker.setBedrockPassengers(anchor.javaId, passenger.javaId());
        // Force the client to recalculate the passenger position after the relation and zero-height anchor data arrive.
        RidingAnchorHelper.move(this.user(), anchor.javaId, position, vehicle.rotation(), vehicle.isOnGround());
    }

    private void removeAnchor(final long passengerUniqueId) {
        final AnchorState anchor = this.anchorsByPassenger.remove(passengerUniqueId);
        if (anchor == null || !anchor.spawned) {
            return;
        }

        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.clearVehicle(anchor.javaId);
        }
        RidingAnchorHelper.remove(this.user(), anchor.javaId);
    }

    private void removePassengerFromOtherVehicles(final long passengerUniqueId, final long newVehicleUniqueId) {
        final LongList changedVehicles = new LongArrayList();
        for (final Long2ObjectMap.Entry<LongList> entry : this.vehiclePassengers.long2ObjectEntrySet()) {
            if (entry.getLongKey() != newVehicleUniqueId && entry.getValue().rem(passengerUniqueId)) {
                changedVehicles.add(entry.getLongKey());
            }
        }
        for (final long vehicleUniqueId : changedVehicles) {
            final LongList passengers = this.vehiclePassengers.get(vehicleUniqueId);
            if (passengers != null && passengers.isEmpty()) {
                this.vehiclePassengers.remove(vehicleUniqueId);
            }
            this.refreshVehicle(vehicleUniqueId);
        }
    }

    private void updateSeatOffset(final Entity entity) {
        final EntityData data = entity.entityData().get(SEAT_OFFSET_DATA);
        if (data != null && data.getValue() instanceof Position3f offset) {
            this.seatOffsets.put(entity.uniqueId(), offset);
        }
    }

    private Position3f rawSeatOffset(final Entity passenger) {
        final Position3f offset = this.seatOffsets.get(passenger.uniqueId());
        if (offset == null) {
            return Position3f.ZERO;
        }
        return offset;
    }

    private Position3f seatOffset(final Entity vehicle, final Entity passenger, final Position3f offset, final float anchorYOffset) {
        // Bedrock seat offsets are local to the vehicle; anchors need absolute Java coordinates.
        final double yaw = Math.toRadians(vehicle.rotation().y());
        final float sin = (float) Math.sin(yaw);
        final float cos = (float) Math.cos(yaw);
        final float y = offset.y() + anchorYOffset;
        return new Position3f(
                offset.x() * cos - offset.z() * sin,
                y,
                offset.x() * sin + offset.z() * cos);
    }

    private float passengerAnchorYOffset(final Entity passenger) {
        if (passenger instanceof PlayerEntity) {
            // Bedrock player positions are network/base-offset coordinates (Nukkit EntityHuman#getBaseOffset = 1.62).
            // Java then subtracts the player's vehicle attachment from the anchor when applying SET_PASSENGERS.
            return JAVA_PLAYER_VEHICLE_ATTACHMENT_Y - passenger.eyeOffset();
        }
        return 0F;
    }

    private void updateLocalVehicle(final long passengerUniqueId, final Long vehicleUniqueId) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return;
        }

        final Entity clientPlayer = entityTracker.getClientPlayer();
        if (clientPlayer != null && clientPlayer.uniqueId() == passengerUniqueId) {
            this.localVehicleUniqueId = vehicleUniqueId;
            this.lastMoveVehicleInput = null;
        }
    }

    private void clearLocalRiding() {
        this.localVehicleUniqueId = null;
        this.ridingShiftDown = false;
        this.lastMoveVehicleInput = null;
    }

    private boolean isLocalPlayer(final Entity entity) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return false;
        }

        final Entity clientPlayer = entityTracker.getClientPlayer();
        return clientPlayer != null && clientPlayer == entity;
    }

    private static boolean usesVanillaRiding(final Entity vehicle) {
        final EntityTypes1_21_11 type = vehicle.javaType();
        return type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_BOAT)
                || type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_HORSE)
                || type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_MINECART)
                || type == EntityTypes1_21_11.PIG
                || type == EntityTypes1_21_11.STRIDER;
    }

    private static boolean contains(final LongList list, final long value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.getLong(i) == value) {
                return true;
            }
        }
        return false;
    }

    private static final class AnchorState {
        final int javaId;
        final UUID uuid;
        long vehicleUniqueId;
        boolean spawned;

        AnchorState(final int javaId, final UUID uuid, final long vehicleUniqueId) {
            this.javaId = javaId;
            this.uuid = uuid;
            this.vehicleUniqueId = vehicleUniqueId;
        }
    }

    private record MoveVehicleInput(Position3f position, float yaw, float pitch, boolean onGround) {
    }

}
