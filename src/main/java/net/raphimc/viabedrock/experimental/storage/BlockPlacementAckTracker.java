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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class BlockPlacementAckTracker extends StoredObject {

    private static final long TIMEOUT_MS = 1000;

    private final Map<BlockPosition, PendingAck> pendingAcks = new HashMap<>();

    public BlockPlacementAckTracker(final UserConnection user) {
        super(user);
    }

    public void addPendingAck(final BlockPosition position, final int sequence) {
        this.pendingAcks.put(position, new PendingAck(sequence, System.currentTimeMillis()));
    }

    public Integer consumeAck(final BlockPosition position) {
        final PendingAck ack = this.pendingAcks.remove(position);
        return ack != null ? ack.sequence : null;
    }

    public List<Integer> flushExpired() {
        final long now = System.currentTimeMillis();
        final List<Integer> expired = new ArrayList<>();
        final Iterator<Map.Entry<BlockPosition, PendingAck>> it = this.pendingAcks.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<BlockPosition, PendingAck> entry = it.next();
            if (now - entry.getValue().timestamp > TIMEOUT_MS) {
                expired.add(entry.getValue().sequence);
                it.remove();
            }
        }
        return expired;
    }

    private record PendingAck(int sequence, long timestamp) {
    }

}
