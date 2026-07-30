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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class PackServiceMetrics {

    static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final double[] DOWNLOAD_DURATION_BUCKETS_SECONDS = {
            0.01D, 0.05D, 0.1D, 0.25D, 0.5D, 1D, 2.5D, 5D, 10D, 20D, 30D, 60D, 120D, 300D
    };

    private final LongAdder lookupHits = new LongAdder();
    private final LongAdder lookupMisses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder evictedBytes = new LongAdder();
    private final LongAdder downloadBytes = new LongAdder();
    private final LongAdder downloadDurationNanos = new LongAdder();
    private final LongAdder downloadDurationCount = new LongAdder();
    private final LongAdder rangeRequests = new LongAdder();
    private final LongAdder uploadBytes = new LongAdder();
    private final LongAdder uploadDurationNanos = new LongAdder();
    private final LongAdder uploadDurationCount = new LongAdder();
    private final LongAdder pendingDurationNanos = new LongAdder();
    private final LongAdder pendingDurationCount = new LongAdder();
    private final LongAdder authFailures = new LongAdder();
    private final AtomicLong activeDownloads = new AtomicLong();
    private final AtomicLong activeUploads = new AtomicLong();
    private final Map<String, LongAdder> downloads = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> downloadFailures = new ConcurrentHashMap<>();
    private final Map<DownloadDurationKey, DurationHistogram> downloadDurations = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> uploads = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> validationFailures = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> pendingResults = new ConcurrentHashMap<>();
    private final Map<HttpKey, LongAdder> httpRequests = new ConcurrentHashMap<>();

    PackServiceMetrics() {
        initialize(this.downloads, "success", "not_found", "invalid_range", "failure");
        initialize(this.downloadFailures, "client_disconnect", "write_timeout", "artifact_read_error",
                "response_error", "unknown_io");
        initialize(this.uploads, "success", "not_found", "rejected", "failure");
        initialize(this.validationFailures, "size", "sha1", "zip", "lookup_key", "conflict", "metadata");
        initialize(this.pendingResults, "created", "completed", "timeout", "cancelled");
    }

    void lookup(final boolean hit) {
        (hit ? this.lookupHits : this.lookupMisses).increment();
    }

    long beginDownload() {
        this.activeDownloads.incrementAndGet();
        return System.nanoTime();
    }

    void finishDownload(final long startedNanos, final String result, final String method,
                        final String transfer, final String failureReason,
                        final long bytes, final boolean range) {
        this.activeDownloads.updateAndGet(value -> Math.max(0L, value - 1L));
        final String normalizedResult = normalizeDownloadResult(result);
        final String normalizedMethod = "HEAD".equalsIgnoreCase(method) ? "head" : "get";
        final String normalizedTransfer = normalizeDownloadTransfer(transfer);
        final long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
        this.downloads.get(normalizedResult).increment();
        if ("failure".equals(normalizedResult)) {
            this.downloadFailures.get(normalizeDownloadFailureReason(failureReason)).increment();
        }
        this.downloadBytes.add(Math.max(0L, bytes));
        this.downloadDurationNanos.add(elapsedNanos);
        this.downloadDurationCount.increment();
        this.downloadDurations.computeIfAbsent(
                new DownloadDurationKey(normalizedResult, normalizedMethod, normalizedTransfer),
                ignored -> new DurationHistogram(DOWNLOAD_DURATION_BUCKETS_SECONDS)).observe(elapsedNanos);
        if (range) this.rangeRequests.increment();
    }

    long beginUpload() {
        this.activeUploads.incrementAndGet();
        return System.nanoTime();
    }

    void finishUpload(final long startedNanos, final String result, final long bytes) {
        this.activeUploads.updateAndGet(value -> Math.max(0L, value - 1L));
        this.uploads.computeIfAbsent(result, ignored -> new LongAdder()).increment();
        this.uploadBytes.add(Math.max(0L, bytes));
        this.uploadDurationNanos.add(Math.max(0L, System.nanoTime() - startedNanos));
        this.uploadDurationCount.increment();
    }

    void validationFailure(final String reason) {
        this.validationFailures.computeIfAbsent(reason, ignored -> new LongAdder()).increment();
    }

    void pendingResult(final String result) {
        this.pendingResults.computeIfAbsent(result, ignored -> new LongAdder()).increment();
    }

    void pendingResult(final String result, final long elapsedMillis) {
        this.pendingResult(result);
        this.pendingDurationNanos.add(Math.max(0L, elapsedMillis) * 1_000_000L);
        this.pendingDurationCount.increment();
    }

    void authenticationFailure() {
        this.authFailures.increment();
    }

    void eviction(final long bytes) {
        this.evictions.increment();
        this.evictedBytes.add(Math.max(0L, bytes));
    }

    void httpRequest(final String route, final String method, final int status) {
        this.httpRequests.computeIfAbsent(new HttpKey(route, method, status), ignored -> new LongAdder()).increment();
    }

    byte[] render(final PackServiceStore.StorageSnapshot storage, final boolean ready) {
        final StringBuilder output = new StringBuilder(8_192);
        help(output, "viabedrock_pack_service_ready", "Whether the pack service can read and write its PVC.", "gauge");
        sample(output, "viabedrock_pack_service_ready", ready ? 1L : 0L);
        help(output, "viabedrock_pack_service_cache_lookups_total", "Central pack cache lookups.", "counter");
        labeled(output, "viabedrock_pack_service_cache_lookups_total", "result", "hit", this.lookupHits.sum());
        labeled(output, "viabedrock_pack_service_cache_lookups_total", "result", "miss", this.lookupMisses.sum());
        help(output, "viabedrock_pack_service_artifacts", "Published content-addressed artifacts.", "gauge");
        sample(output, "viabedrock_pack_service_artifacts", storage.artifacts());
        help(output, "viabedrock_pack_service_mappings", "Persisted lookup mappings.", "gauge");
        sample(output, "viabedrock_pack_service_mappings", storage.mappings());
        help(output, "viabedrock_pack_service_cache_bytes", "Bytes held by published artifacts.", "gauge");
        sample(output, "viabedrock_pack_service_cache_bytes", storage.artifactBytes());
        help(output, "viabedrock_pack_service_cache_budget_bytes", "Configured artifact cache budget.", "gauge");
        sample(output, "viabedrock_pack_service_cache_budget_bytes", storage.cacheBudgetBytes());
        help(output, "viabedrock_pack_service_cache_evictions_total", "Artifacts evicted by maintenance.", "counter");
        sample(output, "viabedrock_pack_service_cache_evictions_total", this.evictions.sum());
        help(output, "viabedrock_pack_service_cache_evicted_bytes_total", "Artifact bytes evicted by maintenance.", "counter");
        sample(output, "viabedrock_pack_service_cache_evicted_bytes_total", this.evictedBytes.sum());

        resultCounters(output, "viabedrock_pack_service_downloads_total", "Public pack downloads.", this.downloads);
        resultCounters(output, "viabedrock_pack_service_download_failures_total",
                "Failed public pack downloads by bounded I/O reason.", "reason", this.downloadFailures);
        help(output, "viabedrock_pack_service_downloads_active", "Downloads currently being served.", "gauge");
        sample(output, "viabedrock_pack_service_downloads_active", this.activeDownloads.get());
        help(output, "viabedrock_pack_service_download_bytes_total", "Bytes served to pack clients.", "counter");
        sample(output, "viabedrock_pack_service_download_bytes_total", this.downloadBytes.sum());
        duration(output, "viabedrock_pack_service_download_duration_seconds", "Completed download duration.",
                this.downloadDurationNanos.sum(), this.downloadDurationCount.sum());
        help(output, "viabedrock_pack_service_download_transfer_duration_seconds",
                "Public pack download duration by bounded result, method and transfer kind.", "histogram");
        this.downloadDurations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> renderDownloadDuration(output, entry.getKey(), entry.getValue()));
        help(output, "viabedrock_pack_service_range_requests_total", "Valid partial-content downloads.", "counter");
        sample(output, "viabedrock_pack_service_range_requests_total", this.rangeRequests.sum());

        resultCounters(output, "viabedrock_pack_service_uploads_total", "Internal artifact uploads.", this.uploads);
        help(output, "viabedrock_pack_service_uploads_active", "Uploads currently being received.", "gauge");
        sample(output, "viabedrock_pack_service_uploads_active", this.activeUploads.get());
        help(output, "viabedrock_pack_service_upload_bytes_total", "Bytes accepted from ViaProxy uploads.", "counter");
        sample(output, "viabedrock_pack_service_upload_bytes_total", this.uploadBytes.sum());
        duration(output, "viabedrock_pack_service_upload_duration_seconds", "Completed upload duration.",
                this.uploadDurationNanos.sum(), this.uploadDurationCount.sum());
        resultCounters(output, "viabedrock_pack_service_validation_failures_total",
                "Rejected uploads grouped by bounded validation reason.", this.validationFailures);

        help(output, "viabedrock_pack_service_pending", "Current pending conversion records.", "gauge");
        sample(output, "viabedrock_pack_service_pending", storage.pending());
        resultCounters(output, "viabedrock_pack_service_pending_results_total",
                "Pending record lifecycle results.", this.pendingResults);
        duration(output, "viabedrock_pack_service_pending_duration_seconds", "Completed pending wait duration.",
                this.pendingDurationNanos.sum(), this.pendingDurationCount.sum());
        help(output, "viabedrock_pack_service_auth_failures_total", "Rejected internal authentication attempts.", "counter");
        sample(output, "viabedrock_pack_service_auth_failures_total", this.authFailures.sum());

        help(output, "viabedrock_pack_service_pvc_total_bytes", "PVC filesystem total bytes.", "gauge");
        sample(output, "viabedrock_pack_service_pvc_total_bytes", storage.pvcTotalBytes());
        help(output, "viabedrock_pack_service_pvc_usable_bytes", "PVC filesystem usable bytes.", "gauge");
        sample(output, "viabedrock_pack_service_pvc_usable_bytes", storage.pvcUsableBytes());
        help(output, "viabedrock_pack_service_pvc_free_bytes", "PVC filesystem unallocated bytes.", "gauge");
        sample(output, "viabedrock_pack_service_pvc_free_bytes", storage.pvcFreeBytes());

        help(output, "viabedrock_pack_service_http_requests_total", "HTTP requests by normalized route, method and status.", "counter");
        this.httpRequests.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    final HttpKey key = entry.getKey();
                    output.append("viabedrock_pack_service_http_requests_total{route=\"")
                            .append(key.route()).append("\",method=\"").append(key.method())
                            .append("\",status=\"").append(key.status()).append("\"} ")
                            .append(entry.getValue().sum()).append('\n');
                });
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void resultCounters(final StringBuilder output, final String name, final String description,
                                       final Map<String, LongAdder> values) {
        resultCounters(output, name, description, "result", values);
    }

    private static void resultCounters(final StringBuilder output, final String name, final String description,
                                       final String label, final Map<String, LongAdder> values) {
        help(output, name, description, "counter");
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> labeled(output, name, label, entry.getKey(), entry.getValue().sum()));
    }

    private static void initialize(final Map<String, LongAdder> values, final String... labels) {
        for (String label : labels) values.put(label, new LongAdder());
    }

    private static void duration(final StringBuilder output, final String name, final String description,
                                 final long nanos, final long count) {
        help(output, name, description, "summary");
        output.append(name).append("_sum ").append(nanos / 1_000_000_000D).append('\n');
        output.append(name).append("_count ").append(count).append('\n');
    }

    private static void renderDownloadDuration(final StringBuilder output, final DownloadDurationKey key,
                                               final DurationHistogram histogram) {
        final String labels = "result=\"" + key.result() + "\",method=\"" + key.method()
                + "\",transfer=\"" + key.transfer() + "\"";
        for (int i = 0; i < DOWNLOAD_DURATION_BUCKETS_SECONDS.length; i++) {
            output.append("viabedrock_pack_service_download_transfer_duration_seconds_bucket{")
                    .append(labels).append(",le=\"")
                    .append(DOWNLOAD_DURATION_BUCKETS_SECONDS[i]).append("\"} ")
                    .append(histogram.bucket(i)).append('\n');
        }
        output.append("viabedrock_pack_service_download_transfer_duration_seconds_bucket{")
                .append(labels).append(",le=\"+Inf\"} ").append(histogram.count()).append('\n');
        output.append("viabedrock_pack_service_download_transfer_duration_seconds_sum{")
                .append(labels).append("} ").append(histogram.sumSeconds()).append('\n');
        output.append("viabedrock_pack_service_download_transfer_duration_seconds_count{")
                .append(labels).append("} ").append(histogram.count()).append('\n');
    }

    private static String normalizeDownloadResult(final String result) {
        return switch (result) {
            case "success", "not_found", "invalid_range", "failure" -> result;
            default -> "failure";
        };
    }

    private static String normalizeDownloadTransfer(final String transfer) {
        return switch (transfer) {
            case "full", "range", "head", "not_modified", "none" -> transfer;
            default -> "none";
        };
    }

    private static String normalizeDownloadFailureReason(final String reason) {
        if (reason == null) return "unknown_io";
        return switch (reason) {
            case "client_disconnect", "write_timeout", "artifact_read_error", "response_error", "unknown_io" ->
                    reason;
            default -> "unknown_io";
        };
    }

    private static void help(final StringBuilder output, final String name, final String description,
                             final String type) {
        output.append("# HELP ").append(name).append(' ').append(description).append('\n');
        output.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void sample(final StringBuilder output, final String name, final long value) {
        output.append(name).append(' ').append(value).append('\n');
    }

    private static void labeled(final StringBuilder output, final String name, final String label,
                                final String value, final long amount) {
        output.append(name).append('{').append(label).append("=\"").append(value)
                .append("\"} ").append(amount).append('\n');
    }

    private record HttpKey(String route, String method, int status) implements Comparable<HttpKey> {
        @Override
        public int compareTo(final HttpKey other) {
            int result = this.route.compareTo(other.route);
            if (result == 0) result = this.method.compareTo(other.method);
            if (result == 0) result = Integer.compare(this.status, other.status);
            return result;
        }
    }

    private record DownloadDurationKey(String result, String method, String transfer)
            implements Comparable<DownloadDurationKey> {
        @Override
        public int compareTo(final DownloadDurationKey other) {
            int comparison = this.result.compareTo(other.result);
            if (comparison == 0) comparison = this.method.compareTo(other.method);
            if (comparison == 0) comparison = this.transfer.compareTo(other.transfer);
            return comparison;
        }
    }

    private static final class DurationHistogram {
        private final long[] boundsNanos;
        private final LongAdder[] buckets;
        private final LongAdder count = new LongAdder();
        private final LongAdder sumNanos = new LongAdder();

        private DurationHistogram(final double[] boundsSeconds) {
            this.boundsNanos = new long[boundsSeconds.length];
            this.buckets = new LongAdder[boundsSeconds.length];
            for (int i = 0; i < boundsSeconds.length; i++) {
                this.boundsNanos[i] = (long) (boundsSeconds[i] * 1_000_000_000D);
                this.buckets[i] = new LongAdder();
            }
        }

        private void observe(final long nanos) {
            this.count.increment();
            this.sumNanos.add(nanos);
            for (int i = 0; i < this.boundsNanos.length; i++) {
                if (nanos <= this.boundsNanos[i]) this.buckets[i].increment();
            }
        }

        private long bucket(final int index) {
            return this.buckets[index].sum();
        }

        private long count() {
            return this.count.sum();
        }

        private double sumSeconds() {
            return this.sumNanos.sum() / 1_000_000_000D;
        }
    }
}
