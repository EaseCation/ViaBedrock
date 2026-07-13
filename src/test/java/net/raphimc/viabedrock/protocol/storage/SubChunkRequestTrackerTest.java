/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SubChunkRequestTrackerTest {

    @Test
    void blobPhaseHasAnIndependentDeadlineThatDuplicateResponsesCannotExtend() {
        SubChunkRequestTracker<Object> tracker = tracker();
        SubChunkRequestTracker.Position position = new SubChunkRequestTracker.Position(2, 3, 4);
        Object owner = new Object();

        assertTrue(tracker.enqueue(position));
        assertFalse(tracker.enqueue(position));
        assertEquals(1, tracker.outstandingCount(position.chunkKey()));
        assertEquals(SubChunkRequestTracker.DispatchResult.DISPATCHED, tracker.dispatch(position, owner, 1));

        SubChunkRequestTracker.Token<Object> token = tracker.captureResponse(position, owner, 5, true);
        assertNotNull(token);
        assertEquals(token, tracker.captureResponse(position, owner, 80, true));
        assertTrue(tracker.expire(104).isEmpty());

        List<SubChunkRequestTracker.Expired<Object>> expired = tracker.expire(105);
        assertEquals(1, expired.size());
        assertTrue(expired.getFirst().waitingForBlob());
        assertTrue(tracker.complete(expired.getFirst().claim()));
        assertFalse(tracker.complete(expired.getFirst().claim()));
        assertEquals(0, tracker.outstandingCount(position.chunkKey()));
        assertTrue(tracker.isEmpty());
    }

    @Test
    void responseRetriesAreBoundedAndOldAttemptTokensStayStale() {
        SubChunkRequestTracker<Object> tracker = tracker();
        SubChunkRequestTracker.Position position = new SubChunkRequestTracker.Position(7, 8, 9);
        Object owner = new Object();

        assertTrue(tracker.enqueue(position));
        assertEquals(SubChunkRequestTracker.DispatchResult.DISPATCHED, tracker.dispatch(position, owner, 0));
        SubChunkRequestTracker.Token<Object> firstToken = tracker.captureResponse(position, owner, 0, false);
        SubChunkRequestTracker.Claim<Object> firstClaim = tracker.claim(position, firstToken, owner);
        assertEquals(SubChunkRequestTracker.RetryResult.REQUEUED, tracker.retry(firstClaim, true));

        assertEquals(SubChunkRequestTracker.DispatchResult.DISPATCHED, tracker.dispatch(position, owner, 1));
        SubChunkRequestTracker.Token<Object> secondToken = tracker.captureResponse(position, owner, 1, false);
        assertNotEquals(firstToken, secondToken);
        assertNull(tracker.claim(position, firstToken, owner));

        SubChunkRequestTracker.Claim<Object> secondClaim = tracker.claim(position, secondToken, owner);
        assertEquals(SubChunkRequestTracker.RetryResult.REQUEUED, tracker.retry(secondClaim, true));
        assertEquals(SubChunkRequestTracker.DispatchResult.DISPATCHED, tracker.dispatch(position, owner, 2));

        List<SubChunkRequestTracker.Expired<Object>> expired = tracker.expire(42);
        assertEquals(1, expired.size());
        assertFalse(expired.getFirst().waitingForBlob());
        assertEquals(SubChunkRequestTracker.RetryResult.EXHAUSTED, tracker.retry(expired.getFirst().claim(), true));
        assertEquals(0, tracker.outstandingCount(position.chunkKey()));
        assertTrue(tracker.isEmpty());
    }

    @Test
    void blobTimeoutCanBeRetriedWithoutAcceptingTheLateOldAttempt() {
        SubChunkRequestTracker<Object> tracker = tracker();
        SubChunkRequestTracker.Position position = new SubChunkRequestTracker.Position(5, 6, 7);
        Object owner = new Object();

        assertTrue(tracker.enqueue(position));
        assertEquals(SubChunkRequestTracker.DispatchResult.DISPATCHED, tracker.dispatch(position, owner, 0));
        SubChunkRequestTracker.Token<Object> oldToken = tracker.captureResponse(position, owner, 1, true);
        List<SubChunkRequestTracker.Expired<Object>> expired = tracker.expire(101);
        assertEquals(1, expired.size());
        assertTrue(expired.getFirst().waitingForBlob());
        assertEquals(
                SubChunkRequestTracker.RetryResult.REQUEUED,
                tracker.retry(expired.getFirst().claim(), true)
        );

        assertEquals(SubChunkRequestTracker.DispatchResult.DISPATCHED, tracker.dispatch(position, owner, 102));
        assertNull(tracker.claim(position, oldToken, owner));
        SubChunkRequestTracker.Token<Object> newToken = tracker.captureResponse(position, owner, 103, false);
        SubChunkRequestTracker.Claim<Object> newClaim = tracker.claim(position, newToken, owner);
        assertTrue(tracker.complete(newClaim));
        assertTrue(tracker.isEmpty());
    }

    @Test
    void cancelSettlesQueuedPendingAndClaimedRequestsExactlyOnce() {
        SubChunkRequestTracker<Object> tracker = tracker();
        Object owner = new Object();
        SubChunkRequestTracker.Position queued = new SubChunkRequestTracker.Position(1, 0, 1);
        SubChunkRequestTracker.Position pending = new SubChunkRequestTracker.Position(1, 1, 1);
        SubChunkRequestTracker.Position claimed = new SubChunkRequestTracker.Position(1, 2, 1);
        SubChunkRequestTracker.Position retained = new SubChunkRequestTracker.Position(2, 0, 2);

        assertTrue(tracker.enqueue(queued));
        assertTrue(tracker.enqueue(pending));
        assertTrue(tracker.enqueue(claimed));
        assertTrue(tracker.enqueue(retained));
        assertEquals(SubChunkRequestTracker.DispatchResult.DISPATCHED, tracker.dispatch(pending, owner, 0));
        assertEquals(SubChunkRequestTracker.DispatchResult.DISPATCHED, tracker.dispatch(claimed, owner, 0));
        SubChunkRequestTracker.Token<Object> claimedToken = tracker.captureResponse(claimed, owner, 0, false);
        SubChunkRequestTracker.Claim<Object> activeClaim = tracker.claim(claimed, claimedToken, owner);

        assertEquals(Set.of(queued.chunkKey()), tracker.cancelIf(position -> position.chunkX() == 1));
        assertEquals(0, tracker.outstandingCount(queued.chunkKey()));
        assertEquals(1, tracker.outstandingCount(retained.chunkKey()));
        assertFalse(tracker.complete(activeClaim));
        assertNull(tracker.captureResponse(pending, owner, 0, false));

        tracker.cancelColumn(retained.chunkKey());
        assertTrue(tracker.isEmpty());
    }

    @Test
    void missingDispatchOwnerTerminatesTheRequestWithoutAnOutstandingLeak() {
        SubChunkRequestTracker<Object> tracker = tracker();
        SubChunkRequestTracker.Position position = new SubChunkRequestTracker.Position(11, 12, 13);

        assertTrue(tracker.enqueue(position));
        assertEquals(SubChunkRequestTracker.DispatchResult.DISCARDED, tracker.dispatch(position, null, 0));
        assertEquals(0, tracker.outstandingCount(position.chunkKey()));
        assertTrue(tracker.isEmpty());
        assertEquals(SubChunkRequestTracker.DispatchResult.NOT_QUEUED, tracker.dispatch(position, new Object(), 1));
    }

    @Test
    void expirationBudgetDefersExcessRequestsWithoutDroppingThem() {
        SubChunkRequestTracker<Object> tracker = tracker();
        Object owner = new Object();
        for (int subChunkY = 0; subChunkY < 5; subChunkY++) {
            SubChunkRequestTracker.Position position = new SubChunkRequestTracker.Position(3, subChunkY, 4);
            assertTrue(tracker.enqueue(position));
            assertEquals(SubChunkRequestTracker.DispatchResult.DISPATCHED, tracker.dispatch(position, owner, 0));
        }

        List<SubChunkRequestTracker.Expired<Object>> first = tracker.expire(40, 2);
        List<SubChunkRequestTracker.Expired<Object>> second = tracker.expire(41, 2);
        List<SubChunkRequestTracker.Expired<Object>> third = tracker.expire(42, 2);

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertEquals(1, third.size());
        assertEquals(5, tracker.outstandingCount(new SubChunkRequestTracker.Position(3, 0, 4).chunkKey()));
        for (SubChunkRequestTracker.Expired<Object> expired : List.of(first, second, third).stream().flatMap(List::stream).toList()) {
            assertTrue(tracker.complete(expired.claim()));
        }
        assertTrue(tracker.isEmpty());
    }

    private static SubChunkRequestTracker<Object> tracker() {
        return new SubChunkRequestTracker<>(2, 40, 100);
    }
}
