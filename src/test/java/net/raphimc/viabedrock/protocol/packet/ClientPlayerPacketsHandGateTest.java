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

import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerActionAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlayerPacketsHandGateTest {

    @Test
    void classifiesSelectedHandPlayerActionsWithoutGatingMiningOrRelease() {
        assertTrue(ClientPlayerPackets.isSelectedHandPlayerAction(PlayerActionAction.DROP_ITEM));
        assertTrue(ClientPlayerPackets.isSelectedHandPlayerAction(PlayerActionAction.DROP_ALL_ITEMS));
        assertTrue(ClientPlayerPackets.isSelectedHandPlayerAction(PlayerActionAction.SWAP_ITEM_WITH_OFFHAND));
        assertTrue(ClientPlayerPackets.isSelectedHandPlayerAction(PlayerActionAction.STAB));

        assertFalse(ClientPlayerPackets.isSelectedHandPlayerAction(PlayerActionAction.RELEASE_USE_ITEM));
        assertFalse(ClientPlayerPackets.isSelectedHandPlayerAction(PlayerActionAction.START_DESTROY_BLOCK));
        assertFalse(ClientPlayerPackets.isSelectedHandPlayerAction(PlayerActionAction.ABORT_DESTROY_BLOCK));
        assertFalse(ClientPlayerPackets.isSelectedHandPlayerAction(PlayerActionAction.STOP_DESTROY_BLOCK));
    }

}
