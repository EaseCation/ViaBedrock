/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.resourcepack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPackCacheTest {

    @Test
    void publishesAndReloadsContentAddressedArtifact(@TempDir final Path tempDir) throws Exception {
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile());
        final byte[] data = "converted-pack".getBytes(StandardCharsets.UTF_8);

        final String hash = cache.put("cache-key", data);

        assertEquals(hash, cache.getValidHash("cache-key"));
        assertArrayEquals(data, cache.getData("cache-key"));
        assertTrue(cache.getArtifactFile(hash).isFile());
        assertArrayEquals(data, Files.readAllBytes(cache.getArtifactFile(hash).toPath()));

        final JavaPackCache reloaded = new JavaPackCache(tempDir.toFile());
        assertEquals(hash, reloaded.getValidHash("cache-key"));
    }

    @Test
    void rejectsTruncatedCacheEntry(@TempDir final Path tempDir) throws Exception {
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile());
        final String hash = cache.put("cache-key", "complete".getBytes(StandardCharsets.UTF_8));
        final Path cacheZip = tempDir.resolve("cache-key.zip");

        Files.writeString(cacheZip, "truncated", StandardCharsets.UTF_8);

        assertNull(cache.getValidHash("cache-key"));
        assertFalse(Files.exists(cacheZip));
        assertFalse(Files.exists(tempDir.resolve("cache-key.sha1")));
        assertNotNull(hash);
    }

    @Test
    void repairsMissingArtifactFromValidCache(@TempDir final Path tempDir) throws Exception {
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile());
        final byte[] data = "converted-pack".getBytes(StandardCharsets.UTF_8);
        final String hash = cache.put("cache-key", data);
        final Path artifact = cache.getArtifactFile(hash).toPath();
        Files.delete(artifact);

        assertEquals(hash, cache.getValidHash("cache-key"));
        assertArrayEquals(data, Files.readAllBytes(artifact));
    }

}
