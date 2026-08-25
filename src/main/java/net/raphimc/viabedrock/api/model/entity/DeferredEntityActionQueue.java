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

import java.util.ArrayDeque;
import java.util.Deque;

public final class DeferredEntityActionQueue {

    public static final int DEFAULT_CAPACITY = 16;
    public static final int DEFAULT_TIMEOUT_TICKS = 40;

    private final int capacity;
    private final int timeoutTicks;
    private final Deque<Action> actions = new ArrayDeque<>();
    private boolean overflowReported;

    public DeferredEntityActionQueue() {
        this(DEFAULT_CAPACITY, DEFAULT_TIMEOUT_TICKS);
    }

    DeferredEntityActionQueue(final int capacity, final int timeoutTicks) {
        if (capacity <= 0 || timeoutTicks <= 0) {
            throw new IllegalArgumentException("capacity and timeoutTicks must be positive");
        }
        this.capacity = capacity;
        this.timeoutTicks = timeoutTicks;
    }

    public EnqueueResult enqueue(final int actionType, final long entityRuntimeId, final long entityUniqueId,
                                 final float clickX, final float clickY, final float clickZ, final int currentAge) {
        return this.enqueue(actionType, entityRuntimeId, entityUniqueId, clickX, clickY, clickZ, currentAge, false);
    }

    public EnqueueResult enqueue(final int actionType, final long entityRuntimeId, final long entityUniqueId,
                                 final float clickX, final float clickY, final float clickZ, final int currentAge,
                                 final boolean swingAfter) {
        if (actionType != 0 && actionType != 1) {
            throw new IllegalArgumentException("Unsupported entity action type: " + actionType);
        }
        this.discardExpired(currentAge);
        if (this.actions.size() >= this.capacity) {
            if (!this.overflowReported) {
                this.overflowReported = true;
                return EnqueueResult.FULL_FIRST;
            }
            return EnqueueResult.FULL;
        }
        this.actions.addLast(new Action(actionType, entityRuntimeId, entityUniqueId, clickX, clickY, clickZ, currentAge, swingAfter));
        return EnqueueResult.QUEUED;
    }

    public int discardExpired(final int currentAge) {
        int discarded = 0;
        while (!this.actions.isEmpty() && this.isExpired(this.actions.peekFirst(), currentAge)) {
            this.actions.removeFirst();
            discarded++;
        }
        if (this.actions.size() < this.capacity) {
            this.overflowReported = false;
        }
        return discarded;
    }

    public Action peekFirst() {
        return this.actions.peekFirst();
    }

    public Action removeFirst() {
        final Action action = this.actions.removeFirst();
        if (this.actions.size() < this.capacity) {
            this.overflowReported = false;
        }
        return action;
    }

    public boolean isEmpty() {
        return this.actions.isEmpty();
    }

    public int size() {
        return this.actions.size();
    }

    public void clear() {
        this.actions.clear();
        this.overflowReported = false;
    }

    private boolean isExpired(final Action action, final int currentAge) {
        final int elapsed = currentAge - action.queuedAtAge();
        return elapsed < 0 || elapsed >= this.timeoutTicks;
    }

    public enum EnqueueResult {
        QUEUED,
        FULL_FIRST,
        FULL
    }

    public record Action(
            int actionType,
            long entityRuntimeId,
            long entityUniqueId,
            float clickX,
            float clickY,
            float clickZ,
            int queuedAtAge,
            boolean swingAfter
    ) {

        public Action(final int actionType, final long entityRuntimeId, final long entityUniqueId,
                      final float clickX, final float clickY, final float clickZ, final int queuedAtAge) {
            this(actionType, entityRuntimeId, entityUniqueId, clickX, clickY, clickZ, queuedAtAge, false);
        }

    }

}
