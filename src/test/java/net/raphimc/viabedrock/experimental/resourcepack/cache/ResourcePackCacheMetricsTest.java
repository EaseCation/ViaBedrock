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

import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics.Tier.ARCHIVE;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics.Tier.CONTENT;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics.Tier.MOTION;
import static net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics.Tier.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackCacheMetricsTest {

    @Test
    void exposesBuildAndExecutorCapacityMetrics() {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        metrics.hit(RUNTIME);
        metrics.miss(RUNTIME);
        metrics.build(RUNTIME);
        metrics.waiter(RUNTIME);
        metrics.recordBuildTime(5L);
        metrics.recordBuildTime(RUNTIME, 10L);
        metrics.recordBuildTime(RUNTIME, 30L);
        metrics.recordBuildTime(MOTION, 20L);
        metrics.executorCapacity(3, 64, 4, 128);
        metrics.executorSnapshot(2, 7, 1, 9);
        metrics.cpuExecutorRejected();
        metrics.ioExecutorRejected();
        metrics.httpRequestCapacity(8L, 64L);
        metrics.setPendingHttpRequests(3L);
        metrics.httpTokenRequestRejected();
        metrics.httpGlobalRequestRejected();
        metrics.setActiveArtifactLeases(2L);
        metrics.setArtifactDiskUsage(1_024L, 3L);
        metrics.recordArtifactDiskCleanup(512L, 1L);
        metrics.artifactDiskCleanupFailure();
        metrics.setActiveArtifactBuildWorkspaces(2L);
        metrics.setArtifactBuildWorkspaceDiskUsage(768L, 3L);
        metrics.recordArtifactBuildWorkspaceCleanup(512L, 2L);
        metrics.artifactBuildWorkspaceCleanupFailure();
        metrics.setCasDiskUsage(2_048L, 4L);
        metrics.recordCasDiskCleanup(1_024L, 2L);
        metrics.casDiskCleanupFailure();
        metrics.setTrustedAliasConflictState(12L, 768L, 4_096L, true);
        metrics.hit(ARCHIVE);
        metrics.miss(ARCHIVE);
        metrics.build(ARCHIVE);
        metrics.failure(ARCHIVE);
        metrics.waiter(ARCHIVE);
        metrics.setInflight(ARCHIVE, 2L);
        metrics.setWeight(ARCHIVE, 100L, 200L);
        metrics.eviction(ARCHIVE);
        metrics.hit(CONTENT);
        metrics.miss(CONTENT);
        metrics.build(CONTENT);
        metrics.failure(CONTENT);
        metrics.waiter(CONTENT);
        metrics.setInflight(CONTENT, 3L);
        metrics.setWeight(CONTENT, 300L, 400L);
        metrics.eviction(CONTENT);
        metrics.hit(MOTION);
        metrics.miss(MOTION);
        metrics.build(MOTION);
        metrics.failure(MOTION);
        metrics.waiter(MOTION);
        metrics.setInflight(MOTION, 4L);
        metrics.setWeight(MOTION, 500L, 600L);
        metrics.eviction(MOTION);

        assertEquals(1L, metrics.getRuntimeHits());
        assertEquals(1L, metrics.getRuntimeMisses());
        assertEquals(1L, metrics.getRuntimeBuilds());
        assertEquals(1L, metrics.getRuntimeWaiters());
        assertEquals(65L, metrics.getBuildTimeMillis());
        assertEquals(4L, metrics.getBuildTimeSamples());
        assertEquals(16.25D, metrics.getAverageBuildTimeMillis());
        assertEquals(30L, metrics.getMaxBuildTimeMillis());
        assertEquals(40L, metrics.getRuntimeBuildTimeMillis());
        assertEquals(2L, metrics.getRuntimeBuildTimeSamples());
        assertEquals(20D, metrics.getRuntimeAverageBuildTimeMillis());
        assertEquals(30L, metrics.getRuntimeMaxBuildTimeMillis());
        assertEquals(20L, metrics.getMotionBuildTimeMillis());
        assertEquals(1L, metrics.getMotionBuildTimeSamples());
        assertEquals(20D, metrics.getMotionAverageBuildTimeMillis());
        assertEquals(20L, metrics.getMotionMaxBuildTimeMillis());
        assertEquals(0L, metrics.getArchiveBuildTimeMillis());
        assertEquals(0L, metrics.getArchiveBuildTimeSamples());
        assertEquals(0D, metrics.getArchiveAverageBuildTimeMillis());
        assertEquals(0L, metrics.getArchiveMaxBuildTimeMillis());
        assertEquals(2L, metrics.getExecutorRejections());
        assertEquals(1L, metrics.getCpuExecutorRejections());
        assertEquals(1L, metrics.getIoExecutorRejections());
        assertEquals(3, metrics.getCpuExecutorWorkerCount());
        assertEquals(64, metrics.getCpuExecutorQueueCapacity());
        assertEquals(2, metrics.getCpuExecutorActiveThreads());
        assertEquals(7, metrics.getCpuExecutorQueueSize());
        assertEquals(4, metrics.getIoExecutorWorkerCount());
        assertEquals(128, metrics.getIoExecutorQueueCapacity());
        assertEquals(1, metrics.getIoExecutorActiveThreads());
        assertEquals(9, metrics.getIoExecutorQueueSize());
        assertEquals(2L, metrics.getActiveArtifactLeases());
        assertEquals(1_024L, metrics.getArtifactDiskBytes());
        assertEquals(3L, metrics.getArtifactDiskFiles());
        assertEquals(512L, metrics.getArtifactDiskDeletedBytes());
        assertEquals(1L, metrics.getArtifactDiskDeletedFiles());
        assertEquals(1L, metrics.getArtifactDiskCleanupFailures());
        assertEquals(2L, metrics.getActiveArtifactBuildWorkspaces());
        assertEquals(768L, metrics.getArtifactBuildWorkspaceBytes());
        assertEquals(3L, metrics.getArtifactBuildWorkspaceFiles());
        assertEquals(768L, metrics.getArtifactBuildWorkspacePeakBytes());
        assertEquals(512L, metrics.getArtifactBuildWorkspaceDeletedBytes());
        assertEquals(2L, metrics.getArtifactBuildWorkspaceDeletedFiles());
        assertEquals(1L, metrics.getArtifactBuildWorkspaceCleanupFailures());
        assertEquals(2_048L, metrics.getCasDiskBytes());
        assertEquals(4L, metrics.getCasDiskFiles());
        assertEquals(1_024L, metrics.getCasDiskDeletedBytes());
        assertEquals(2L, metrics.getCasDiskDeletedFiles());
        assertEquals(1L, metrics.getCasDiskCleanupFailures());
        assertEquals(12L, metrics.getTrustedAliasConflictTombstones());
        assertEquals(768L, metrics.getTrustedAliasConflictTombstoneBytes());
        assertEquals(4_096L, metrics.getTrustedAliasConflictTombstoneCapacity());
        assertTrue(metrics.getTrustedAliasGlobalQuarantine());
        assertEquals(1L, metrics.getArchiveHits());
        assertEquals(1L, metrics.getArchiveMisses());
        assertEquals(1L, metrics.getArchiveBuilds());
        assertEquals(1L, metrics.getArchiveFailures());
        assertEquals(1L, metrics.getArchiveWaiters());
        assertEquals(2L, metrics.getArchiveInflight());
        assertEquals(100L, metrics.getArchiveWeightBytes());
        assertEquals(200L, metrics.getArchiveMaxWeightBytes());
        assertEquals(1L, metrics.getArchiveEvictions());
        assertEquals(1L, metrics.getContentHits());
        assertEquals(1L, metrics.getContentMisses());
        assertEquals(1L, metrics.getContentBuilds());
        assertEquals(1L, metrics.getContentFailures());
        assertEquals(1L, metrics.getContentWaiters());
        assertEquals(3L, metrics.getContentInflight());
        assertEquals(300L, metrics.getContentWeightBytes());
        assertEquals(400L, metrics.getContentMaxWeightBytes());
        assertEquals(1L, metrics.getContentEvictions());
        assertEquals(1L, metrics.getMotionHits());
        assertEquals(1L, metrics.getMotionMisses());
        assertEquals(1L, metrics.getMotionBuilds());
        assertEquals(1L, metrics.getMotionFailures());
        assertEquals(1L, metrics.getMotionWaiters());
        assertEquals(4L, metrics.getMotionInflight());
        assertEquals(500L, metrics.getMotionWeightBytes());
        assertEquals(600L, metrics.getMotionMaxWeightBytes());
        assertEquals(1L, metrics.getMotionEvictions());
        assertEquals(3L, metrics.getPendingHttpRequests());
        assertEquals(8L, metrics.getMaxPendingHttpRequestsPerToken());
        assertEquals(64L, metrics.getMaxPendingHttpRequestsGlobal());
        assertEquals(1L, metrics.getHttpTokenRequestRejections());
        assertEquals(1L, metrics.getHttpGlobalRequestRejections());
    }

    @Test
    void registrationReplacesStaleBeanAndOldOwnerCannotUnregisterReplacement() throws Exception {
        final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        final ObjectName name = new ObjectName(ResourcePackCacheMetrics.OBJECT_NAME);
        final ResourcePackCacheMetrics first = new ResourcePackCacheMetrics();
        final ResourcePackCacheMetrics second = new ResourcePackCacheMetrics();
        try {
            first.recordBuildTime(RUNTIME, 17L);
            first.setWeight(RUNTIME, 123L, 456L);
            first.register();
            assertEquals(first.getRegistrationId(), server.getAttribute(name, "RegistrationId"));
            assertEquals(0L, server.getAttribute(name, "ArchiveBuilds"));
            assertEquals(0L, server.getAttribute(name, "ContentBuilds"));
            assertEquals(0L, server.getAttribute(name, "MotionBuilds"));
            for (String tier : new String[]{
                    "Archive", "Content", "Motion", "Blob", "Layer", "Runtime", "Artifact"}) {
                final long expected = tier.equals("Runtime") ? 17L : 0L;
                assertEquals(expected,
                        ((Number) server.getAttribute(name, tier + "BuildTimeMillis")).longValue());
                assertEquals(tier.equals("Runtime") ? 1L : 0L,
                        ((Number) server.getAttribute(name, tier + "BuildTimeSamples")).longValue());
                assertEquals((double) expected,
                        ((Number) server.getAttribute(name, tier + "AverageBuildTimeMillis")).doubleValue());
                assertEquals(expected,
                        ((Number) server.getAttribute(name, tier + "MaxBuildTimeMillis")).longValue());
            }
            assertEquals(123L, server.getAttribute(name, "RuntimeWeightBytes"));
            assertEquals(456L, server.getAttribute(name, "RuntimeMaxWeightBytes"));
            assertEquals(0L, server.getAttribute(name, "TrustedAliasConflictTombstones"));
            assertEquals(0L, server.getAttribute(name, "TrustedAliasConflictTombstoneBytes"));
            assertEquals(0L, server.getAttribute(name, "TrustedAliasConflictTombstoneCapacity"));
            assertEquals(false, server.getAttribute(name, "TrustedAliasGlobalQuarantine"));

            second.register();
            assertEquals(second.getRegistrationId(), server.getAttribute(name, "RegistrationId"));

            first.unregister();
            assertTrue(server.isRegistered(name));
            assertEquals(second.getRegistrationId(), server.getAttribute(name, "RegistrationId"));

            second.unregister();
            assertFalse(server.isRegistered(name));
        } finally {
            first.unregister();
            second.unregister();
        }
    }

}
