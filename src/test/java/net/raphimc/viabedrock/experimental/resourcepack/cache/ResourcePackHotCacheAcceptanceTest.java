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

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackPipelineTestFixture.PACK_KEY;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackPipelineTestFixture.config;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackPipelineTestFixture.resourcePackArchive;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackPipelineTestFixture.writeJavaArtifact;

class ResourcePackHotCacheAcceptanceTest {

    private static final int HOT_JOIN_COUNT = 99;

    @Test
    void knownRawDigestKeepsAllOuterLookupsAboveNinetyFivePercentAfterColdJoin(
            @TempDir final Path tempDir) throws Exception {
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
            final byte[] archive = resourcePackArchive("hot_cache");
            final byte[] rawDigest = MessageDigest.getInstance("SHA-256").digest(archive);
            final AtomicInteger artifactBuilds = new AtomicInteger();
            final JavaPackCache.ArtifactBuilder artifactBuilder = target -> {
                artifactBuilds.incrementAndGet();
                writeJavaArtifact(target);
            };

            final ResourcePackArchiveStore.Claim coldClaim = archiveStore.claim(rawDigest);
            assertTrue(coldClaim.leader());
            archiveStore.publish(coldClaim, archive);
            final ResourcePack coldPack = archiveStore.loadEffective(
                    coldClaim, PACK_KEY, new byte[0], "")
                    .get(10L, TimeUnit.SECONDS);
            final JavaPackCache.ArtifactRef coldArtifact = join(
                    runtimeCache, artifactCache, coldPack, artifactBuilder);
            final BuildCounts coldBuildCounts = BuildCounts.capture(metrics);
            final BypassedLookupCounts coldBypassedLookups = BypassedLookupCounts.capture(metrics);

            assertEquals(new BuildCounts(1L, 1L, 1L, 1L, 1L, 1L, 1L), coldBuildCounts);
            assertEquals(new BypassedLookupCounts(0L, 1L, 0L, 1L), coldBypassedLookups);
            assertEquals(1, artifactBuilds.get());

            for (int i = 0; i < HOT_JOIN_COUNT; i++) {
                final ResourcePackArchiveStore.Claim hotClaim = archiveStore.claim(rawDigest);
                assertFalse(hotClaim.leader());
                final ResourcePack hotPack = archiveStore.loadEffective(
                        hotClaim, PACK_KEY, new byte[0], "")
                        .get(10L, TimeUnit.SECONDS);
                assertEquals(coldArtifact, join(runtimeCache, artifactCache, hotPack, artifactBuilder));
            }

            assertEquals(coldBuildCounts, BuildCounts.capture(metrics));
            assertEquals(1, artifactBuilds.get());
            assertEquals(0L, metrics.getActiveRuntimeLeases());
            assertHitRateAboveNinetyFivePercent(
                    "archive", metrics.getArchiveHits(), metrics.getArchiveMisses());
            assertHitRateAboveNinetyFivePercent(
                    "content", metrics.getContentHits(), metrics.getContentMisses());
            assertHitRateAboveNinetyFivePercent(
                    "runtime", metrics.getRuntimeHits(), metrics.getRuntimeMisses());
            assertHitRateAboveNinetyFivePercent(
                    "motion", metrics.getMotionHits(), metrics.getMotionMisses());
            assertHitRateAboveNinetyFivePercent(
                    "artifact", metrics.getArtifactHits(), metrics.getArtifactMisses());

            assertEquals(coldBypassedLookups, BypassedLookupCounts.capture(metrics),
                    "Hot runtime hits must bypass blob and layer lookup");
        } finally {
            scheduler.shutdown();
        }
    }

    private static JavaPackCache.ArtifactRef join(
            final SharedPackRuntimeCache runtimeCache, final JavaPackCache artifactCache,
            final ResourcePack pack, final JavaPackCache.ArtifactBuilder artifactBuilder) throws Exception {
        final SharedPackRuntimeCache.RuntimeLease lease = runtimeCache.acquireAsync(
                List.of(pack), List.of("")).get(10L, TimeUnit.SECONDS);
        try {
            return artifactCache.getOrBuild(lease.runtime().artifactKey(true), artifactBuilder)
                    .get(10L, TimeUnit.SECONDS);
        } finally {
            lease.close();
        }
    }

    private static void assertHitRateAboveNinetyFivePercent(
            final String tier, final long hits, final long misses) {
        final long lookups = hits + misses;
        final double hitRate = lookups == 0L ? 0D : (double) hits / lookups;
        assertEquals(HOT_JOIN_COUNT + 1L, lookups, tier + " lookup count");
        assertTrue(hitRate > 0.95D, () -> tier + " hit rate was " + hitRate
                + " (hits=" + hits + ", misses=" + misses + ")");
    }

    private record BuildCounts(long archives, long contents, long blobs, long layers,
                               long runtimes, long motions, long artifacts) {

        private static BuildCounts capture(final ResourcePackCacheMetrics metrics) {
            return new BuildCounts(
                    metrics.getArchiveBuilds(), metrics.getContentBuilds(), metrics.getBlobBuilds(),
                    metrics.getLayerBuilds(), metrics.getRuntimeBuilds(), metrics.getMotionBuilds(),
                    metrics.getArtifactBuilds());
        }
    }

    private record BypassedLookupCounts(long blobHits, long blobMisses,
                                        long layerHits, long layerMisses) {

        private static BypassedLookupCounts capture(final ResourcePackCacheMetrics metrics) {
            return new BypassedLookupCounts(
                    metrics.getBlobHits(), metrics.getBlobMisses(),
                    metrics.getLayerHits(), metrics.getLayerMisses());
        }
    }

}
