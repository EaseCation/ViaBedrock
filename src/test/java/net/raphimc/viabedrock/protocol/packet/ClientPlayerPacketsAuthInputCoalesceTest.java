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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerActionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlayerPacketsAuthInputCoalesceTest {

    @Test
    void sameTickRetargetKeepsStartAndDropsAbort() {
        final BlockPosition oldPos = new BlockPosition(1, 64, 1);
        final BlockPosition newPos = new BlockPosition(2, 64, 1);
        final List<ClientPlayerEntity.AuthInputBlockAction> coalesced = ClientPlayerPackets.coalesceAuthInputBlockActions(List.of(
                new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.AbortDestroyBlock, oldPos, 0),
                new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StartDestroyBlock, newPos, 1)
        ));
        assertEquals(1, coalesced.size());
        assertEquals(PlayerActionType.StartDestroyBlock, coalesced.get(0).action());
        assertEquals(newPos, coalesced.get(0).position());
    }

    @Test
    void sameTickStartAndFinishKeepsStart() {
        final BlockPosition pos = new BlockPosition(3, 70, 3);
        final List<ClientPlayerEntity.AuthInputBlockAction> coalesced = ClientPlayerPackets.coalesceAuthInputBlockActions(List.of(
                new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StartDestroyBlock, pos, 1),
                new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.ContinueDestroyBlock, pos, 1),
                new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.PredictDestroyBlock, pos, 1)
        ));
        assertEquals(List.of(
                PlayerActionType.StartDestroyBlock,
                PlayerActionType.ContinueDestroyBlock,
                PlayerActionType.PredictDestroyBlock
        ), coalesced.stream().map(ClientPlayerEntity.AuthInputBlockAction::action).toList());
    }

    @Test
    void finishWithoutStartStaysContinueThenPredict() {
        final BlockPosition pos = new BlockPosition(4, 70, 4);
        final List<ClientPlayerEntity.AuthInputBlockAction> coalesced = ClientPlayerPackets.coalesceAuthInputBlockActions(List.of(
                new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.ContinueDestroyBlock, pos, 2),
                new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.PredictDestroyBlock, pos, 2)
        ));
        assertEquals(List.of(
                PlayerActionType.ContinueDestroyBlock,
                PlayerActionType.PredictDestroyBlock
        ), coalesced.stream().map(ClientPlayerEntity.AuthInputBlockAction::action).toList());
    }

    @Test
    void abortWithoutStartIsKept() {
        final BlockPosition pos = new BlockPosition(5, 64, 5);
        final List<ClientPlayerEntity.AuthInputBlockAction> coalesced = ClientPlayerPackets.coalesceAuthInputBlockActions(List.of(
                new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.AbortDestroyBlock, pos, 0)
        ));
        assertEquals(1, coalesced.size());
        assertEquals(PlayerActionType.AbortDestroyBlock, coalesced.get(0).action());
        assertTrue(ClientPlayerPackets.coalesceAuthInputBlockActions(List.of()).isEmpty());
    }
}
