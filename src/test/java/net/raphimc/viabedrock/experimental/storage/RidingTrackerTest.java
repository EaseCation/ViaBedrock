/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.experimental.model.PlayerAuthInputContext;
import net.raphimc.viabedrock.protocol.data.enums.java.Relative;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.BOAT_PREDICTED;
import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.PASSENGER_ONLY;
import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.VIRTUAL_INPUT_ONLY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RidingTrackerTest {

    private static final long PLAYER_UNIQUE_ID = 7L;
    private static final long BOAT_UNIQUE_ID = 42L;
    private static final byte LINK_REMOVE = 0;
    private static final byte LINK_RIDE = 1;
    private static final Position3f BOAT_POSITION = new Position3f(12.5F, 64F, -4.5F);
    private static final Position3f EXPECTED_SAFE_POSITION = new Position3f(
            BOAT_POSITION.x(), BOAT_POSITION.y() + 1.62F + 1.02001F, BOAT_POSITION.z());

    @Test
    void forwardsDirectionalInputForControllableMinecarts() {
        assertAll(
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.MINECART, true)),
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.CHEST_MINECART, true))
        );
    }

    @Test
    void keepsBoatPredictionSeparateFromMinecartInput() {
        assertEquals(BOAT_PREDICTED, RidingTracker.localRidingMode(EntityTypes1_21_11.OAK_BOAT, true));
    }

    @Test
    void doesNotForwardInputFromNonControllingPassengers() {
        assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.MINECART, false));
    }

    @Test
    void preservesExistingVanillaPassengerModes() {
        assertAll(
                () -> assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.HORSE, true)),
                () -> assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.PIG, true)),
                () -> assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.STRIDER, true))
        );
    }

    @Test
    void serverLinkRemovalSynchronizesTheLocalPlayerAboveTheBoat() {
        final RidingFixture fixture = ridingFixture();
        try {
            fixture.cacheCurrentSafePosition();

            fixture.tracker().handleLink(new EntityLink(
                    BOAT_UNIQUE_ID, PLAYER_UNIQUE_ID, LINK_REMOVE, false, false, 0F));

            assertEquals(EXPECTED_SAFE_POSITION, fixture.clientPlayer().position());
            assertEquals(Relative.ROTATION, fixture.clientPlayer().positionSyncRelatives());
            assertNull(fixture.tracker().localVehicle());
        } finally {
            fixture.channel().finishAndReleaseAll();
        }
    }

    @Test
    void vehicleRemovalBeforeTheFirstRidingTickUsesTheCurrentBoatPosition() {
        final RidingFixture fixture = ridingFixture();
        try {
            fixture.tracker().onEntityRemoved(fixture.boat());

            assertEquals(EXPECTED_SAFE_POSITION, fixture.clientPlayer().position());
            assertEquals(Relative.ROTATION, fixture.clientPlayer().positionSyncRelatives());
            assertNull(fixture.tracker().localVehicle());
        } finally {
            fixture.channel().finishAndReleaseAll();
        }
    }

    @Test
    void authoritativeServerPositionWinsAfterTheDismountSync() {
        final RidingFixture fixture = ridingFixture();
        try {
            fixture.cacheCurrentSafePosition();
            fixture.tracker().handleLink(new EntityLink(
                    BOAT_UNIQUE_ID, PLAYER_UNIQUE_ID, LINK_REMOVE, false, false, 0F));
            final Position3f serverPosition = new Position3f(100F, 81.62F, -30F);
            fixture.clientPlayer().setPosition(serverPosition);
            final PlayerAuthInputContext context = new PlayerAuthInputContext(serverPosition, Position3f.ZERO);

            fixture.tracker().applyAuthInput(fixture.clientPlayer(), context);

            assertEquals(serverPosition, context.position());
            assertNull(fixture.tracker().localVehicle());
        } finally {
            fixture.channel().finishAndReleaseAll();
        }
    }

    private static RidingFixture ridingFixture() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final UserConnectionImpl user = new UserConnectionImpl(channel);
        final FixtureClientPlayerEntity clientPlayer = new FixtureClientPlayerEntity(
                user,
                PLAYER_UNIQUE_ID,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                new PlayerAbilities(PLAYER_UNIQUE_ID, (byte) 0, (byte) 0));
        clientPlayer.setPosition(new Position3f(0F, 65.62F, 0F));
        final Entity boat = new Entity(
                user,
                BOAT_UNIQUE_ID,
                420L,
                "minecraft:boat",
                2,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                EntityTypes1_21_11.OAK_BOAT);
        boat.setPosition(BOAT_POSITION);

        user.getStoredObjects().put(EntityTracker.class, new FixtureEntityTracker(user, clientPlayer, boat));
        final RidingTracker tracker = new RidingTracker(user);
        tracker.handleLink(new EntityLink(
                BOAT_UNIQUE_ID, PLAYER_UNIQUE_ID, LINK_RIDE, false, false, 0F));
        return new RidingFixture(channel, tracker, clientPlayer, boat);
    }

    private record RidingFixture(
            EmbeddedChannel channel,
            RidingTracker tracker,
            FixtureClientPlayerEntity clientPlayer,
            Entity boat
    ) {

        void cacheCurrentSafePosition() {
            this.tracker.applyAuthInput(
                    this.clientPlayer,
                    new PlayerAuthInputContext(this.clientPlayer.position(), Position3f.ZERO));
        }
    }

    private static final class FixtureClientPlayerEntity extends ClientPlayerEntity {

        private Set<Relative> positionSyncRelatives;

        FixtureClientPlayerEntity(
                final UserConnection user,
                final long runtimeId,
                final UUID javaUuid,
                final PlayerAbilities abilities
        ) {
            super(user, runtimeId, javaUuid, abilities);
        }

        @Override
        public void beginPositionSync(final Set<Relative> relatives) {
            this.positionSyncRelatives = relatives;
        }

        Set<Relative> positionSyncRelatives() {
            return this.positionSyncRelatives;
        }
    }

    private static final class FixtureEntityTracker extends EntityTracker {

        private final ClientPlayerEntity clientPlayer;
        private final Entity boat;

        FixtureEntityTracker(final UserConnection user, final ClientPlayerEntity clientPlayer, final Entity boat) {
            super(user);
            this.clientPlayer = clientPlayer;
            this.boat = boat;
        }

        @Override
        public ClientPlayerEntity getClientPlayer() {
            return this.clientPlayer;
        }

        @Override
        public Entity getEntityByUid(final long uniqueId) {
            if (uniqueId == this.clientPlayer.uniqueId()) {
                return this.clientPlayer;
            }
            if (uniqueId == this.boat.uniqueId()) {
                return this.boat;
            }
            return null;
        }
    }

}
