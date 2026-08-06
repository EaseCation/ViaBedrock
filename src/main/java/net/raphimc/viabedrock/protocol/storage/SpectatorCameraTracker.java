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

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.packet.ClientPlayerPackets;
import net.raphimc.viabedrock.protocol.packet.SpectatorCameraPackets;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.HashSet;
import java.util.Set;

public final class SpectatorCameraTracker extends StoredObject {

    private final EntityLookup entityLookup;
    private final PacketSink packetSink;
    private final Set<Long> spawnedPlayerRuntimeIds = new HashSet<>();
    private State state = State.DETACHED;
    private long targetRuntimeId = -1L;
    private int targetJavaId = -1;
    private boolean suppressShiftUntilRelease;
    private boolean invalidPayloadLogged;

    public SpectatorCameraTracker(final UserConnection user) {
        this(user, new EntityLookup() {
            @Override
            public Entity findByRuntimeId(final long runtimeId) {
                final EntityTracker entityTracker = user.get(EntityTracker.class);
                return entityTracker != null ? entityTracker.getEntityByRid(runtimeId) : null;
            }

            @Override
            public int ownJavaId() {
                final EntityTracker entityTracker = user.get(EntityTracker.class);
                final ClientPlayerEntity clientPlayer = entityTracker != null ? entityTracker.getClientPlayer() : null;
                return clientPlayer != null ? clientPlayer.javaId() : -1;
            }

            @Override
            public GameMode ownJavaGameMode() {
                final EntityTracker entityTracker = user.get(EntityTracker.class);
                final ClientPlayerEntity clientPlayer = entityTracker != null ? entityTracker.getClientPlayer() : null;
                return clientPlayer != null ? clientPlayer.javaGameMode() : GameMode.SURVIVAL;
            }
        }, new PacketSink() {
            @Override
            public void sendCamera(final int javaEntityId) {
                final PacketWrapper camera = PacketWrapper.create(ClientboundPackets26_1.SET_CAMERA, user);
                camera.write(Types.VAR_INT, javaEntityId);
                camera.send(BedrockProtocol.class);
            }

            @Override
            public void sendDetachRequest(final String reason) {
                final PacketWrapper request = PacketWrapper.create(ServerboundBedrockPackets.SCRIPT_MESSAGE, user);
                request.write(BedrockTypes.STRING, SpectatorCameraPackets.MESSAGE_ID);
                request.write(BedrockTypes.STRING, SpectatorCameraPackets.encodeDetachRequest(reason));
                request.sendToServer(BedrockProtocol.class);
            }

            @Override
            public void sendGameMode(final GameMode gameMode) {
                ClientPlayerPackets.sendJavaGameMode(user, gameMode);
            }

            @Override
            public void resendAbilities() {
                final EntityTracker entityTracker = user.get(EntityTracker.class);
                final ClientPlayerEntity clientPlayer = entityTracker != null ? entityTracker.getClientPlayer() : null;
                if (clientPlayer != null) {
                    clientPlayer.setAbilities(clientPlayer.abilities());
                }
            }
        });
    }

    SpectatorCameraTracker(final UserConnection user, final EntityLookup entityLookup, final PacketSink packetSink) {
        super(user);
        this.entityLookup = entityLookup;
        this.packetSink = packetSink;
    }

    public void attach(final long runtimeId) {
        if (this.state == State.DETACHED) {
            this.packetSink.sendGameMode(GameMode.SPECTATOR);
        }

        final Entity target = this.entityLookup.findByRuntimeId(runtimeId);
        if (target == null || !this.spawnedPlayerRuntimeIds.contains(runtimeId)) {
            if (this.hasActiveCamera() && this.targetRuntimeId != runtimeId) {
                this.sendOwnCamera();
            }
            this.state = State.PENDING_TARGET;
            this.targetRuntimeId = runtimeId;
            this.targetJavaId = -1;
            return;
        }

        if (this.hasActiveCamera() && this.targetRuntimeId == runtimeId && this.targetJavaId == target.javaId()) {
            this.state = State.ATTACHED;
            return;
        }

        this.packetSink.sendCamera(target.javaId());
        this.state = State.ATTACHED;
        this.targetRuntimeId = runtimeId;
        this.targetJavaId = target.javaId();
    }

    public void detach() {
        if (this.state == State.DETACHED) return;

        if (this.hasActiveCamera()) {
            this.sendOwnCamera();
        }
        this.restoreOwnPresentation();
        this.clearTarget();
    }

    public void onJavaPlayerSpawned(final Entity entity) {
        this.spawnedPlayerRuntimeIds.add(entity.runtimeId());
        if (this.state == State.PENDING_TARGET && this.targetRuntimeId == entity.runtimeId()) {
            this.attach(entity.runtimeId());
        }
    }

    public void onEntityRemoved(final Entity entity) {
        this.spawnedPlayerRuntimeIds.remove(entity.runtimeId());
        if (this.state == State.DETACHED || this.targetRuntimeId != entity.runtimeId()) return;

        if (this.hasActiveCamera()) {
            this.sendOwnCamera();
        }
        this.restoreOwnPresentation();
        this.clearTarget();
        this.packetSink.sendDetachRequest("target_removed");
    }

    public void onDimensionChange() {
        this.spawnedPlayerRuntimeIds.clear();
        if (this.state == State.DETACHED) return;

        if (this.hasActiveCamera()) {
            this.sendOwnCamera();
        }
        this.restoreOwnPresentation();
        this.clearTarget();
        this.packetSink.sendDetachRequest("dimension_change");
    }

    public boolean handleShiftInput(final boolean pressed) {
        if (!pressed) {
            this.suppressShiftUntilRelease = false;
            return false;
        }
        if (this.suppressShiftUntilRelease) return true;
        if (this.state == State.DETACHED) return false;

        this.suppressShiftUntilRelease = true;
        if (this.state != State.DETACH_REQUESTED) {
            this.state = State.DETACH_REQUESTED;
            this.packetSink.sendDetachRequest("sneak");
        }
        return true;
    }

    public Position3f javaListenerPosition() {
        if (!this.hasActiveCamera()) return null;

        final Entity target = this.entityLookup.findByRuntimeId(this.targetRuntimeId);
        return target != null ? target.position() : null;
    }

    public boolean markInvalidPayloadLogged() {
        if (this.invalidPayloadLogged) return false;

        this.invalidPayloadLogged = true;
        return true;
    }

    public GameMode projectJavaGameMode(final GameMode gameMode) {
        return this.state == State.DETACHED ? gameMode : GameMode.SPECTATOR;
    }

    State state() {
        return this.state;
    }

    long targetRuntimeId() {
        return this.targetRuntimeId;
    }

    boolean suppressShiftUntilRelease() {
        return this.suppressShiftUntilRelease;
    }

    private boolean hasActiveCamera() {
        return this.state == State.ATTACHED || this.state == State.DETACH_REQUESTED;
    }

    private void clearTarget() {
        this.state = State.DETACHED;
        this.targetRuntimeId = -1L;
        this.targetJavaId = -1;
    }

    private void sendOwnCamera() {
        final int ownJavaId = this.entityLookup.ownJavaId();
        if (ownJavaId >= 0) {
            this.packetSink.sendCamera(ownJavaId);
        }
    }

    private void restoreOwnPresentation() {
        this.packetSink.sendGameMode(this.entityLookup.ownJavaGameMode());
        this.packetSink.resendAbilities();
    }

    interface EntityLookup {

        Entity findByRuntimeId(long runtimeId);

        int ownJavaId();

        GameMode ownJavaGameMode();
    }

    interface PacketSink {

        void sendCamera(int javaEntityId);

        void sendDetachRequest(String reason);

        void sendGameMode(GameMode gameMode);

        void resendAbilities();
    }

    enum State {
        DETACHED,
        PENDING_TARGET,
        ATTACHED,
        DETACH_REQUESTED
    }

}
