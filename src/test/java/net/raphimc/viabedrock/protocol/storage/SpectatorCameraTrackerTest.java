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

import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.model.Position3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectatorCameraTrackerTest {

    private static final int OWN_JAVA_ID = 1;

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
    private final Map<Long, Entity> entities = new HashMap<>();
    private final List<Integer> cameraPackets = new ArrayList<>();
    private final List<String> detachRequests = new ArrayList<>();
    private final List<GameMode> gameModes = new ArrayList<>();
    private int abilityResends;
    private SpectatorCameraTracker tracker;

    @BeforeEach
    void setUp() {
        this.tracker = new SpectatorCameraTracker(this.user, new SpectatorCameraTracker.EntityLookup() {
            @Override
            public Entity findByRuntimeId(final long runtimeId) {
                return entities.get(runtimeId);
            }

            @Override
            public int ownJavaId() {
                return OWN_JAVA_ID;
            }

            @Override
            public GameMode ownJavaGameMode() {
                return GameMode.CREATIVE;
            }
        }, new SpectatorCameraTracker.PacketSink() {
            @Override
            public void sendCamera(final int javaEntityId) {
                cameraPackets.add(javaEntityId);
            }

            @Override
            public void sendDetachRequest(final String reason) {
                detachRequests.add(reason);
            }

            @Override
            public void sendGameMode(final GameMode gameMode) {
                gameModes.add(gameMode);
            }

            @Override
            public void resendAbilities() {
                abilityResends++;
            }
        });
    }

    @AfterEach
    void tearDown() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void waitsUntilTargetWasSentToJavaClient() {
        final Entity target = this.entity(10L, 40, new Position3f(1F, 2F, 3F));

        this.tracker.attach(target.runtimeId());
        assertEquals(SpectatorCameraTracker.State.PENDING_TARGET, this.tracker.state());
        assertTrue(this.cameraPackets.isEmpty());
        assertEquals(List.of(GameMode.SPECTATOR), this.gameModes);

        this.tracker.onJavaPlayerSpawned(target);
        assertEquals(SpectatorCameraTracker.State.ATTACHED, this.tracker.state());
        assertEquals(List.of(40), this.cameraPackets);

        this.tracker.attach(target.runtimeId());
        assertEquals(List.of(40), this.cameraPackets);

        this.tracker.detach();
        this.tracker.detach();
        assertEquals(List.of(40, OWN_JAVA_ID), this.cameraPackets);
        assertEquals(List.of(GameMode.SPECTATOR, GameMode.CREATIVE), this.gameModes);
        assertEquals(1, this.abilityResends);
        assertEquals(SpectatorCameraTracker.State.DETACHED, this.tracker.state());
    }

    @Test
    void restoresOwnCameraBeforeWaitingForANewTarget() {
        final Entity first = this.entity(10L, 40, Position3f.ZERO);
        final Entity second = this.entity(11L, 41, Position3f.ZERO);
        this.tracker.onJavaPlayerSpawned(first);
        this.tracker.attach(first.runtimeId());

        this.tracker.attach(second.runtimeId());
        assertEquals(SpectatorCameraTracker.State.PENDING_TARGET, this.tracker.state());
        assertEquals(List.of(40, OWN_JAVA_ID), this.cameraPackets);

        this.tracker.onJavaPlayerSpawned(second);
        assertEquals(SpectatorCameraTracker.State.ATTACHED, this.tracker.state());
        assertEquals(List.of(40, OWN_JAVA_ID, 41), this.cameraPackets);
    }

    @Test
    void detachesWhenTargetIsRemovedOrDimensionChanges() {
        final Entity target = this.entity(10L, 40, Position3f.ZERO);
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attach(target.runtimeId());
        this.tracker.onEntityRemoved(target);

        assertEquals(List.of(40, OWN_JAVA_ID), this.cameraPackets);
        assertEquals(List.of("target_removed"), this.detachRequests);
        assertEquals(SpectatorCameraTracker.State.DETACHED, this.tracker.state());

        this.tracker.attach(99L);
        this.tracker.onDimensionChange();
        assertEquals(List.of(40, OWN_JAVA_ID), this.cameraPackets);
        assertEquals(List.of("target_removed", "dimension_change"), this.detachRequests);
    }

    @Test
    void consumesShiftUntilReleaseWithoutLeavingSneakState() {
        final Entity target = this.entity(10L, 40, Position3f.ZERO);
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attach(target.runtimeId());

        assertTrue(this.tracker.handleShiftInput(true));
        assertTrue(this.tracker.handleShiftInput(true));
        assertEquals(List.of("sneak"), this.detachRequests);
        assertEquals(SpectatorCameraTracker.State.DETACH_REQUESTED, this.tracker.state());

        this.tracker.detach();
        assertTrue(this.tracker.handleShiftInput(true));
        assertFalse(this.tracker.handleShiftInput(false));
        assertFalse(this.tracker.suppressShiftUntilRelease());
        assertFalse(this.tracker.handleShiftInput(true));
        assertEquals(List.of("sneak"), this.detachRequests);
    }

    @Test
    void usesAttachedTargetAsJavaSoundListener() {
        final Position3f position = new Position3f(4F, 5F, 6F);
        final Entity target = this.entity(10L, 40, position);

        assertNull(this.tracker.javaListenerPosition());
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attach(target.runtimeId());
        assertEquals(position, this.tracker.javaListenerPosition());

        this.tracker.detach();
        assertNull(this.tracker.javaListenerPosition());
    }

    @Test
    void keepsIncomingGameModeUpdatesInSpectatorProjectionUntilDetach() {
        final Entity target = this.entity(10L, 40, Position3f.ZERO);

        assertEquals(GameMode.ADVENTURE, this.tracker.projectJavaGameMode(GameMode.ADVENTURE));
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attach(target.runtimeId());
        assertEquals(GameMode.SPECTATOR, this.tracker.projectJavaGameMode(GameMode.ADVENTURE));

        this.tracker.detach();
        assertEquals(GameMode.ADVENTURE, this.tracker.projectJavaGameMode(GameMode.ADVENTURE));
    }

    private Entity entity(final long runtimeId, final int javaId, final Position3f position) {
        final Entity entity = new Entity(this.user, runtimeId, runtimeId, "minecraft:player", javaId,
                UUID.randomUUID(), EntityTypes1_21_11.PLAYER);
        entity.setPosition(position);
        this.entities.put(runtimeId, entity);
        return entity;
    }

}
