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

import net.raphimc.viabedrock.protocol.storage.ClientLightStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinPacketsTest {

    @Test
    void runsPlayDependentWorkOnlyAfterConfigurationFinish() {
        final List<String> events = new ArrayList<>();
        final Runnable completion = JoinPackets.sequenceConfigurationCompletion(
                () -> events.add("finish-and-play"),
                () -> events.add("play-dependent"));

        assertEquals(List.of(), events);
        completion.run();
        assertEquals(List.of("finish-and-play", "play-dependent"), events);
    }

    @Test
    void doesNotRunPlayDependentWorkWhenFinishFails() {
        final List<String> events = new ArrayList<>();
        final Runnable completion = JoinPackets.sequenceConfigurationCompletion(
                () -> {
                    events.add("finish-failed");
                    throw new IllegalStateException("failed");
                },
                () -> events.add("play-dependent"));

        assertThrows(IllegalStateException.class, completion::run);
        assertEquals(List.of("finish-failed"), events);
    }

    @Test
    void negotiationBarrierDelaysTheEntireCompletionSequence() {
        final ClientLightStorage storage = new ClientLightStorage();
        final List<String> events = new ArrayList<>();
        final Runnable completion = JoinPackets.sequenceConfigurationCompletion(
                () -> events.add("finish-and-play"),
                () -> events.add("play-dependent"));

        assertTrue(storage.markProbeSent(0L));
        assertEquals(ClientLightStorage.FinishDecision.WAIT, storage.requestFinish(1L, completion).decision());
        assertEquals(List.of(), events);

        assertTrue(storage.tryNegotiateClientComputed());
        final Runnable released = storage.releasePendingFinishAfterNegotiation();
        assertNotNull(released);
        released.run();
        assertEquals(List.of("finish-and-play", "play-dependent"), events);
    }

}
