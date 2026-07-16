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
package net.raphimc.viabedrock.experimental.tablist;

import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabListLatencyModuleTest {

    @Test
    void onlyMatchesExactNpcName() {
        assertTrue(TabListLatencyModule.isNpcName("NPC"));
        assertFalse(TabListLatencyModule.isNpcName("npc"));
        assertFalse(TabListLatencyModule.isNpcName("NPC "));
        assertFalse(TabListLatencyModule.isNpcName("Player"));
    }

    @Test
    void formatsLatencyUsingSignalStrengthThresholds() {
        assertEquals("Player", TabListLatencyModule.formatDisplayName("Player", -1));
        assertEquals("Player \u00A77[\u00A7a149ms\u00A77]", TabListLatencyModule.formatDisplayName("Player", 149));
        assertEquals("Player \u00A77[\u00A7e150ms\u00A77]", TabListLatencyModule.formatDisplayName("Player", 150));
        assertEquals("Player \u00A77[\u00A7e299ms\u00A77]", TabListLatencyModule.formatDisplayName("Player", 299));
        assertEquals("Player \u00A77[\u00A76300ms\u00A77]", TabListLatencyModule.formatDisplayName("Player", 300));
        assertEquals("Player \u00A77[\u00A76599ms\u00A77]", TabListLatencyModule.formatDisplayName("Player", 599));
        assertEquals("Player \u00A77[\u00A7c600ms\u00A77]", TabListLatencyModule.formatDisplayName("Player", 600));
    }

    @Test
    void buildsUpdatesOnlyForExistingNonNpcEntries() {
        final UUID knownUuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        final UUID unknownLatencyUuid = UUID.fromString("87654321-4321-8765-cba9-876543210fed");
        final UUID npcUuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        final UUID missingUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        final PlayerListStorage playerList = new PlayerListStorage();
        playerList.addPlayer(knownUuid, 1L, "Known");
        playerList.addPlayer(unknownLatencyUuid, 2L, "Unknown");
        playerList.addPlayer(npcUuid, 3L, "NPC");

        final Map<UUID, Integer> latencies = new LinkedHashMap<>();
        latencies.put(knownUuid, 87);
        latencies.put(unknownLatencyUuid, -1);
        latencies.put(npcUuid, 42);
        latencies.put(missingUuid, 99);

        final Map<UUID, String> displayNames = TabListLatencyModule.displayNamesFor(playerList, latencies);

        assertEquals(Map.of(
                knownUuid, "Known \u00A77[\u00A7a87ms\u00A77]",
                unknownLatencyUuid, "Unknown"
        ), displayNames);
    }

}
