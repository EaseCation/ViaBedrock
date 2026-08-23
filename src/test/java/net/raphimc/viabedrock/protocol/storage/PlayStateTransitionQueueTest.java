/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayStateTransitionQueueTest {

    @Test
    void defersJoinTimePlayersMountsIdentifiersAndChunkCenter() {
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.ADD_PLAYER));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.ADD_PAINTING));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.SET_ENTITY_LINK));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.AVAILABLE_ENTITY_IDENTIFIERS));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.NETWORK_CHUNK_PUBLISHER_UPDATE));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.LEVEL_CHUNK));
        assertTrue(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.ADD_ENTITY));
        assertFalse(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.START_GAME));
        assertFalse(PlayStateTransitionQueue.shouldDefer(ClientboundBedrockPackets.RESOURCE_PACKS_INFO));
    }
}
