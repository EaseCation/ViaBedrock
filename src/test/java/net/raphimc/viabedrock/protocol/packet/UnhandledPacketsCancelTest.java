/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnhandledPacketsCancelTest {

    @Test
    void cancelsEveryUnmappedClientboundTypeExactlyOnce() {
        final ClientboundBedrockPackets[] leftovers = UnhandledPackets.NETEASE_UNMAPPED_CLIENTBOUND;
        assertEquals(48, leftovers.length);
        final Set<ClientboundBedrockPackets> unique = new HashSet<>(Arrays.asList(leftovers));
        assertEquals(leftovers.length, unique.size());
        assertTrue(unique.contains(ClientboundBedrockPackets.DATA_DRIVEN_UI_SHOW_SCREEN));
        assertTrue(unique.contains(ClientboundBedrockPackets.SPAWN_EXPERIENCE_ORB));
        assertTrue(unique.contains(ClientboundBedrockPackets.SERVER_PLAYER_POST_MOVE_POSITION));
        assertFalse(unique.contains(ClientboundBedrockPackets.TRIM_DATA));
        assertFalse(unique.contains(ClientboundBedrockPackets.CAMERA_PRESETS));
        assertFalse(unique.contains(ClientboundBedrockPackets.SET_PLAYER_INVENTORY_OPTIONS));
        assertFalse(unique.contains(ClientboundBedrockPackets.PY_RPC));
    }
}
