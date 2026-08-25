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
package net.raphimc.viabedrock.api.model.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffhandPromotionStateTest {

    @Test
    void activePromotionDoesNotRetryBeforeRestoreIsRequested() {
        final OffhandPromotionState state = new OffhandPromotionState();
        state.promoted();
        assertTrue(state.isPromoted());
        assertFalse(state.shouldRetryRestore());
    }

    @Test
    void scheduledRestoreStaysPromotedAndDeduplicatesEventLoopTasks() {
        final OffhandPromotionState state = new OffhandPromotionState();
        state.promoted();
        assertTrue(state.scheduleRestore());
        assertTrue(state.isPromoted());
        assertTrue(state.isRestoreScheduled());
        assertFalse(state.shouldRetryRestore());
        assertFalse(state.scheduleRestore());

        state.restoreSubmissionFailed();
        assertFalse(state.isRestoreScheduled());
        assertTrue(state.shouldRetryRestore());

        assertTrue(state.scheduleRestore());
        state.restoreRequestSent(29);
        assertTrue(state.restoreConfirmed(29));
        assertTrue(state.isPromoted());
        assertTrue(state.completeRestoreFlush());
        assertFalse(state.isPromoted());
    }

    @Test
    void submittedRestoreRemainsPromotedUntilMatchingResponseConfirmsIt() {
        final OffhandPromotionState state = new OffhandPromotionState();
        state.promoted();
        assertTrue(state.scheduleRestore());
        state.restoreRequestSent(37);

        assertTrue(state.isPromoted());
        assertTrue(state.isRestoring());
        assertEquals(37, state.restoreRequestId());
        assertFalse(state.shouldRetryRestore());
        assertFalse(state.scheduleRestore());
        assertFalse(state.canProcessHandSensitiveAction(false, false));
        assertFalse(state.canProcessHandSensitiveAction(true, false));
        assertFalse(state.canProcessHandSensitiveAction(false, true));
        assertFalse(state.restoreConfirmed(38));
        assertTrue(state.isRestoring());

        assertTrue(state.restoreConfirmed(37));
        assertTrue(state.isPromoted());
        assertFalse(state.isRestoring());
        assertTrue(state.completeRestoreFlush());
        assertFalse(state.isPromoted());
    }

    @Test
    void rejectedRestoreReturnsToRetryablePromotedLayout() {
        final OffhandPromotionState state = new OffhandPromotionState();
        state.promoted();
        state.scheduleRestore();
        state.restoreRequestSent(41);

        assertFalse(state.restoreRejected(42));
        assertTrue(state.isRestoring());
        assertTrue(state.restoreRejected(41));
        assertTrue(state.isPromoted());
        assertTrue(state.shouldRetryRestore());
        assertFalse(state.canProcessHandSensitiveAction(false, false));
        assertFalse(state.canProcessHandSensitiveAction(true, false));
        assertTrue(state.canProcessHandSensitiveAction(false, true));
        assertTrue(state.canProcessHandSensitiveAction(true, false, true));

        assertTrue(state.scheduleRestore());
        state.restoreRequestSent(43);
        assertTrue(state.restoreConfirmed(43));
        assertTrue(state.completeRestoreFlush());
        assertFalse(state.isPromoted());
    }

    @Test
    void handGateCoversEveryPromotionPhase() {
        final OffhandPromotionState state = new OffhandPromotionState();
        assertTrue(state.canProcessHandSensitiveAction(false, false));

        state.promoted();
        assertFalse(state.canProcessHandSensitiveAction(false, false));
        assertFalse(state.canProcessHandSensitiveAction(true, false));
        assertTrue(state.canProcessHandSensitiveAction(false, true));
        assertTrue(state.canProcessHandSensitiveAction(true, false, true));

        state.scheduleRestore();
        assertFalse(state.canProcessHandSensitiveAction(false, false));
        assertFalse(state.canProcessHandSensitiveAction(true, false));
        assertTrue(state.canProcessHandSensitiveAction(true, false, true));

        state.restoreSubmissionFailed();
        assertFalse(state.canProcessHandSensitiveAction(false, false));
        assertFalse(state.canProcessHandSensitiveAction(true, false));
        assertTrue(state.canProcessHandSensitiveAction(true, false, true));

        state.scheduleRestore();
        state.restoreRequestSent(51);
        assertFalse(state.canProcessHandSensitiveAction(false, false));
        assertFalse(state.canProcessHandSensitiveAction(true, false));
        assertFalse(state.canProcessHandSensitiveAction(false, true));
        assertFalse(state.canProcessHandSensitiveAction(true, false, true));
    }

    @Test
    void promotionPendingWaitsForMatchingOkAndDoesNotRemapHands() {
        final OffhandPromotionState state = new OffhandPromotionState();
        state.promotionPending(17);
        assertTrue(state.isPromotionPending());
        assertFalse(state.isPromoted());
        assertFalse(state.canProcessHandSensitiveAction(false, false));
        assertFalse(state.canProcessHandSensitiveAction(true, false, true));
        assertFalse(state.scheduleRestore());
        assertFalse(state.promotionConfirmed(18));
        assertTrue(state.isPromotionPending());
        assertTrue(state.promotionConfirmed(17));
        assertTrue(state.isPromoted());
        assertFalse(state.isPromotionPending());
        assertFalse(state.canProcessHandSensitiveAction(false, false));
        assertTrue(state.canProcessHandSensitiveAction(true, false, true));
    }

    @Test
    void rejectedPromotionReturnsToIdleWithoutPromoting() {
        final OffhandPromotionState state = new OffhandPromotionState();
        state.promotionPending(19);
        assertFalse(state.promotionRejected(20));
        assertTrue(state.promotionRejected(19));
        assertFalse(state.isPromoted());
        assertFalse(state.isPromotionPending());
        assertTrue(state.canProcessHandSensitiveAction(false, false));
    }

    @Test
    void reusingPendingPromotionResetsScheduledRestoreBeforeNewUse() {
        final OffhandPromotionState state = new OffhandPromotionState();
        state.promoted();
        state.restoreSubmissionFailed();
        state.promoted();
        assertTrue(state.isPromoted());
        assertFalse(state.shouldRetryRestore());
    }

}
