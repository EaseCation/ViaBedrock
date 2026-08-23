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

import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import org.junit.jupiter.api.Test;

import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.packet.EntityPacketLayout;

import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.BOAT_PREDICTED;
import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.PASSENGER_ONLY;
import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.VIRTUAL_INPUT_ONLY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RidingTrackerTest {

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
    void predictedBoatAuthInputUsesBoatNetworkOffsetNotPlayerEye() {
        final Position3f javaBoat = new Position3f(10F, 64F, -3F);
        final Position3f auth = RidingTracker.predictedBoatAuthInputPosition(javaBoat, 0.375F);
        assertEquals(10F, auth.x());
        assertEquals(64.375F, auth.y(), 1.0e-6F);
        assertEquals(-3F, auth.z());
        assertTrue(auth.y() - javaBoat.y() < 1.0F, "player eye 1.62 would lift the boat into GanAC FlyCheck");
    }

    @Test
    void doesNotForwardInputFromNonControllingPassengers() {
        assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.MINECART, false));
    }

    @Test
    void mapsJavaPaddleBooleansToMotRowActions() {
        assertAll(
                () -> assertTrue(EntityPacketLayout.isRowAction(EntityPacketLayout.ROW_LEFT_ACTION)),
                () -> assertTrue(EntityPacketLayout.isRowAction(EntityPacketLayout.ROW_RIGHT_ACTION)),
                () -> assertFalse(EntityPacketLayout.isRowAction(1)),
                () -> assertEquals(129, EntityPacketLayout.ROW_LEFT_ACTION),
                () -> assertEquals(128, EntityPacketLayout.ROW_RIGHT_ACTION)
        );
    }

    @Test
    void preservesExistingVanillaPassengerModes() {
        assertAll(
                () -> assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.HORSE, true)),
                () -> assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.PIG, true)),
                () -> assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.STRIDER, true))
        );
    }

}
