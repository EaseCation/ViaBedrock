/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinGateChunkCachePassTest {

    @Test
    void cacheCenterAndRadiusPassBeforeJavaLogin() {
        assertTrue(JoinGate.passesClientboundBeforeLogin(ClientboundPackets26_1.SET_CHUNK_CACHE_CENTER));
        assertTrue(JoinGate.passesClientboundBeforeLogin(ClientboundPackets26_1.SET_CHUNK_CACHE_RADIUS));
        assertFalse(JoinGate.dropsClientboundBeforeLogin(ClientboundPackets26_1.SET_CHUNK_CACHE_CENTER));
        assertFalse(JoinGate.dropsClientboundBeforeLogin(ClientboundPackets26_1.SET_CHUNK_CACHE_RADIUS));
    }

    @Test
    void chunkPayloadStillWaitsForJavaLogin() {
        assertTrue(JoinGate.dropsClientboundBeforeLogin(ClientboundPackets26_1.LEVEL_CHUNK_WITH_LIGHT));
        assertTrue(JoinGate.dropsClientboundBeforeLogin(ClientboundPackets26_1.FORGET_LEVEL_CHUNK));
        assertFalse(JoinGate.passesClientboundBeforeLogin(ClientboundPackets26_1.LEVEL_CHUNK_WITH_LIGHT));
    }
}
