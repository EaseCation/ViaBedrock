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

import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackWorkSchedulerTest {

    private final List<ResourcePackWorkScheduler> schedulers = new ArrayList<>();

    @AfterEach
    void shutdownSchedulers() {
        this.schedulers.forEach(ResourcePackWorkScheduler::shutdown);
    }

    @Test
    void rejectsBeyondBoundedQueueAndCompletesFuture() throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = this.scheduler(config(1, 1, 1), metrics);
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Void> active = scheduler.runCpu(() -> await(running, release));
        assertTrue(running.await(5, TimeUnit.SECONDS));

        final CompletableFuture<Void> queued = scheduler.runCpu(() -> {
        });
        final CompletableFuture<Void> rejected = scheduler.runCpu(() -> {
        });

        assertFutureFailure(rejected, RejectedExecutionException.class);
        assertFalse(queued.isDone());
        assertEquals(1L, metrics.getExecutorRejections());
        assertEquals(1L, metrics.getCpuExecutorRejections());
        assertEquals(0L, metrics.getIoExecutorRejections());

        release.countDown();
        active.get(5, TimeUnit.SECONDS);
        queued.get(5, TimeUnit.SECONDS);
    }

    @Test
    void shutdownCompletesQueuedFuturesExceptionally() throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = this.scheduler(config(1, 1, 2), metrics);
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        scheduler.runIo(() -> await(running, release));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        final CompletableFuture<Void> queued = scheduler.runIo(() -> {
        });

        scheduler.shutdown();

        assertFutureFailure(queued, RejectedExecutionException.class);
        release.countDown();
    }

    @Test
    void clampsConfiguredCapacity() {
        final ResourcePackCacheMetrics highMetrics = new ResourcePackCacheMetrics();
        this.scheduler(config(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), highMetrics);
        assertEquals(ResourcePackWorkScheduler.MAX_WORKERS, highMetrics.getCpuExecutorWorkerCount());
        assertEquals(ResourcePackWorkScheduler.MAX_WORKERS, highMetrics.getIoExecutorWorkerCount());
        assertEquals(ResourcePackWorkScheduler.MAX_QUEUE_CAPACITY, highMetrics.getCpuExecutorQueueCapacity());
        assertEquals(ResourcePackWorkScheduler.MAX_QUEUE_CAPACITY, highMetrics.getIoExecutorQueueCapacity());

        final ResourcePackCacheMetrics lowMetrics = new ResourcePackCacheMetrics();
        this.scheduler(config(-1, -1, -1), lowMetrics);
        assertTrue(lowMetrics.getCpuExecutorWorkerCount() >= 2);
        assertTrue(lowMetrics.getCpuExecutorWorkerCount() <= 4);
        assertEquals(1, lowMetrics.getIoExecutorWorkerCount());
        assertEquals(1, lowMetrics.getCpuExecutorQueueCapacity());
    }

    @Test
    void usesNamedDaemonThreadsAndTracksPoolSpecificShutdownRejection() throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = this.scheduler(config(1, 1, 4), metrics);

        final ThreadSnapshot cpu = scheduler.submitCpu(ResourcePackWorkSchedulerTest::threadSnapshot)
                .get(5, TimeUnit.SECONDS);
        final ThreadSnapshot io = scheduler.submitIo(ResourcePackWorkSchedulerTest::threadSnapshot)
                .get(5, TimeUnit.SECONDS);
        assertTrue(cpu.daemon());
        assertTrue(cpu.name().startsWith("ViaBedrock Pack CPU #"));
        assertTrue(io.daemon());
        assertTrue(io.name().startsWith("ViaBedrock Pack IO #"));

        scheduler.shutdown();
        assertFutureFailure(scheduler.runIo(() -> {
        }), RejectedExecutionException.class);
        assertEquals(1L, metrics.getExecutorRejections());
        assertEquals(0L, metrics.getCpuExecutorRejections());
        assertEquals(1L, metrics.getIoExecutorRejections());
    }

    @Test
    void fixedRateTimerOnlyEnqueuesMaintenanceOntoBoundedIoPool() throws Exception {
        final ResourcePackWorkScheduler scheduler = this.scheduler(
                config(1, 1, 4), new ResourcePackCacheMetrics());
        final CountDownLatch ran = new CountDownLatch(1);
        final AtomicReference<ThreadSnapshot> thread = new AtomicReference<>();
        final ScheduledFuture<?> scheduled = scheduler.scheduleIoAtFixedRate(() -> {
            thread.set(threadSnapshot());
            ran.countDown();
        }, 0L, 1L, TimeUnit.DAYS);

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertTrue(thread.get().daemon());
        assertTrue(thread.get().name().startsWith("ViaBedrock Pack IO #"));

        scheduler.shutdown();
        assertTrue(scheduled.isCancelled() || scheduled.isDone());
    }

    private ResourcePackWorkScheduler scheduler(final ViaBedrockConfig config,
                                                final ResourcePackCacheMetrics metrics) {
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        this.schedulers.add(scheduler);
        return scheduler;
    }

    private static ViaBedrockConfig config(final int cpuWorkers, final int ioWorkers, final int queueCapacity) {
        return (ViaBedrockConfig) Proxy.newProxyInstance(
                ViaBedrockConfig.class.getClassLoader(), new Class<?>[]{ViaBedrockConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getResourcePackCacheCpuWorkers" -> cpuWorkers;
                    case "getResourcePackCacheIoWorkers" -> ioWorkers;
                    case "getResourcePackCacheQueueCapacity" -> queueCapacity;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }

    private static void await(final CountDownLatch running, final CountDownLatch release) {
        running.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting in test task", e);
        }
    }

    private static ThreadSnapshot threadSnapshot() {
        final Thread thread = Thread.currentThread();
        return new ThreadSnapshot(thread.getName(), thread.isDaemon());
    }

    private static <T extends Throwable> T assertFutureFailure(
            final CompletableFuture<?> future, final Class<T> expectedType) throws Exception {
        final ExecutionException thrown = assertThrows(
                ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        return assertInstanceOf(expectedType, thrown.getCause());
    }

    private record ThreadSnapshot(String name, boolean daemon) {
    }

}
