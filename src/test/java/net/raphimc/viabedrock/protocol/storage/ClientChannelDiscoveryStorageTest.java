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
package net.raphimc.viabedrock.protocol.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientChannelDiscoveryStorageTest {

    @Test
    void sendsOneProbePerConfigurationCycle() {
        final ClientChannelDiscoveryStorage storage = new ClientChannelDiscoveryStorage();

        assertTrue(storage.markProbeSent());
        assertFalse(storage.markProbeSent());
        assertTrue(storage.hasSentProbe());

        storage.beginConfigurationCycle();

        assertFalse(storage.hasSentProbe());
        assertTrue(storage.markProbeSent());
    }

    @Test
    void resetsRegistrationStateWithTheConfigurationCycle() {
        final ClientChannelDiscoveryStorage storage = new ClientChannelDiscoveryStorage();

        assertTrue(storage.markRegistrationReceived());
        assertFalse(storage.markRegistrationReceived());
        assertTrue(storage.hasReceivedRegistration());

        storage.beginConfigurationCycle();

        assertFalse(storage.hasReceivedRegistration());
        assertTrue(storage.markRegistrationReceived());
    }

    @Test
    void allowsOnlyOneConcurrentProbeWinner() throws Exception {
        final ClientChannelDiscoveryStorage storage = new ClientChannelDiscoveryStorage();
        final ExecutorService executor = Executors.newFixedThreadPool(8);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    return storage.markProbeSent();
                }));
            }

            start.countDown();
            int winners = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(5L, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
        }
    }

}
