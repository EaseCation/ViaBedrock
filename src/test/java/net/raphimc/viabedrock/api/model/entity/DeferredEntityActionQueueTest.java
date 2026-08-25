/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.model.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredEntityActionQueueTest {

    @Test
    void drainsInInsertionOrderAndPreservesPrimitivePayload() {
        final DeferredEntityActionQueue queue = new DeferredEntityActionQueue(4, 20);
        assertEquals(DeferredEntityActionQueue.EnqueueResult.QUEUED,
                queue.enqueue(1, 11L, 101L, 1F, 2F, 3F, 5));
        assertEquals(DeferredEntityActionQueue.EnqueueResult.QUEUED,
                queue.enqueue(0, 12L, 102L, 4F, 5F, 6F, 6));

        assertEquals(new DeferredEntityActionQueue.Action(1, 11L, 101L, 1F, 2F, 3F, 5), queue.removeFirst());
        assertEquals(new DeferredEntityActionQueue.Action(0, 12L, 102L, 4F, 5F, 6F, 6), queue.removeFirst());
        assertNull(queue.peekFirst());
    }

    @Test
    void preservesDeferredAttackSwingMarker() {
        final DeferredEntityActionQueue queue = new DeferredEntityActionQueue(2, 20);

        queue.enqueue(1, 11L, 101L, 1F, 2F, 3F, 5, true);

        assertTrue(queue.removeFirst().swingAfter());
    }

    @Test
    void enforcesCapacityAndReportsOnlyTheFirstOverflow() {
        final DeferredEntityActionQueue queue = new DeferredEntityActionQueue(2, 20);
        queue.enqueue(1, 1L, 1L, 0F, 0F, 0F, 0);
        queue.enqueue(1, 2L, 2L, 0F, 0F, 0F, 0);

        assertEquals(DeferredEntityActionQueue.EnqueueResult.FULL_FIRST,
                queue.enqueue(1, 3L, 3L, 0F, 0F, 0F, 0));
        assertEquals(DeferredEntityActionQueue.EnqueueResult.FULL,
                queue.enqueue(1, 4L, 4L, 0F, 0F, 0F, 0));
        assertEquals(2, queue.size());
    }

    @Test
    void expiresFromTheHeadAndClearsOnAgeReset() {
        final DeferredEntityActionQueue queue = new DeferredEntityActionQueue(4, 5);
        queue.enqueue(1, 1L, 1L, 0F, 0F, 0F, 10);
        queue.enqueue(0, 2L, 2L, 0F, 0F, 0F, 12);

        assertEquals(1, queue.discardExpired(15));
        assertEquals(2L, queue.peekFirst().entityRuntimeId());
        assertEquals(1, queue.discardExpired(2));
        assertNull(queue.peekFirst());
    }

    @Test
    void clearDiscardsAllActionsForDimensionReset() {
        final DeferredEntityActionQueue queue = new DeferredEntityActionQueue();
        queue.enqueue(1, 1L, 1L, 0F, 0F, 0F, 0);
        queue.enqueue(0, 2L, 2L, 0F, 0F, 0F, 0);

        queue.clear();

        assertEquals(0, queue.size());
        assertNull(queue.peekFirst());
    }

    @Test
    void rejectsMOTItemInteractActionTwo() {
        final DeferredEntityActionQueue queue = new DeferredEntityActionQueue();
        assertThrows(IllegalArgumentException.class,
                () -> queue.enqueue(2, 1L, 1L, 0F, 0F, 0F, 0));
    }

}
