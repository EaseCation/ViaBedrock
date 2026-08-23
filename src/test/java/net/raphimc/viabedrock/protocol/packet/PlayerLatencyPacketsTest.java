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

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLatencyPacketsTest {

    private static final UUID VALID_UUID = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void parsesValidSnapshotAndIgnoresInvalidEntries() {
        final Map<UUID, Integer> snapshot = PlayerLatencyPackets.parseSnapshot("""
                {
                  "12345678-1234-5678-9abc-def012345678": 87,
                  "87654321-4321-8765-cba9-876543210fed": -1,
                  "not-a-uuid": 42,
                  "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa": 1.5,
                  "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb": 2147483648
                }
                """);

        assertEquals(Map.of(VALID_UUID, 87), snapshot);
    }

    @Test
    void acceptsEmptyFullSnapshot() {
        assertTrue(PlayerLatencyPackets.parseSnapshot("{}").isEmpty());
    }

    @Test
    void acceptsCanonicalAndLegacyChannelIds() {
        assertTrue(PlayerLatencyPackets.isLatencyMessage("waterdog:player_latency_v1"));
        assertTrue(PlayerLatencyPackets.isLatencyMessage("easecation:player_latency_v1"));
        assertTrue(!PlayerLatencyPackets.isLatencyMessage("ganquanonline:player_latency_v1"));
    }

    @Test
    void rejectsMalformedAndOversizedSnapshots() {
        assertThrows(RuntimeException.class, () -> PlayerLatencyPackets.parseSnapshot("not-json"));
        assertThrows(RuntimeException.class, () -> PlayerLatencyPackets.parseSnapshot("[]"));
        assertThrows(IllegalArgumentException.class, () -> PlayerLatencyPackets.parseSnapshot("x".repeat(PlayerLatencyPackets.MAX_PAYLOAD_LENGTH + 1)));

        final StringBuilder tooManyEntries = new StringBuilder("{");
        for (int i = 0; i <= PlayerLatencyPackets.MAX_ENTRIES; i++) {
            if (i > 0) tooManyEntries.append(',');
            tooManyEntries.append('"').append(new UUID(0L, i)).append("\":0");
        }
        tooManyEntries.append('}');
        assertThrows(IllegalArgumentException.class, () -> PlayerLatencyPackets.parseSnapshot(tooManyEntries.toString()));
    }
}
