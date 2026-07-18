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
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackPipelineTestFixture.PACK_KEY;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackPipelineTestFixture.config;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackPipelineTestFixture.resourcePackArchive;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackPipelineTestFixture.writeJavaArtifact;

class ResourcePackPipelineSingleFlightTest {

    @Test
    void twentyConcurrentSessionsBuildEverySharedStageOnce(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final ResourcePackArchiveStore archiveStore = new ResourcePackArchiveStore(
                    tempDir.resolve("server-packs"), scheduler, metrics, config);
            final SharedPackRuntimeCache runtimeCache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, archiveStore);
            final JavaPackCache artifactCache = new JavaPackCache(
                    tempDir.resolve("java-packs").toFile(), scheduler, metrics);
            final byte[] archive = resourcePackArchive("single_flight");
            final AtomicInteger downloads = new AtomicInteger();
            final AtomicInteger conversions = new AtomicInteger();
            final CountDownLatch downloadStarted = new CountDownLatch(1);
            final CountDownLatch releaseDownload = new CountDownLatch(1);
            final CountDownLatch conversionStarted = new CountDownLatch(1);
            final CountDownLatch allConversionRequests = new CountDownLatch(20);
            final CountDownLatch releaseConversion = new CountDownLatch(1);
            final List<ResourcePack> sourcePacks = new CopyOnWriteArrayList<>();
            final List<SharedPackRuntime> runtimes = new CopyOnWriteArrayList<>();

            final ResourcePackArchiveStore.ArchiveLoader loader = () -> {
                downloads.incrementAndGet();
                downloadStarted.countDown();
                if (!releaseDownload.await(10L, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release the archive download");
                }
                return archive;
            };
            final JavaPackCache.ArtifactBuilder converter = target -> {
                conversions.incrementAndGet();
                conversionStarted.countDown();
                if (!releaseConversion.await(10L, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release Java pack conversion");
                }
                writeJavaArtifact(target);
            };

            final List<CompletableFuture<JavaPackCache.ArtifactRef>> sessions = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                sessions.add(archiveStore.loadFromSource(
                                "https://packs.example/shared.mcpack", PACK_KEY, new byte[0], "", loader)
                        .thenCompose(pack -> {
                            sourcePacks.add(pack);
                            return runtimeCache.acquireAsync(List.of(pack), List.of(""));
                        })
                        .thenCompose(lease -> {
                            runtimes.add(lease.runtime());
                            final CompletableFuture<JavaPackCache.ArtifactRef> artifact =
                                    artifactCache.getOrBuild(lease.runtime().artifactKey(true), converter);
                            allConversionRequests.countDown();
                            return artifact.whenComplete((value, error) -> lease.close());
                        }));
            }

            try {
                assertTrue(downloadStarted.await(10L, TimeUnit.SECONDS));
                assertEquals(1, downloads.get());
            } finally {
                releaseDownload.countDown();
            }
            try {
                assertTrue(conversionStarted.await(20L, TimeUnit.SECONDS));
                assertTrue(allConversionRequests.await(20L, TimeUnit.SECONDS));
                assertEquals(1, conversions.get());
            } finally {
                releaseConversion.countDown();
            }

            final List<JavaPackCache.ArtifactRef> artifacts = new ArrayList<>();
            for (CompletableFuture<JavaPackCache.ArtifactRef> session : sessions) {
                artifacts.add(session.get(20L, TimeUnit.SECONDS));
            }

            assertEquals(20, sourcePacks.size());
            assertEquals(20, runtimes.size());
            sourcePacks.forEach(pack -> assertSame(sourcePacks.getFirst(), pack));
            runtimes.forEach(runtime -> assertSame(runtimes.getFirst(), runtime));
            artifacts.forEach(artifact -> assertEquals(artifacts.getFirst(), artifact));

            final SharedPackRuntime runtime = runtimes.getFirst();
            final IdentityHashMap<Object, Boolean> uniqueLayers = new IdentityHashMap<>();
            runtime.parsedLayersBottomToTop().forEach(layer -> uniqueLayers.put(layer, Boolean.TRUE));
            assertEquals(uniqueLayers.size(), metrics.getLayerBuilds());
            assertEquals(1L, metrics.getArchiveBuilds());
            assertEquals(1L, metrics.getContentBuilds());
            assertEquals(1L, metrics.getBlobBuilds());
            assertEquals(1L, metrics.getRuntimeBuilds());
            assertEquals(1L, metrics.getMotionBuilds());
            assertEquals(1L, metrics.getArtifactBuilds());
            assertEquals(19L, metrics.getArtifactWaiters());
            assertEquals(0L, metrics.getActiveRuntimeLeases());
        } finally {
            scheduler.shutdown();
        }
    }

}
