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

import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlayerPacketsImmobileTest {

    @Test
    void removesMovementInputsButPreservesStateTransitionsAndInteractions() {
        final Set<PlayerAuthInputPacket_InputData> inputData = EnumSet.of(
                PlayerAuthInputPacket_InputData.Up,
                PlayerAuthInputPacket_InputData.JumpDown,
                PlayerAuthInputPacket_InputData.WantDown,
                PlayerAuthInputPacket_InputData.StartJumping,
                PlayerAuthInputPacket_InputData.StartSprinting,
                PlayerAuthInputPacket_InputData.StopSprinting,
                PlayerAuthInputPacket_InputData.StopSneaking,
                PlayerAuthInputPacket_InputData.PerformItemInteraction,
                PlayerAuthInputPacket_InputData.PerformBlockActions,
                PlayerAuthInputPacket_InputData.MissedSwing
        );

        ClientPlayerPackets.removeImmobileMovementInput(inputData);

        assertFalse(inputData.contains(PlayerAuthInputPacket_InputData.Up));
        assertFalse(inputData.contains(PlayerAuthInputPacket_InputData.JumpDown));
        assertFalse(inputData.contains(PlayerAuthInputPacket_InputData.WantDown));
        assertFalse(inputData.contains(PlayerAuthInputPacket_InputData.StartJumping));
        assertTrue(inputData.contains(PlayerAuthInputPacket_InputData.StartSprinting));
        assertTrue(inputData.contains(PlayerAuthInputPacket_InputData.StopSprinting));
        assertTrue(inputData.contains(PlayerAuthInputPacket_InputData.StopSneaking));
        assertTrue(inputData.contains(PlayerAuthInputPacket_InputData.PerformItemInteraction));
        assertTrue(inputData.contains(PlayerAuthInputPacket_InputData.PerformBlockActions));
        assertTrue(inputData.contains(PlayerAuthInputPacket_InputData.MissedSwing));
    }

    @Test
    void usesAbsoluteNeteaseLevelGravityForAuthInput() {
        assertEquals(ProtocolConstants.PLAYER_GRAVITY, ClientPlayerPackets.neteaseAuthInputGravity((Float) null));
        assertEquals(0.08f, ClientPlayerPackets.neteaseAuthInputGravity(-0.08f), 0.0001f);
        assertEquals(0.16f, ClientPlayerPackets.neteaseAuthInputGravity(0.16f), 0.0001f);
    }

}
