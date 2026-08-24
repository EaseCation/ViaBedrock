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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlayerPacketsJumpBreakTest {

    @Test
    void startJumpingPulsesOncePerPressWhileGrounded() {
        assertTrue(ClientPlayerPackets.shouldEmitStartJumping(true, true, true, false));
        assertFalse(ClientPlayerPackets.shouldEmitStartJumping(true, true, true, true));
        assertFalse(ClientPlayerPackets.shouldEmitStartJumping(true, true, false, false));
    }

    @Test
    void startJumpingPulsesAgainAfterLandingWithJumpHeld() {
        assertTrue(ClientPlayerPackets.shouldEmitStartJumping(false, true, true, true));
        assertFalse(ClientPlayerPackets.shouldEmitStartJumping(false, false, true, true));
    }

    @Test
    void startJumpingNeverPulsesWhileRiding() {
        assertFalse(ClientPlayerPackets.shouldEmitStartJumping(true, true, true, false, true));
        assertFalse(ClientPlayerPackets.shouldEmitStartJumping(false, true, true, true, true));
        assertFalse(ClientPlayerPackets.shouldEmitStartJumping(true, false, true, false, true));
        assertTrue(ClientPlayerPackets.shouldEmitStartJumping(true, true, true, false, false));
    }

    @Test
    void abortDestroyFacingPrefersPacketThenCachedStartFace() {
        final ClientPlayerEntity.BlockBreakingInfo info = new ClientPlayerEntity.BlockBreakingInfo(
                new BlockPosition(1, 64, 1), Direction.NORTH);
        assertEquals(Direction.UP.ordinal(), ClientPlayerPackets.abortDestroyFacing(Direction.UP, info));
        assertEquals(Direction.NORTH.ordinal(), ClientPlayerPackets.abortDestroyFacing(null, info));
        assertEquals(Direction.DOWN.ordinal(), ClientPlayerPackets.abortDestroyFacing(null, null));
    }

    @Test
    void discardPendingAuthInputClearsNullSafe() {
        ClientPlayerPackets.discardPendingAuthInput(null);
    }
}
