/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.resourcepack.cache;

import net.raphimc.viabedrock.ViaBedrockConfig;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.api.resourcepack.content.PackContentView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class FrozenPackBlobTest {

    @Test
    void canonicalBlobIsReadOnlyAndDoesNotRetainExpandedPayload(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final ResourcePackArchiveStore store = new ResourcePackArchiveStore(
                    tempDir.resolve("server-packs"), scheduler, metrics, config);
            final RetentionProbe probe = createRetentionProbe(store, 8 * 1024 * 1024);

            awaitCollected(probe.sourcePayload());
            final FrozenPackBlob blob = probe.blob();
            assertTrue(blob.isProductionCanonical());
            assertEquals(FrozenPackBlob.BackingKind.CANONICAL_CAS, blob.backingKind());
            assertEquals(probe.digest(), blob.contentDigest());
            assertTrue(blob.canonicalPath().filter(Files::isRegularFile).isPresent());
            assertTrue(blob.estimatedHeapWeightBytes() < 512L * 1024L,
                    "A disk-backed blob must retain its index, not the 8 MiB payload");

            final PackContentView view = blob.content();
            assertFalse(List.of(PackContentView.class.getMethods()).stream()
                    .map(Method::getName).anyMatch(name -> name.startsWith("put")));
            final List<String> paths = view.getFilesDeep("", "");
            paths.clear();
            assertTrue(view.getFilesDeep("", "").contains("payload.bin"));
            assertEquals(8L * 1024L * 1024L, view.size("payload.bin"));
            try (InputStream input = view.open("payload.bin")) {
                assertNotNull(input);
                assertEquals(7, input.read());
            }
            assertThrows(UnsupportedOperationException.class,
                    () -> blob.resourcePack().content().put("mutated.txt", new byte[]{1}));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void canonicalBlobRejectsDigestPathContainingDifferentContent(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final Path storeRoot = tempDir.resolve("server-packs");
            final ResourcePackArchiveStore store = new ResourcePackArchiveStore(
                    storeRoot, scheduler, metrics, config);
            final ResourcePack expected = pack(UUID.randomUUID(), "expected", new byte[]{1});
            final ResourcePack different = pack(UUID.randomUUID(), "different", new byte[]{2});
            final ContentDigest expectedDigest = ContentDigest.compute(expected.content());
            final Path forged = storeRoot.resolve("v2/content/sha256")
                    .resolve(expectedDigest.hex().substring(0, 2))
                    .resolve(expectedDigest.hex() + ".zip");
            Files.createDirectories(forged.getParent());
            different.content().writeZip(forged);

            final IOException failure = assertThrows(IOException.class,
                    () -> store.openFrozenBlob(expectedDigest));

            assertTrue(failure.getMessage().contains("digest mismatch"));
            assertTrue(Files.isRegularFile(forged));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void completedBlobProtectsCanonicalFileUntilBlobIsCollectable(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final AtomicLong currentTimeMillis = new AtomicLong(System.currentTimeMillis());
        try {
            final ResourcePackArchiveStore store = new ResourcePackArchiveStore(
                    tempDir.resolve("server-packs"), scheduler, metrics, config,
                    System::nanoTime, currentTimeMillis::get);
            final CanonicalLifetimeProbe probe = openAndProtectCanonical(store, currentTimeMillis);

            awaitCollected(probe.blob());
            store.cleanupNow();

            assertFalse(Files.exists(probe.canonicalPath()));
        } finally {
            scheduler.shutdown();
        }
    }

    private static RetentionProbe createRetentionProbe(final ResourcePackArchiveStore store,
                                                       final int payloadBytes) throws IOException {
        final byte[] payload = new byte[payloadBytes];
        payload[0] = 7;
        final WeakReference<byte[]> payloadReference = new WeakReference<>(payload);
        final ResourcePack source = pack(UUID.randomUUID(), "retained", payload);
        final ContentDigest digest = ContentDigest.compute(source.content());
        store.ensureCanonical(source, digest);
        return new RetentionProbe(store.openFrozenBlob(digest), digest, payloadReference);
    }

    private static CanonicalLifetimeProbe openAndProtectCanonical(
            final ResourcePackArchiveStore store, final AtomicLong currentTimeMillis) throws IOException {
        final ResourcePack source = pack(UUID.randomUUID(), "lifetime", new byte[]{1, 2, 3});
        final ContentDigest digest = ContentDigest.compute(source.content());
        store.ensureCanonical(source, digest);
        final FrozenPackBlob blob = store.openFrozenBlob(digest);
        final Path canonical = blob.canonicalPath().orElseThrow();
        currentTimeMillis.addAndGet(TimeUnit.DAYS.toMillis(8L));
        store.cleanupNow();
        assertTrue(Files.isRegularFile(canonical));
        return new CanonicalLifetimeProbe(new WeakReference<>(blob), canonical);
    }

    private static ResourcePack pack(final UUID id, final String name, final byte[] payload) {
        final InMemoryContent content = new InMemoryContent();
        content.putString("manifest.json", """
                {"format_version":2,"header":{"name":"%s","uuid":"%s","version":[1,0,0]}}
                """.formatted(name, id));
        content.put("payload.bin", payload);
        return new ResourcePack(content);
    }

    private static ViaBedrockConfig config(final Path tempDir) throws Exception {
        final Path configPath = tempDir.resolve("viabedrock.yml");
        Files.writeString(configPath, """
                resource-pack-cache:
                  memory-budget-mib: 64
                  memory-hard-limit-mib: 128
                  disk-budget-mib: 128
                  disk-idle-days: 7
                  cpu-workers: 4
                  io-workers: 4
                  queue-capacity: 64
                """);
        final ViaBedrockConfig config = new ViaBedrockConfig(configPath.toFile(), Logger.getAnonymousLogger());
        config.reload();
        return config;
    }

    private static void awaitCollected(final WeakReference<?> reference) {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (reference.get() == null) return;
            System.gc();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
        }
        fail("Expected source payload or blob to become collectable");
    }

    private record RetentionProbe(FrozenPackBlob blob, ContentDigest digest,
                                  WeakReference<byte[]> sourcePayload) {
    }

    private record CanonicalLifetimeProbe(WeakReference<FrozenPackBlob> blob, Path canonicalPath) {
    }

}
