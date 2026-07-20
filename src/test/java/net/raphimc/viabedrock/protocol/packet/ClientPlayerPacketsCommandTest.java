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
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerCommandAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientPlayerPacketsCommandTest {

    @Test
    void translatesJavaPlayerCommandsToBedrockAuthInput() {
        assertEquals(PlayerAuthInputPacket_InputData.StartSprinting,
                ClientPlayerPackets.playerCommandInputData(PlayerCommandAction.START_SPRINTING));
        assertEquals(PlayerAuthInputPacket_InputData.StopSprinting,
                ClientPlayerPackets.playerCommandInputData(PlayerCommandAction.STOP_SPRINTING));
        assertEquals(PlayerAuthInputPacket_InputData.StartGliding,
                ClientPlayerPackets.playerCommandInputData(PlayerCommandAction.START_FALL_FLYING));
    }

    @Test
    void rejectsUnsupportedJavaPlayerCommands() {
        assertThrows(IllegalStateException.class,
                () -> ClientPlayerPackets.playerCommandInputData(PlayerCommandAction.OPEN_INVENTORY));
    }

}
