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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.raphimc.viabedrock.api.resourcepack.http.PackServiceStore.ArtifactLease;
import net.raphimc.viabedrock.api.resourcepack.http.PackServiceStore.LookupResult;
import net.raphimc.viabedrock.api.resourcepack.http.PackServiceStore.MissingPendingException;
import net.raphimc.viabedrock.api.resourcepack.http.PackServiceStore.UploadValidationException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PackServiceHttpServer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(PackServiceHttpServer.class.getName());
    private static final String ACCESS_CONTEXT_ATTRIBUTE = PackServiceHttpServer.class.getName() + ".access";
    private static final Pattern ARTIFACT_PATH = Pattern.compile("^/packs/([0-9a-f]{40})\\.zip$");
    private static final Pattern PENDING_PATH = Pattern.compile(
            "^/packs/pending/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");
    private static final Pattern LOOKUP_PATH = Pattern.compile("^/internal/v1/lookups/([0-9a-f]{64})$");
    private static final Pattern INTERNAL_PENDING_PATH = Pattern.compile(
            "^/internal/v1/pending/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final PackServiceConfig config;
    private final PackServiceStore store;
    private final PackServiceMetrics metrics;
    private final ArtifactFileOpener artifactFileOpener;
    private final Consumer<String> accessLogger;
    private final byte[] expectedAuthorization;
    private final HttpServer publicServer;
    private final HttpServer internalServer;
    private final HttpServer metricsServer;
    private final ThreadPoolExecutor publicExecutor;
    private final ThreadPoolExecutor internalExecutor;
    private final ThreadPoolExecutor metricsExecutor;
    private final ScheduledExecutorService maintenanceExecutor;
    private final AtomicInteger pendingDownloads = new AtomicInteger();

    PackServiceHttpServer(final PackServiceConfig config, final PackServiceStore store,
                          final PackServiceMetrics metrics) throws IOException {
        this(config, store, metrics, path -> new RandomAccessFile(path.toFile(), "r"));
    }

    PackServiceHttpServer(final PackServiceConfig config, final PackServiceStore store,
                          final PackServiceMetrics metrics,
                          final ArtifactFileOpener artifactFileOpener) throws IOException {
        this(config, store, metrics, artifactFileOpener, message -> LOGGER.info(message));
    }

    PackServiceHttpServer(final PackServiceConfig config, final PackServiceStore store,
                          final PackServiceMetrics metrics,
                          final ArtifactFileOpener artifactFileOpener,
                          final Consumer<String> accessLogger) throws IOException {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.artifactFileOpener = Objects.requireNonNull(artifactFileOpener, "artifactFileOpener");
        this.accessLogger = Objects.requireNonNull(accessLogger, "accessLogger");
        this.expectedAuthorization = ("Bearer " + config.sharedSecret()).getBytes(StandardCharsets.UTF_8);
        this.publicExecutor = createExecutor(
                config.workerThreads(), config.workerThreads() * 8, "Pack Service Public");
        final int internalThreads = Math.max(2, Math.min(8, config.workerThreads()));
        this.internalExecutor = createExecutor(
                internalThreads, internalThreads * 8, "Pack Service Internal");
        this.metricsExecutor = createExecutor(2, 16, "Pack Service Metrics");
        this.maintenanceExecutor = new ScheduledThreadPoolExecutor(
                1, new NamedThreadFactory("Pack Service Maintenance"));
        this.publicServer = this.create(
                config.publicAddress(), "public", this::handlePublic, this.publicExecutor);
        this.internalServer = this.create(
                config.internalAddress(), "internal", this::handleInternal, this.internalExecutor);
        this.metricsServer = this.create(
                config.metricsAddress(), null, this::handleMetrics, this.metricsExecutor);
    }

    void start() {
        this.publicServer.start();
        this.internalServer.start();
        this.metricsServer.start();
        this.maintenanceExecutor.scheduleWithFixedDelay(() -> {
            try {
                this.store.maintain();
            } catch (Throwable error) {
                LOGGER.warning("[pack-service-maintenance] result=failed error="
                        + error.getClass().getSimpleName());
            }
        }, this.config.maintenanceInterval().toSeconds(), this.config.maintenanceInterval().toSeconds(),
                TimeUnit.SECONDS);
    }

    private HttpServer create(final java.net.InetSocketAddress address,
                              final String listener,
                              final com.sun.net.httpserver.HttpHandler handler,
                              final ThreadPoolExecutor executor) throws IOException {
        final HttpServer server = HttpServer.create(address, 128);
        server.createContext("/", exchange -> {
            if (listener == null) {
                handler.handle(exchange);
                return;
            }
            final AccessContext context = new AccessContext(listener, System.nanoTime());
            exchange.setAttribute(ACCESS_CONTEXT_ATTRIBUTE, context);
            try {
                handler.handle(exchange);
            } finally {
                if (!context.completed) {
                    this.logAccess(exchange, "unhandled", exchange.getRequestMethod(), 500, 0L);
                }
            }
        });
        server.setExecutor(executor);
        return server;
    }

    private static ThreadPoolExecutor createExecutor(final int threads, final int queueCapacity,
                                                     final String name) {
        return new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new NamedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void handlePublic(final HttpExchange exchange) throws IOException {
        final String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        final String path = exchange.getRequestURI().getPath();
        if (!("GET".equals(method) || "HEAD".equals(method))) {
            this.respond(exchange, "public", method, 405);
            return;
        }

        final Matcher artifact = ARTIFACT_PATH.matcher(path);
        if (artifact.matches()) {
            this.serveArtifact(exchange, "artifact", artifact.group(1));
            return;
        }
        final Matcher pending = PENDING_PATH.matcher(path);
        if (pending.matches()) {
            this.servePending(exchange, UUID.fromString(pending.group(1)));
            return;
        }
        this.respond(exchange, "not_found", method, 404);
    }

    private void servePending(final HttpExchange exchange, final UUID token) throws IOException {
        final String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        final int current = this.pendingDownloads.incrementAndGet();
        if (current > this.config.maxPendingDownloads()) {
            this.pendingDownloads.decrementAndGet();
            this.respond(exchange, "pending", method, 503);
            return;
        }
        try {
            final PackServiceStore.Artifact artifact = this.store.await(token).get(
                    this.config.pendingTimeout().toMillis(), TimeUnit.MILLISECONDS);
            this.serveArtifact(exchange, "pending", artifact.sha1());
        } catch (ExecutionException | CompletionException e) {
            final Throwable cause = unwrap(e);
            this.respond(exchange, "pending", method,
                    cause instanceof MissingPendingException ? 404 : cause instanceof TimeoutException ? 504 : 500);
        } catch (TimeoutException e) {
            this.respond(exchange, "pending", method, 504);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.respond(exchange, "pending", method, 503);
        } finally {
            this.pendingDownloads.decrementAndGet();
        }
    }

    private void serveArtifact(final HttpExchange exchange, final String route, final String sha1) throws IOException {
        final String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        final long started = this.metrics.beginDownload();
        long transferred = 0L;
        boolean partial = false;
        boolean responseStarted = false;
        String result = "success";
        int status = 500;
        try (ArtifactLease lease = this.store.acquire(sha1)) {
            if (lease == null) {
                result = "not_found";
                status = 404;
                sendEmpty(exchange, status);
                responseStarted = true;
                return;
            }
            final PackServiceStore.Artifact artifact = lease.artifact();
            final String etag = "\"" + artifact.sha1() + "\"";
            addArtifactHeaders(exchange, etag);
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                status = 304;
                exchange.sendResponseHeaders(status, -1L);
                responseStarted = true;
                return;
            }
            final ResourcePackHttpServer.HttpByteRange range = ResourcePackHttpServer.parseRange(
                    exchange.getRequestHeaders().getFirst("Range"), artifact.size());
            if (range == null) {
                result = "invalid_range";
                status = 416;
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + artifact.size());
                sendEmpty(exchange, status);
                responseStarted = true;
                return;
            }
            partial = range.partial();
            status = partial ? 206 : 200;
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            if (partial) {
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + range.start() + "-" + range.end() + "/" + artifact.size());
            }
            if ("HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Content-Length", Long.toString(range.length()));
                exchange.sendResponseHeaders(status, -1L);
                responseStarted = true;
                return;
            }
            try (RandomAccessFile file = this.artifactFileOpener.open(artifact.path())) {
                file.seek(range.start());
                exchange.sendResponseHeaders(status, range.length());
                responseStarted = true;
                try (OutputStream output = exchange.getResponseBody()) {
                    transferred = copy(file, output, range.length());
                }
            }
        } catch (IOException e) {
            result = "failure";
            if (responseStarted) throw e;
            status = 500;
            sendEmpty(exchange, status);
        } finally {
            this.metrics.finishDownload(started, result, transferred, partial);
            this.metrics.httpRequest(route, method, status);
            this.logAccess(exchange, route, method, status, transferred);
            exchange.close();
        }
    }

    private void handleInternal(final HttpExchange exchange) throws IOException {
        final String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        final String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && "/health/live".equals(path)) {
            this.respond(exchange, "health_live", method, 200);
            return;
        }
        if ("GET".equals(method) && "/health/ready".equals(path)) {
            this.respond(exchange, "health_ready", method, this.store.isReady() ? 200 : 503);
            return;
        }
        if (!this.authorized(exchange)) {
            this.metrics.authenticationFailure();
            this.respond(exchange, "internal", method, 401);
            return;
        }
        final Matcher lookup = LOOKUP_PATH.matcher(path);
        if (lookup.matches()) {
            if (!"POST".equals(method)) {
                this.respond(exchange, "lookup", method, 405);
                return;
            }
            final LookupResult result = this.store.lookupOrCreate(lookup.group(1));
            if (result.ready()) {
                exchange.getResponseHeaders().set(RemotePackServiceClient.STATUS, "ready");
                exchange.getResponseHeaders().set(RemotePackServiceClient.ARTIFACT_KEY,
                        result.artifact().artifactKey());
                exchange.getResponseHeaders().set(RemotePackServiceClient.SHA1, result.artifact().sha1());
                exchange.getResponseHeaders().set(RemotePackServiceClient.SIZE,
                        Long.toString(result.artifact().size()));
                this.respond(exchange, "lookup", method, 200);
            } else {
                exchange.getResponseHeaders().set(RemotePackServiceClient.STATUS, "pending");
                exchange.getResponseHeaders().set(RemotePackServiceClient.TOKEN, result.token().toString());
                this.respond(exchange, "lookup", method, 202);
            }
            return;
        }

        final Matcher pending = INTERNAL_PENDING_PATH.matcher(path);
        if (pending.matches()) {
            final UUID token = UUID.fromString(pending.group(1));
            if ("PUT".equals(method)) {
                this.handleUpload(exchange, token);
                return;
            }
            if ("DELETE".equals(method)) {
                final String lookupKey = exchange.getRequestHeaders().getFirst(RemotePackServiceClient.LOOKUP_KEY);
                final boolean cancelled;
                try {
                    cancelled = this.store.cancel(token, lookupKey);
                } catch (IllegalArgumentException | UploadValidationException e) {
                    this.respond(exchange, "cancel", method, 422);
                    return;
                }
                this.respond(exchange, "cancel", method, cancelled ? 204 : 404);
                return;
            }
            this.respond(exchange, "pending_internal", method, 405);
            return;
        }
        this.respond(exchange, "internal_not_found", method, 404);
    }

    private void handleUpload(final HttpExchange exchange, final UUID token) throws IOException {
        final long started = this.metrics.beginUpload();
        long bytes = 0L;
        String result = "success";
        int status;
        try {
            final String lookupKey = requiredHeader(exchange, RemotePackServiceClient.LOOKUP_KEY);
            final String artifactKey = requiredHeader(exchange, RemotePackServiceClient.ARTIFACT_KEY);
            final String sha1 = requiredHeader(exchange, RemotePackServiceClient.SHA1);
            final long size = Long.parseLong(requiredHeader(exchange, RemotePackServiceClient.SIZE));
            final String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
            if (contentLength == null || Long.parseLong(contentLength) != size) {
                throw new UploadValidationException("size", "Content-Length does not match declared size");
            }
            this.store.publish(token, lookupKey, artifactKey, sha1, size, exchange.getRequestBody());
            bytes = size;
            status = 201;
        } catch (MissingPendingException e) {
            result = "not_found";
            status = 404;
        } catch (UploadValidationException e) {
            result = "rejected";
            this.metrics.validationFailure(e.reason());
            status = 422;
        } catch (IllegalArgumentException e) {
            result = "rejected";
            this.metrics.validationFailure("metadata");
            status = 422;
        } catch (IOException e) {
            result = "failure";
            status = 500;
        } finally {
            exchange.getRequestBody().close();
        }
        this.metrics.finishUpload(started, result, bytes);
        this.respond(exchange, "upload", "PUT", status);
    }

    private void handleMetrics(final HttpExchange exchange) throws IOException {
        final String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!"GET".equals(method) || !"/metrics".equals(exchange.getRequestURI().getPath())) {
            sendEmpty(exchange, "GET".equals(method) ? 404 : 405);
            exchange.close();
            return;
        }
        final boolean ready = this.store.isReady();
        final byte[] body = this.metrics.render(this.store.snapshot(), ready);
        exchange.getResponseHeaders().set("Content-Type", PackServiceMetrics.CONTENT_TYPE);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(final HttpExchange exchange) {
        final String provided = exchange.getRequestHeaders().getFirst(RemotePackServiceClient.AUTHORIZATION);
        return provided != null && MessageDigest.isEqual(
                this.expectedAuthorization, provided.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(final HttpExchange exchange, final String route, final String method,
                         final int status) throws IOException {
        sendEmpty(exchange, status);
        this.metrics.httpRequest(route, method, status);
        this.logAccess(exchange, route, method, status, 0L);
        exchange.close();
    }

    private void logAccess(final HttpExchange exchange, final String route, final String method,
                           final int status, final long bytes) {
        final Object value = exchange.getAttribute(ACCESS_CONTEXT_ATTRIBUTE);
        if (!(value instanceof AccessContext context) || context.completed) return;
        context.completed = true;
        if ("health_live".equals(route) || "health_ready".equals(route)) return;

        final long durationMillis = TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - context.startedNanos));
        final String client = clientAddress(exchange, context.listener);
        try {
            this.accessLogger.accept("[pack-service-access] listener=" + context.listener
                    + " client=" + client
                    + " method=" + method.toUpperCase(Locale.ROOT)
                    + " route=" + route
                    + " status=" + status
                    + " bytes=" + Math.max(0L, bytes)
                    + " duration_ms=" + durationMillis);
        } catch (RuntimeException error) {
            LOGGER.log(Level.WARNING, "Failed to write resource pack access log", error);
        }
    }

    private static String clientAddress(final HttpExchange exchange, final String listener) {
        if ("public".equals(listener)) {
            final String forwardedFor = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null) {
                final String first = forwardedFor.split(",", 2)[0].trim();
                if (!first.isEmpty() && first.length() <= 64
                        && first.chars().allMatch(character -> Character.digit(character, 16) >= 0
                        || character == ':' || character == '.')) {
                    return first;
                }
            }
        }
        final InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote == null) return "-";
        return remote.getAddress() != null ? remote.getAddress().getHostAddress() : remote.getHostString();
    }

    private static void sendEmpty(final HttpExchange exchange, final int status) throws IOException {
        exchange.getResponseHeaders().set("Content-Length", "0");
        exchange.sendResponseHeaders(status, -1L);
    }

    private static void addArtifactHeaders(final HttpExchange exchange, final String etag) {
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
    }

    private static String requiredHeader(final HttpExchange exchange, final String name)
            throws UploadValidationException {
        final String value = exchange.getRequestHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            throw new UploadValidationException("metadata", "Missing header " + name);
        }
        return value;
    }

    private static long copy(final RandomAccessFile input, final OutputStream output,
                             final long expected) throws IOException {
        final byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long remaining = expected;
        long copied = 0L;
        while (remaining > 0L) {
            final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Artifact ended before declared range length");
            output.write(buffer, 0, read);
            copied += read;
            remaining -= read;
        }
        return copied;
    }

    private static Throwable unwrap(final Throwable error) {
        Throwable current = error;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    interface ArtifactFileOpener {
        RandomAccessFile open(java.nio.file.Path path) throws IOException;
    }

    private static final class AccessContext {
        private final String listener;
        private final long startedNanos;
        private boolean completed;

        private AccessContext(final String listener, final long startedNanos) {
            this.listener = listener;
            this.startedNanos = startedNanos;
        }
    }

    @Override
    public void close() {
        this.publicServer.stop(2);
        this.internalServer.stop(2);
        this.metricsServer.stop(2);
        this.maintenanceExecutor.shutdownNow();
        this.publicExecutor.shutdownNow();
        this.internalExecutor.shutdownNow();
        this.metricsExecutor.shutdownNow();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        private NamedThreadFactory(final String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(final Runnable task) {
            final Thread thread = new Thread(task, this.prefix + " #" + this.counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
