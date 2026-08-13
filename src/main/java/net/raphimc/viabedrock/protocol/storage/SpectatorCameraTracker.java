/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class SpectatorCameraTracker extends StoredObject {

    private final EntityLookup entityLookup;
    private final PacketSink packetSink;
    private final SpectatorMenuProjection menuProjection;
    private final Set<Long> spawnedPlayerRuntimeIds = new HashSet<>();
    private State state = State.DETACHED;
    private UUID sessionId;
    private long generation = -1L;
    private long targetRuntimeId = -1L;
    private int targetJavaId = -1;
    private boolean spectatorPresentation;
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
            public void sendSessionReady(final UUID sessionId, final long generation) {
                sendRequest(user, SpectatorCameraPackets.MESSAGE_ID_V2,
                        SpectatorCameraPackets.encodeSessionReady(sessionId, generation));
            }

            @Override
            public void sendDetachRequest(final UUID sessionId, final long generation, final String reason) {
                sendRequest(user, SpectatorCameraPackets.MESSAGE_ID_V2,
                        SpectatorCameraPackets.encodeDetachRequest(sessionId, generation, reason));
            }

            @Override
            public void sendTargetRequest(final UUID sessionId, final long generation, final UUID targetId) {
                sendRequest(user, SpectatorCameraPackets.MESSAGE_ID_V2,
                        SpectatorCameraPackets.encodeTargetRequest(sessionId, generation, targetId));
            }

            @Override
            public void sendLegacyDetachRequest(final String reason) {
                sendRequest(user, SpectatorCameraPackets.MESSAGE_ID_V1,
                        SpectatorCameraPackets.encodeLegacyDetachRequest(reason));
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
        }, user.get(SpectatorMenuProjection.class));
    }

    SpectatorCameraTracker(
            final UserConnection user,
            final EntityLookup entityLookup,
            final PacketSink packetSink,
            final SpectatorMenuProjection menuProjection
    ) {
        super(user);
        this.entityLookup = entityLookup;
        this.packetSink = packetSink;
        this.menuProjection = menuProjection;
    }

    public void beginSession(
            final UUID sessionId,
            final long generation,
            final List<SpectatorCameraPackets.Target> targets,
            final List<SpectatorCameraPackets.Team> teams
    ) {
        if (this.sessionId == null && this.state != State.DETACHED) {
            this.detachCamera(true);
        }
        if (this.sessionId != null && !this.sessionId.equals(sessionId)) {
            this.detachCamera(false);
        }
        this.sessionId = sessionId;
        this.generation = generation;
        this.state = State.DETACHED;
        this.clearTarget();
        this.menuProjection.begin(targets, teams);
    }

    public void confirmSession() {
        if (this.sessionId != null) {
            this.packetSink.sendSessionReady(this.sessionId, this.generation);
        }
    }

    public void attachLegacy(final long runtimeId) {
        if (this.sessionId != null) return;
        this.attachCamera(runtimeId);
    }

    public void detachLegacy() {
        if (this.sessionId == null) {
            this.detachCamera(true);
        }
    }

    public void replaceTargets(
            final UUID sessionId,
            final long generation,
            final List<SpectatorCameraPackets.Target> targets,
            final List<SpectatorCameraPackets.Team> teams
    ) {
        if (!this.matches(sessionId, generation)) return;
        this.menuProjection.replace(targets, teams);
    }

    public void attach(final UUID sessionId, final long generation, final long runtimeId) {
        if (!this.matchesSession(sessionId) || generation < this.generation) return;
        this.generation = generation;
        this.attachCamera(runtimeId);
    }

    private void attachCamera(final long runtimeId) {
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

        this.showSpectatorPresentation();
        if (this.hasActiveCamera() && this.targetRuntimeId == runtimeId && this.targetJavaId == target.javaId()) {
            this.state = State.ATTACHED;
            return;
        }

        this.packetSink.sendCamera(target.javaId());
        this.state = State.ATTACHED;
        this.targetRuntimeId = runtimeId;
        this.targetJavaId = target.javaId();
    }

    public void detachTarget(final UUID sessionId, final long generation) {
        if (!this.matches(sessionId, generation)) return;
        this.detachCamera(true);
    }

    public void endSession(final UUID sessionId) {
        if (!this.matchesSession(sessionId)) return;
        this.detachCamera(true);
        this.menuProjection.clear();
        this.sessionId = null;
        this.generation = -1L;
    }

    public boolean requestTarget(final UUID targetId) {
        if (this.sessionId == null || !this.menuProjection.contains(targetId)) return false;
        this.packetSink.sendTargetRequest(this.sessionId, this.generation, targetId);
        return true;
    }

    public void onJavaPlayerSpawned(final Entity entity) {
        this.spawnedPlayerRuntimeIds.add(entity.runtimeId());
        if (this.state == State.PENDING_TARGET && this.targetRuntimeId == entity.runtimeId()) {
            this.attachCamera(entity.runtimeId());
        }
    }

    public void onEntityRemoved(final Entity entity) {
        this.spawnedPlayerRuntimeIds.remove(entity.runtimeId());
        if (this.state == State.DETACHED || this.targetRuntimeId != entity.runtimeId()) return;

        this.detachCamera(true);
        if (this.sessionId != null) {
            this.packetSink.sendDetachRequest(this.sessionId, this.generation, "target_removed");
        } else {
            this.packetSink.sendLegacyDetachRequest("target_removed");
        }
    }

    public void onDimensionChange() {
        this.spawnedPlayerRuntimeIds.clear();
        final boolean hadTarget = this.state != State.DETACHED;
        if (hadTarget || this.spectatorPresentation) {
            this.detachCamera(true);
            if (!hadTarget) return;
            if (this.sessionId != null) {
                this.packetSink.sendDetachRequest(this.sessionId, this.generation, "dimension_change");
            } else {
                this.packetSink.sendLegacyDetachRequest("dimension_change");
            }
        }
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
            if (this.sessionId != null) {
                this.packetSink.sendDetachRequest(this.sessionId, this.generation, "sneak");
            } else {
                this.packetSink.sendLegacyDetachRequest("sneak");
            }
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
        return this.spectatorPresentation ? GameMode.SPECTATOR : gameMode;
    }

    public boolean acceptsJavaGameModeChange() {
        return !this.spectatorPresentation;
    }

    public void restorePresentationAfterClientReset() {
        if (this.spectatorPresentation) {
            this.packetSink.sendGameMode(GameMode.SPECTATOR);
        } else {
            this.packetSink.resendAbilities();
        }
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

    private boolean matchesSession(final UUID sessionId) {
        return this.sessionId != null && this.sessionId.equals(sessionId);
    }

    private boolean matches(final UUID sessionId, final long generation) {
        return this.matchesSession(sessionId) && this.generation == generation;
    }

    private boolean hasActiveCamera() {
        return this.state == State.ATTACHED || this.state == State.DETACH_REQUESTED;
    }

    private void detachCamera(final boolean restorePresentation) {
        if (this.hasActiveCamera()) {
            this.sendOwnCamera();
        }
        if (restorePresentation) {
            this.restoreOwnPresentation();
        }
        this.state = State.DETACHED;
        this.clearTarget();
    }

    private void clearTarget() {
        this.targetRuntimeId = -1L;
        this.targetJavaId = -1;
    }

    private void sendOwnCamera() {
        final int ownJavaId = this.entityLookup.ownJavaId();
        if (ownJavaId >= 0) {
            this.packetSink.sendCamera(ownJavaId);
        }
    }

    private void showSpectatorPresentation() {
        if (this.spectatorPresentation) return;
        this.spectatorPresentation = true;
        this.menuProjection.setOwnSpectatorPresentation(true);
        this.packetSink.sendGameMode(GameMode.SPECTATOR);
    }

    private void restoreOwnPresentation() {
        if (!this.spectatorPresentation) return;
        this.spectatorPresentation = false;
        this.menuProjection.setOwnSpectatorPresentation(false);
        this.packetSink.sendGameMode(this.entityLookup.ownJavaGameMode());
        this.packetSink.resendAbilities();
    }

    private static void sendRequest(final UserConnection user, final String messageId, final String payload) {
        final PacketWrapper request = PacketWrapper.create(ServerboundBedrockPackets.SCRIPT_MESSAGE, user);
        request.write(BedrockTypes.STRING, messageId);
        request.write(BedrockTypes.STRING, payload);
        request.sendToServer(BedrockProtocol.class);
    }

    interface EntityLookup {
        Entity findByRuntimeId(long runtimeId);
        int ownJavaId();
        GameMode ownJavaGameMode();
    }

    interface PacketSink {
        void sendCamera(int javaEntityId);
        void sendSessionReady(UUID sessionId, long generation);
        void sendDetachRequest(UUID sessionId, long generation, String reason);
        void sendTargetRequest(UUID sessionId, long generation, UUID targetId);
        void sendLegacyDetachRequest(String reason);
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
