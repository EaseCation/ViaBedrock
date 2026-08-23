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

import net.raphimc.viabedrock.experimental.tablist.PlayerIdentity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerIdentityPacketsTest {

    private static final UUID JAVA_UUID = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    private static final UUID BEDROCK_UUID = UUID.fromString("87654321-4321-8765-cba9-876543210fed");

    @Test
    void parsesJavaAndBedrockIdentitiesFromSnapshot() {
        final Map<UUID, PlayerIdentity> snapshot = PlayerIdentityPackets.parseSnapshot("""
                {
                  "12345678-1234-5678-9abc-def012345678": {
                    "edition": "je",
                    "platform": "pc_java",
                    "version": "1.21.11"
                  },
                  "87654321-4321-8765-cba9-876543210fed": {
                    "edition": "be",
                    "version": "1.21.124"
                  },
                  "not-a-uuid": {
                    "edition": "je"
                  }
                }
                """);

        assertEquals(PlayerIdentity.javaEdition("1.21.11"), snapshot.get(JAVA_UUID));
        assertEquals(PlayerIdentity.bedrock("1.21.124"), snapshot.get(BEDROCK_UUID));
        assertEquals(2, snapshot.size());
    }

    @Test
    void treatsPcJavaPlatformAsJavaEvenWithoutEdition() {
        final Map<UUID, PlayerIdentity> snapshot = PlayerIdentityPackets.parseSnapshot("""
                {
                  "12345678-1234-5678-9abc-def012345678": {
                    "platform": "pc_java",
                    "java_version": "1.21.11"
                  }
                }
                """);
        assertEquals(PlayerIdentity.javaEdition("1.21.11"), snapshot.get(JAVA_UUID));
    }

    @Test
    void acceptsCanonicalChannelId() {
        assertTrue(PlayerIdentityPackets.isIdentityMessage("waterdog:player_identity_v1"));
        assertFalse(PlayerIdentityPackets.isIdentityMessage("waterdog:player_latency_v1"));
    }

    @Test
    void rejectsMalformedAndOversizedSnapshots() {
        assertThrows(RuntimeException.class, () -> PlayerIdentityPackets.parseSnapshot("not-json"));
        assertThrows(RuntimeException.class, () -> PlayerIdentityPackets.parseSnapshot("[]"));
        assertThrows(IllegalArgumentException.class, () -> PlayerIdentityPackets.parseSnapshot("x".repeat(PlayerIdentityPackets.MAX_PAYLOAD_LENGTH + 1)));
    }
}
