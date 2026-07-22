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

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record PackServiceConfig(InetSocketAddress publicAddress, InetSocketAddress internalAddress,
                                InetSocketAddress metricsAddress, Path dataDirectory, String sharedSecret,
                                long maxUploadBytes, Duration pendingTimeout, long cacheBudgetBytes,
                                Duration cacheIdleTime, Duration maintenanceInterval, int workerThreads,
                                int maxPendingDownloads) {

    private static final long MIB = 1024L * 1024L;

    public PackServiceConfig {
        Objects.requireNonNull(publicAddress, "publicAddress");
        Objects.requireNonNull(internalAddress, "internalAddress");
        Objects.requireNonNull(metricsAddress, "metricsAddress");
        dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new IllegalArgumentException("PACK_SERVICE_SHARED_SECRET must not be empty");
        }
        if (maxUploadBytes < 1L || cacheBudgetBytes < 1L) {
            throw new IllegalArgumentException("Pack service byte limits must be positive");
        }
        if (pendingTimeout.isZero() || pendingTimeout.isNegative()
                || cacheIdleTime.isZero() || cacheIdleTime.isNegative()
                || maintenanceInterval.isZero() || maintenanceInterval.isNegative()) {
            throw new IllegalArgumentException("Pack service durations must be positive");
        }
        if (workerThreads < 1 || maxPendingDownloads < 1) {
            throw new IllegalArgumentException("Pack service concurrency limits must be positive");
        }
        if (publicAddress.getPort() == internalAddress.getPort()
                || publicAddress.getPort() == metricsAddress.getPort()
                || internalAddress.getPort() == metricsAddress.getPort()) {
            throw new IllegalArgumentException("Pack service public, internal and metrics ports must be distinct");
        }
    }

    public static PackServiceConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static PackServiceConfig fromEnvironment(final Map<String, String> environment) {
        final String publicHost = value(environment, "PACK_SERVICE_PUBLIC_BIND_ADDRESS", "0.0.0.0");
        final String internalHost = value(environment, "PACK_SERVICE_INTERNAL_BIND_ADDRESS", "0.0.0.0");
        final String metricsHost = value(environment, "PACK_SERVICE_METRICS_BIND_ADDRESS", "0.0.0.0");
        return new PackServiceConfig(
                new InetSocketAddress(publicHost, integer(environment, "PACK_SERVICE_PUBLIC_PORT", 8080, 1, 65_535)),
                new InetSocketAddress(internalHost, integer(environment, "PACK_SERVICE_INTERNAL_PORT", 8081, 1, 65_535)),
                new InetSocketAddress(metricsHost, integer(environment, "PACK_SERVICE_METRICS_PORT", 9462, 1, 65_535)),
                Path.of(value(environment, "PACK_SERVICE_DATA_DIR", "/data")),
                required(environment, "PACK_SERVICE_SHARED_SECRET"),
                mebibytes(environment, "PACK_SERVICE_MAX_UPLOAD_MIB", 2_048),
                Duration.ofSeconds(integer(environment, "PACK_SERVICE_PENDING_TIMEOUT_SECONDS", 300, 1, 86_400)),
                mebibytes(environment, "PACK_SERVICE_CACHE_BUDGET_MIB", 20_480),
                Duration.ofDays(integer(environment, "PACK_SERVICE_CACHE_IDLE_DAYS", 7, 1, 3_650)),
                Duration.ofSeconds(integer(environment, "PACK_SERVICE_MAINTENANCE_INTERVAL_SECONDS", 3_600, 10, 86_400)),
                integer(environment, "PACK_SERVICE_WORKER_THREADS", 32, 2, 256),
                integer(environment, "PACK_SERVICE_MAX_PENDING_DOWNLOADS", 256, 1, 4_096));
    }

    private static long mebibytes(final Map<String, String> environment, final String name, final int fallback) {
        final int value = integer(environment, name, fallback, 1, Integer.MAX_VALUE);
        try {
            return Math.multiplyExact((long) value, MIB);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is too large", e);
        }
    }

    private static int integer(final Map<String, String> environment, final String name, final int fallback,
                               final int minimum, final int maximum) {
        final String raw = value(environment, name, Integer.toString(fallback));
        final int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static String required(final Map<String, String> environment, final String name) {
        final String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be provided");
        }
        return value;
    }

    private static String value(final Map<String, String> environment, final String name, final String fallback) {
        final String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
