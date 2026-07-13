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
package net.raphimc.viabedrock.experimental.light;

import net.raphimc.viabedrock.protocol.storage.ClientLightStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncLightEngineModeTest {

    @Test
    void fallbackAcceptsJobsCapturedAfterFreezeAndRejectsOlderGenerations() {
        final ClientLightStorage storage = new ClientLightStorage();
        final long unknownGeneration = storage.modeGeneration();

        assertTrue(storage.freeze());
        assertEquals(ClientLightStorage.Mode.SERVER_COMPUTED, storage.mode());
        assertTrue(AsyncLightEngine.allowsProxyComputation(true, storage));
        assertFalse(AsyncLightEngine.isCurrentProxyMode(true, storage, unknownGeneration));
        assertTrue(AsyncLightEngine.isCurrentProxyMode(true, storage, storage.modeGeneration()));
    }

    @Test
    void negotiatedClientAndReplacedChunkTrackerStopProxyWork() {
        final ClientLightStorage storage = new ClientLightStorage();
        assertTrue(storage.tryNegotiateClientComputed());

        assertFalse(AsyncLightEngine.allowsProxyComputation(true, storage));
        assertFalse(AsyncLightEngine.isCurrentProxyMode(true, storage, storage.modeGeneration()));
        assertFalse(AsyncLightEngine.allowsProxyComputation(false, null));
        assertFalse(AsyncLightEngine.isCurrentProxyMode(false, null, 0L));
    }

    @Test
    void missingNegotiationStoragePreservesLegacyProxyMode() {
        assertTrue(AsyncLightEngine.allowsProxyComputation(true, null));
        assertTrue(AsyncLightEngine.isCurrentProxyMode(true, null, 0L));
    }

}
