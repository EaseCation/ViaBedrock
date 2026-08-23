/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.storage;

import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CreativeContentCacheTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void findNetIdFallsBackToIdentifierAndDataWhenNbtDiffers() {
        final CreativeContentCache cache = new CreativeContentCache(this.user);
        final BedrockItem cached = new BedrockItem(35, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1);
        cache.replace(List.of(new CreativeContentCache.Entry(7, cached)));

        final BedrockItem requested = new BedrockItem(35, (short) 0, (byte) 64);
        assertEquals(7, cache.findNetId(requested));
    }

    @Test
    void findNetIdDoesNotCrossIdentifiers() {
        final CreativeContentCache cache = new CreativeContentCache(this.user);
        cache.replace(List.of(new CreativeContentCache.Entry(3, new BedrockItem(35, (short) 0, (byte) 1))));
        assertNull(cache.findNetId(new BedrockItem(1, (short) 0, (byte) 1)));
    }
}
