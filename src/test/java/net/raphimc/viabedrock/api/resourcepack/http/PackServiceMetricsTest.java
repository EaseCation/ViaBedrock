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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackServiceMetricsTest {

    @Test
    void rendersCumulativeDownloadHistogramWithBoundedLabels() {
        final PackServiceMetrics metrics = new PackServiceMetrics();
        final long started = metrics.beginDownload();
        metrics.finishDownload(started, "failure", "GET", "full", "sensitive-path-/tmp/artifact.zip",
                123L, false);

        final String rendered = new String(metrics.render(
                new PackServiceStore.StorageSnapshot(1L, 1L, 0L, 123L, 1_024L, 2_048L, 1_024L, 1_024L),
                true), StandardCharsets.UTF_8);

        assertTrue(rendered.contains(
                "viabedrock_pack_service_download_failures_total{reason=\"unknown_io\"} 1"));
        assertFalse(rendered.contains("sensitive-path"));

        final String bucketPrefix = "viabedrock_pack_service_download_transfer_duration_seconds_bucket"
                + "{result=\"failure\",method=\"get\",transfer=\"full\",le=\"";
        final List<Long> buckets = new ArrayList<>();
        for (String line : rendered.lines().toList()) {
            if (!line.startsWith(bucketPrefix)) continue;
            buckets.add(Long.parseLong(line.substring(line.lastIndexOf(' ') + 1)));
        }
        assertEquals(15, buckets.size());
        for (int i = 1; i < buckets.size(); i++) {
            assertTrue(buckets.get(i) >= buckets.get(i - 1), "Histogram buckets must be cumulative");
        }
        assertEquals(1L, buckets.get(buckets.size() - 1));
        assertTrue(rendered.contains(
                "viabedrock_pack_service_download_transfer_duration_seconds_count"
                        + "{result=\"failure\",method=\"get\",transfer=\"full\"} 1"));
    }
}
