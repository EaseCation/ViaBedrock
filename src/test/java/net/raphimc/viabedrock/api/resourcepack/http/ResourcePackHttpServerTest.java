/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackHttpServerTest {

    @Test
    void extractsOnlyContentAddressedArtifactPaths() {
        final String hash = "0123456789abcdef0123456789abcdef01234567";

        assertEquals(hash, ResourcePackHttpServer.artifactHash("/packs/" + hash + ".zip"));
        assertNull(ResourcePackHttpServer.artifactHash("/packs/not-a-hash.zip"));
        assertNull(ResourcePackHttpServer.artifactHash("/other/" + hash + ".zip"));
    }

    @Test
    void parsesFullAndOpenEndedRanges() {
        final ResourcePackHttpServer.HttpByteRange full = ResourcePackHttpServer.parseRange(null, 100);
        assertEquals(0, full.start());
        assertEquals(99, full.end());
        assertEquals(100, full.length());
        assertFalse(full.partial());

        final ResourcePackHttpServer.HttpByteRange openEnded = ResourcePackHttpServer.parseRange("bytes=40-", 100);
        assertEquals(40, openEnded.start());
        assertEquals(99, openEnded.end());
        assertEquals(60, openEnded.length());
        assertTrue(openEnded.partial());
    }

    @Test
    void parsesBoundedAndSuffixRanges() {
        final ResourcePackHttpServer.HttpByteRange bounded = ResourcePackHttpServer.parseRange("bytes=4-7", 16);
        assertEquals(4, bounded.start());
        assertEquals(7, bounded.end());

        final ResourcePackHttpServer.HttpByteRange suffix = ResourcePackHttpServer.parseRange("bytes=-4", 16);
        assertEquals(12, suffix.start());
        assertEquals(15, suffix.end());
    }

    @Test
    void rejectsInvalidOrUnsatisfiableRanges() {
        assertNull(ResourcePackHttpServer.parseRange("bytes=100-200", 100));
        assertNull(ResourcePackHttpServer.parseRange("bytes=8-4", 100));
        assertNull(ResourcePackHttpServer.parseRange("bytes=0-1,4-5", 100));
        assertNull(ResourcePackHttpServer.parseRange("bytes=-0", 100));
        assertNull(ResourcePackHttpServer.parseRange(null, 0));
    }

}
