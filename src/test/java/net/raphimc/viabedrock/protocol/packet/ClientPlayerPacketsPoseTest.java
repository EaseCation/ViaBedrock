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

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlayerPacketsPoseTest {

    @Test
    void sprintInWaterStartsSwimmingOnce() {
        assertTrue(ClientPlayerPackets.wantsJavaSwim(false, false, false, true, false, true));
        assertEquals(PlayerAuthInputPacket_InputData.StartSwimming,
                ClientPlayerPackets.swimTransitionFlag(true, false));
        assertNull(ClientPlayerPackets.swimTransitionFlag(true, true));
    }

    @Test
    void leavingWaterOrSprintStopsSwimming() {
        assertFalse(ClientPlayerPackets.wantsJavaSwim(false, false, false, false, false, true));
        assertFalse(ClientPlayerPackets.wantsJavaSwim(false, false, false, true, false, false));
        assertEquals(PlayerAuthInputPacket_InputData.StopSwimming,
                ClientPlayerPackets.swimTransitionFlag(false, true));
        assertNull(ClientPlayerPackets.swimTransitionFlag(false, false));
    }

    @Test
    void flyingGlidingOrRidingDoesNotStartSwimming() {
        assertFalse(ClientPlayerPackets.wantsJavaSwim(true, false, false, true, true, true));
        assertFalse(ClientPlayerPackets.wantsJavaSwim(false, true, false, true, true, true));
        assertFalse(ClientPlayerPackets.wantsJavaSwim(false, false, true, true, true, true));
    }

    @Test
    void landingWhileGlidingEmitsStopGliding() {
        assertTrue(ClientPlayerPackets.shouldStopGliding(true, true));
        assertFalse(ClientPlayerPackets.shouldStopGliding(true, false));
        assertFalse(ClientPlayerPackets.shouldStopGliding(false, true));
    }

    @Test
    void waterIdentifiersMatchMotFeetCheck() {
        assertTrue(ClientPlayerPackets.isWaterIdentifier("water"));
        assertTrue(ClientPlayerPackets.isWaterIdentifier("flowing_water"));
        assertFalse(ClientPlayerPackets.isWaterIdentifier("lava"));
        assertFalse(ClientPlayerPackets.isWaterIdentifier(null));
    }
}
