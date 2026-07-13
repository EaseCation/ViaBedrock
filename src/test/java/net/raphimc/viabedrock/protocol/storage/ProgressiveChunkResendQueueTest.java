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

import static org.junit.jupiter.api.Assertions.*;

class ProgressiveChunkResendQueueTest {

    @Test
    void leadingSnapshotIsImmediateAndChangedTrailingSnapshotIsSentOnce() {
        ProgressiveChunkResendQueue queue = new ProgressiveChunkResendQueue();
        long chunk = 12L;

        assertTrue(queue.onProgress(chunk, false, true, true));
        assertFalse(queue.onProgress(chunk, true, true, true));
        assertTrue(queue.onProgress(chunk, true, false, false));
        assertFalse(queue.onProgress(chunk, true, false, false));
    }

    @Test
    void terminalFailureWithoutAChangeDoesNotResendAnExistingSnapshot() {
        ProgressiveChunkResendQueue queue = new ProgressiveChunkResendQueue();

        assertFalse(queue.onProgress(34L, true, true, false));
        assertFalse(queue.onProgress(34L, true, false, false));
        assertArrayEquals(new long[0], queue.drainAll());
    }

    @Test
    void aSnapshotSentWhileRequestsRemainAbsorbsAllEarlierChanges() {
        ProgressiveChunkResendQueue queue = new ProgressiveChunkResendQueue();

        assertFalse(queue.onProgress(55L, true, true, true));
        queue.onSnapshotSent(55L);
        assertFalse(queue.onProgress(55L, true, false, false));
    }

    @Test
    void resetFlushesChangedColumnsAndUnloadDropsItsObligation() {
        ProgressiveChunkResendQueue queue = new ProgressiveChunkResendQueue();
        assertFalse(queue.onProgress(1L, true, true, true));
        assertFalse(queue.onProgress(2L, true, true, true));
        queue.forget(2L);

        assertArrayEquals(new long[] {1L}, queue.drainAll());
        assertArrayEquals(new long[0], queue.drainAll());
    }
}
