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
package net.raphimc.viabedrock.experimental.blockbreak;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerActionType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerActionAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockBreakingProgressModuleTest {

    @Test
    void neteaseCompletionCannotRestartMiningAfterPredict() {
        assertEquals(List.of(PlayerActionType.PredictDestroyBlock),
                BlockBreakingProgressModule.serverAuthoritativeCompletionActions(true));
    }

    @Test
    void officialCompletionRetainsContinueThenPredict() {
        assertEquals(List.of(PlayerActionType.ContinueDestroyBlock, PlayerActionType.PredictDestroyBlock),
                BlockBreakingProgressModule.serverAuthoritativeCompletionActions(false));
    }

    @Test
    void miningAckPolicyDefersOnlyAuthoritativeCompletions() {
        assertEquals(true, BlockBreakingProgressModule.shouldAcknowledgeImmediately(
                PlayerActionAction.START_DESTROY_BLOCK, false));
        assertEquals(false, BlockBreakingProgressModule.shouldAcknowledgeImmediately(
                PlayerActionAction.START_DESTROY_BLOCK, true));
        assertEquals(true, BlockBreakingProgressModule.shouldAcknowledgeImmediately(
                PlayerActionAction.ABORT_DESTROY_BLOCK, false));
        assertEquals(false, BlockBreakingProgressModule.shouldAcknowledgeImmediately(
                PlayerActionAction.STOP_DESTROY_BLOCK, false));
        assertEquals(false, BlockBreakingProgressModule.shouldAcknowledgeImmediately(
                PlayerActionAction.STOP_DESTROY_BLOCK, true));
    }
}
