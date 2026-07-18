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

public interface ResourcePackCacheMXBean {

    long getArchiveHits();

    long getArchiveMisses();

    long getArchiveBuilds();

    long getArchiveFailures();

    long getArchiveWaiters();

    long getArchiveInflight();

    long getArchiveWeightBytes();

    long getArchiveMaxWeightBytes();

    long getArchiveEvictions();

    long getArchiveBuildTimeMillis();

    long getArchiveBuildTimeSamples();

    double getArchiveAverageBuildTimeMillis();

    long getArchiveMaxBuildTimeMillis();

    long getContentHits();

    long getContentMisses();

    long getContentBuilds();

    long getContentFailures();

    long getContentWaiters();

    long getContentInflight();

    long getContentWeightBytes();

    long getContentMaxWeightBytes();

    long getContentEvictions();

    long getContentBuildTimeMillis();

    long getContentBuildTimeSamples();

    double getContentAverageBuildTimeMillis();

    long getContentMaxBuildTimeMillis();

    long getMotionHits();

    long getMotionMisses();

    long getMotionBuilds();

    long getMotionFailures();

    long getMotionWaiters();

    long getMotionInflight();

    long getMotionWeightBytes();

    long getMotionMaxWeightBytes();

    long getMotionEvictions();

    long getMotionBuildTimeMillis();

    long getMotionBuildTimeSamples();

    double getMotionAverageBuildTimeMillis();

    long getMotionMaxBuildTimeMillis();

    long getBlobHits();

    long getBlobMisses();

    long getBlobBuilds();

    long getBlobFailures();

    long getBlobWaiters();

    long getBlobInflight();

    long getBlobCompletedEntries();

    long getBlobWeightBytes();

    long getBlobMaxWeightBytes();

    long getBlobEvictions();

    long getBlobBuildTimeMillis();

    long getBlobBuildTimeSamples();

    double getBlobAverageBuildTimeMillis();

    long getBlobMaxBuildTimeMillis();

    long getLayerHits();

    long getLayerMisses();

    long getLayerBuilds();

    long getLayerFailures();

    long getLayerWaiters();

    long getLayerInflight();

    long getLayerWeightBytes();

    long getLayerMaxWeightBytes();

    long getLayerEvictions();

    long getLayerBuildTimeMillis();

    long getLayerBuildTimeSamples();

    double getLayerAverageBuildTimeMillis();

    long getLayerMaxBuildTimeMillis();

    long getRuntimeHits();

    long getRuntimeMisses();

    long getRuntimeBuilds();

    long getRuntimeFailures();

    long getRuntimeWaiters();

    long getRuntimeInflight();

    long getRuntimeWeightBytes();

    long getRuntimeMaxWeightBytes();

    long getRuntimeEvictions();

    long getRuntimeBuildTimeMillis();

    long getRuntimeBuildTimeSamples();

    double getRuntimeAverageBuildTimeMillis();

    long getRuntimeMaxBuildTimeMillis();

    long getArtifactHits();

    long getArtifactMisses();

    long getArtifactBuilds();

    long getArtifactFailures();

    long getArtifactWaiters();

    long getArtifactInflight();

    long getArtifactWeightBytes();

    long getArtifactMaxWeightBytes();

    long getArtifactEvictions();

    long getArtifactBuildTimeMillis();

    long getArtifactBuildTimeSamples();

    double getArtifactAverageBuildTimeMillis();

    long getArtifactMaxBuildTimeMillis();

    long getActiveRuntimeLeases();

    long getActiveArtifactLeases();

    long getActiveRuntimeWeightBytes();

    long getInflightEstimatedWeightBytes();

    long getArtifactDiskBytes();

    long getArtifactDiskFiles();

    long getArtifactDiskDeletedBytes();

    long getArtifactDiskDeletedFiles();

    long getArtifactDiskCleanupFailures();

    long getArtifactDiskMetricRefreshFailures();

    long getActiveArtifactBuildWorkspaces();

    long getArtifactBuildWorkspaceBytes();

    long getArtifactBuildWorkspaceFiles();

    long getArtifactBuildWorkspacePeakBytes();

    long getArtifactBuildWorkspaceDeletedBytes();

    long getArtifactBuildWorkspaceDeletedFiles();

    long getArtifactBuildWorkspaceCleanupFailures();

    long getCasDiskBytes();

    long getCasDiskFiles();

    long getCasDiskDeletedBytes();

    long getCasDiskDeletedFiles();

    long getCasDiskCleanupFailures();

    long getAliasConflicts();

    long getTrustedAliasConflictTombstones();

    long getTrustedAliasConflictTombstoneBytes();

    long getTrustedAliasConflictTombstoneCapacity();

    boolean getTrustedAliasGlobalQuarantine();

    String getRegistrationId();

    long getBuildTimeMillis();

    long getBuildTimeSamples();

    double getAverageBuildTimeMillis();

    long getMaxBuildTimeMillis();

    long getExecutorRejections();

    long getCpuExecutorRejections();

    int getCpuExecutorWorkerCount();

    int getCpuExecutorQueueCapacity();

    int getCpuExecutorActiveThreads();

    int getCpuExecutorQueueSize();

    long getIoExecutorRejections();

    int getIoExecutorWorkerCount();

    int getIoExecutorQueueCapacity();

    int getIoExecutorActiveThreads();

    int getIoExecutorQueueSize();

    long getPendingHttpRequests();

    long getMaxPendingHttpRequestsPerToken();

    long getMaxPendingHttpRequestsGlobal();

    long getHttpTokenRequestRejections();

    long getHttpGlobalRequestRejections();

}
