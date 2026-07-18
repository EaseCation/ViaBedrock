/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import net.raphimc.viabedrock.ViaBedrockConfig;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.FrozenContent;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.api.resourcepack.content.ZipFileContent;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions;
import net.raphimc.viabedrock.api.resourcepack.definition.ParsedPackLayer;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackArchiveStore;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntime;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntimeCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackSessionDataTest {

    @Test
    void identicalStackAtOneTenAndTwentySessionsKeepsSharedGraphsConstant(
            @TempDir final Path tempDir) throws Exception {
        final Fixture fixture = fixture(tempDir);
        final UUID packId = UUID.randomUUID();
        final ResourcePack.Key packKey = new ResourcePack.Key(packId, "1.0.0");
        final List<Session> sessions = new ArrayList<>();
        try {
            growSessions(fixture.cache(), packId, sessions, 1);
            final Session first = sessions.getFirst();
            final SharedPackRuntime sharedRuntime = first.runtime();
            final SharedPackRuntime.PackSource sharedSource = findPackSource(sharedRuntime, packKey);
            final ParsedPackLayer sharedLayer = findLayer(sharedRuntime, packKey);
            final Object sharedMotionDefinitions = sharedRuntime.bedrockMotionPackManager()
                    .getAnimationDefinitions().getAnimations();
            final Object sharedMotionDefinition = sharedRuntime.bedrockMotionPackManager()
                    .getAnimationDefinitions().getAnimations().get("animation.test.shared");
            final ItemDefinitions.ItemDefinition sharedBaseItem = sharedRuntime.items().get("test:shared");
            final BuildCounts buildCounts = BuildCounts.capture(fixture.metrics());
            final long sharedRuntimeWeight = fixture.metrics().getActiveRuntimeWeightBytes();

            assertEquals(1L, buildCounts.blobs());
            assertTrue(buildCounts.layers() >= 1L);
            assertEquals(1L, buildCounts.runtimes());
            assertTrue(sharedRuntimeWeight > 0L);
            assertEquals(SharedPackRuntime.SourceKind.CANONICAL, sharedSource.sourceKind());
            final ResourcePack compatibilityView = sharedRuntime.packStackTopToBottom().getFirst();
            assertSame(compatibilityView, sharedRuntime.packStackTopToBottom().getFirst());
            assertSame(compatibilityView, sharedRuntime.packStackBottomToTop().getLast());
            assertTrue(sharedRuntime.packStackTopToBottom().contains(compatibilityView));
            assertEquals(0, sharedRuntime.packStackTopToBottom().indexOf(compatibilityView));
            assertFalse(compatibilityView.content() instanceof FrozenContent);
            assertFalse(compatibilityView.content() instanceof ZipFileContent);
            assertNotNull(sharedMotionDefinition);
            assertNotNull(sharedBaseItem);
            assertCohort(sessions, 1, fixture.metrics(), buildCounts, sharedRuntime,
                    sharedRuntimeWeight, sharedSource, sharedLayer,
                    sharedMotionDefinitions, sharedMotionDefinition, sharedBaseItem);

            growSessions(fixture.cache(), packId, sessions, 10);
            assertCohort(sessions, 10, fixture.metrics(), buildCounts, sharedRuntime,
                    sharedRuntimeWeight, sharedSource, sharedLayer,
                    sharedMotionDefinitions, sharedMotionDefinition, sharedBaseItem);

            growSessions(fixture.cache(), packId, sessions, 20);
            assertCohort(sessions, 20, fixture.metrics(), buildCounts, sharedRuntime,
                    sharedRuntimeWeight, sharedSource, sharedLayer,
                    sharedMotionDefinitions, sharedMotionDefinition, sharedBaseItem);

            final Set<ItemDefinitions> overlays = Collections.newSetFromMap(new IdentityHashMap<>());
            final Set<Map<String, Object>> sessionData = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0; i < sessions.size(); i++) {
                final ResourcePackStorage storage = sessions.get(i).storage();
                assertTrue(overlays.add(storage.getItems()));
                assertTrue(sessionData.add(storage.getConverterData()));
                storage.getItems().addFromNetworkTag("test:network_" + i, new CompoundTag());
                storage.getConverterData().put("session-index", i);
            }
            sessions.getFirst().storage().setLoadedOnJavaClient();
            sessions.getFirst().storage().setSupportsFreeRotation(true);

            for (int i = 0; i < sessions.size(); i++) {
                final ResourcePackStorage storage = sessions.get(i).storage();
                assertEquals(i, storage.getConverterData().get("session-index"));
                assertTrue(storage.getItems().get("test:network_" + i).networkDefinition());
                for (int other = 0; other < sessions.size(); other++) {
                    if (other != i) {
                        assertNull(storage.getItems().get("test:network_" + other));
                    }
                }
                assertEquals(i == 0, storage.isLoadedOnJavaClient());
                assertEquals(i == 0, storage.isSupportsFreeRotation());
            }
            assertNull(sharedRuntime.converterData().get("session-index"));
            assertCohort(sessions, 20, fixture.metrics(), buildCounts, sharedRuntime,
                    sharedRuntimeWeight, sharedSource, sharedLayer,
                    sharedMotionDefinitions, sharedMotionDefinition, sharedBaseItem);
        } finally {
            sessions.forEach(session -> session.storage().onRemove());
            assertEquals(0L, fixture.metrics().getActiveRuntimeLeases());
            fixture.scheduler().shutdown();
        }
    }

    @Test
    void compatibilityPackStacksShareViewsAcrossReversedMountOrder(
            @TempDir final Path tempDir) throws Exception {
        final Fixture fixture = fixture(tempDir);
        try (SharedPackRuntimeCache.RuntimeLease lease = fixture.cache().acquire(List.of(
                pack(UUID.randomUUID(), false), pack(UUID.randomUUID(), false)))) {
            final List<ResourcePack> bottomToTop = lease.runtime().packStackBottomToTop();
            final List<ResourcePack> topToBottom = lease.runtime().packStackTopToBottom();
            assertEquals(bottomToTop.size(), topToBottom.size());
            assertTrue(bottomToTop.size() >= 2);

            for (int i = 0; i < bottomToTop.size(); i++) {
                final int reversedIndex = bottomToTop.size() - 1 - i;
                final ResourcePack view = bottomToTop.get(i);
                assertSame(view, bottomToTop.get(i));
                assertSame(view, topToBottom.get(reversedIndex));
                assertTrue(bottomToTop.contains(view));
                assertTrue(topToBottom.contains(view));
                assertEquals(i, bottomToTop.indexOf(view));
                assertEquals(reversedIndex, topToBottom.indexOf(view));
            }
        } finally {
            fixture.scheduler().shutdown();
        }
    }

    @Test
    void compatibilityDataIsSessionLocalWhileRuntimeDataIsShared(@TempDir final Path tempDir) throws Exception {
        final Path configPath = tempDir.resolve("viabedrock.yml");
        Files.writeString(configPath, "resource-pack-cache:\n  memory-budget-mib: 64\n  memory-hard-limit-mib: 128\n");
        final ViaBedrockConfig config = new ViaBedrockConfig(configPath.toFile(), Logger.getAnonymousLogger());
        config.reload();
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack pack = pack();
            final ResourcePackStorage first = new ResourcePackStorage(cache.acquire(List.of(pack)));
            final ResourcePackStorage second = new ResourcePackStorage(cache.acquire(List.of(pack)));
            try {
                assertNotSame(first.getConverterData(), second.getConverterData());
                first.getConverterData().put("session", "first");
                assertThrows(IllegalStateException.class,
                        () -> first.putRuntimeData("outside-initializer", "rejected"));
                try (SharedPackRuntimeCache.RuntimeLease retained = first.retainRuntimeLease()) {
                    retained.initializeRuntimeData(() -> first.putRuntimeData("shared", "runtime"));
                }
                assertSame(first.getRuntimeData(), second.getRuntimeData());
                assertNull(second.getConverterData().get("session"));
                assertEquals("runtime", second.getRuntimeData().get("shared"));
                assertThrows(UnsupportedOperationException.class,
                        () -> first.getRuntimeData().put("late", "mutation"));
            } finally {
                first.onRemove();
                second.onRemove();
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void retainedOperationSurvivesDisconnectAndDetachedTimeout(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = fixture(tempDir);
        try {
            final ResourcePackStorage storage = new ResourcePackStorage(
                    fixture.cache().acquire(List.of(pack())));
            final CompletableFuture<String> source = new CompletableFuture<>();
            final CompletableFuture<String> operation = storage.retainRuntimeDuring(() -> source);
            assertEquals(2L, fixture.metrics().getActiveRuntimeLeases());

            storage.onRemove();
            assertEquals(1L, fixture.metrics().getActiveRuntimeLeases());
            final ResourcePack.Key materializedAfterDisconnect = storage.withPackStack(() ->
                    storage.getPackStackTopToBottom().iterator().next().key());
            assertNotNull(materializedAfterDisconnect,
                    "The retained artifact build must not depend on the closed session lease");

            final CompletableFuture<String> request = operation.thenApply(value -> value)
                    .orTimeout(25L, TimeUnit.MILLISECONDS);
            final ExecutionException timeout = assertThrows(
                    ExecutionException.class, () -> request.get(2L, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, timeout.getCause());
            assertFalse(source.isDone());
            assertEquals(1L, fixture.metrics().getActiveRuntimeLeases());

            source.complete("ready");
            assertEquals("ready", operation.get(2L, TimeUnit.SECONDS));
            assertEquals(0L, fixture.metrics().getActiveRuntimeLeases());
        } finally {
            fixture.scheduler().shutdown();
        }
    }

    @Test
    void cancelledSingleFlightWaiterDoesNotLeakRetainedRuntime(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = fixture(tempDir);
        try {
            final ResourcePackStorage storage = new ResourcePackStorage(
                    fixture.cache().acquire(List.of(pack())));
            final CompletableFuture<String> sharedSource = new CompletableFuture<>();
            final CompletableFuture<String> first = storage.retainRuntimeDuring(() -> sharedSource);
            final CompletableFuture<String> second = storage.retainRuntimeDuring(() -> sharedSource);
            assertEquals(3L, fixture.metrics().getActiveRuntimeLeases());

            first.cancel(false);
            assertFalse(sharedSource.isCancelled());
            assertEquals(3L, fixture.metrics().getActiveRuntimeLeases());

            sharedSource.complete("built-once");
            assertEquals("built-once", second.get(2L, TimeUnit.SECONDS));
            assertEquals(1L, fixture.metrics().getActiveRuntimeLeases());

            storage.onRemove();
            assertEquals(0L, fixture.metrics().getActiveRuntimeLeases());
        } finally {
            fixture.scheduler().shutdown();
        }
    }

    private static Fixture fixture(final Path tempDir) throws Exception {
        final Path configPath = tempDir.resolve("viabedrock-retain.yml");
        Files.writeString(configPath,
                "enable-server-entity-animation: true\nresource-pack-cache:\n"
                        + "  memory-budget-mib: 64\n  memory-hard-limit-mib: 128\n");
        final ViaBedrockConfig config = new ViaBedrockConfig(configPath.toFile(), Logger.getAnonymousLogger());
        config.reload();
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final ResourcePackArchiveStore archiveStore = new ResourcePackArchiveStore(
                tempDir.resolve("server-packs"), scheduler, metrics, config);
        return new Fixture(new SharedPackRuntimeCache(config, metrics, scheduler, archiveStore),
                metrics, scheduler);
    }

    private static ResourcePack pack() {
        return pack(UUID.randomUUID(), false);
    }

    private static ResourcePack pack(final UUID id, final boolean includeSharedDefinitions) {
        final JsonObject header = new JsonObject();
        header.addProperty("uuid", id.toString());
        final JsonArray version = new JsonArray();
        version.add(1);
        version.add(0);
        version.add(0);
        header.add("version", version);
        header.addProperty("name", "test");
        final JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);
        manifest.add("header", header);
        final InMemoryContent content = new InMemoryContent();
        content.putJson("manifest.json", manifest);
        if (includeSharedDefinitions) {
            content.putString("items/shared.json", """
                    {"minecraft:item":{"description":{"identifier":"test:shared"},
                    "components":{"minecraft:icon":"shared"}}}
                    """);
            content.putString("animations/shared.json", """
                    {"format_version":"1.8.0","animations":{"animation.test.shared":{"loop":true}}}
                    """);
        }
        return new ResourcePack(content);
    }

    private static void growSessions(final SharedPackRuntimeCache cache, final UUID packId,
                                     final List<Session> sessions, final int targetCount) {
        while (sessions.size() < targetCount) {
            final SharedPackRuntimeCache.RuntimeLease lease = cache.acquire(
                    List.of(pack(packId, true)));
            sessions.add(new Session(lease.runtime(), new ResourcePackStorage(lease)));
        }
    }
    private static void assertCohort(final List<Session> sessions, final int expectedSessions,
                                     final ResourcePackCacheMetrics metrics, final BuildCounts buildCounts,
                                     final SharedPackRuntime sharedRuntime, final long sharedRuntimeWeight,
                                     final SharedPackRuntime.PackSource sharedSource,
                                     final ParsedPackLayer sharedLayer,
                                     final Object sharedMotionDefinitions,
                                     final Object sharedMotionDefinition,
                                     final ItemDefinitions.ItemDefinition sharedBaseItem) {
        assertEquals(expectedSessions, sessions.size());
        assertEquals(expectedSessions, metrics.getActiveRuntimeLeases());
        assertEquals(buildCounts, BuildCounts.capture(metrics));
        assertEquals(sharedRuntimeWeight, metrics.getActiveRuntimeWeightBytes());
        final ResourcePackStorage firstStorage = sessions.getFirst().storage();
        for (Session session : sessions) {
            final ResourcePackStorage storage = session.storage();
            assertSame(sharedRuntime, session.runtime());
            assertSame(sharedSource, findPackSource(session.runtime(),
                    sharedSource.mount().alias().toResourcePackKey()));
            assertSame(sharedLayer, findLayer(session.runtime(),
                    sharedSource.mount().alias().toResourcePackKey()));
            assertSame(sharedMotionDefinitions, storage.getBedrockMotionPackManager()
                    .getAnimationDefinitions().getAnimations());
            assertSame(sharedMotionDefinition, storage.getBedrockMotionPackManager()
                    .getAnimationDefinitions().getAnimations().get("animation.test.shared"));
            assertSame(sharedBaseItem, storage.getItems().get("test:shared"));
            assertSame(sharedRuntime.converterData(), storage.getRuntimeData());
            assertSame(sharedRuntime.packStackBottomToTop(), storage.getPackStackBottomToTop());
            assertSame(sharedRuntime.packStackTopToBottom(), storage.getPackStackTopToBottom());
            assertSame(firstStorage.getTexts(), storage.getTexts());
            assertSame(firstStorage.getBlocks(), storage.getBlocks());
            assertSame(firstStorage.getAttachables(), storage.getAttachables());
            assertSame(firstStorage.getTextures(), storage.getTextures());
            assertSame(firstStorage.getSounds(), storage.getSounds());
            assertSame(firstStorage.getParticles(), storage.getParticles());
            assertSame(firstStorage.getEntities(), storage.getEntities());
            assertSame(firstStorage.getModels(), storage.getModels());
            assertSame(firstStorage.getFogs(), storage.getFogs());
            assertSame(firstStorage.getBiomes(), storage.getBiomes());
            assertSame(firstStorage.getRenderControllers(), storage.getRenderControllers());
        }
    }

    private static SharedPackRuntime.PackSource findPackSource(
            final SharedPackRuntime runtime, final ResourcePack.Key key) {
        return runtime.packSourcesBottomToTop().stream()
                .filter(source -> source.mount().alias().toResourcePackKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static ParsedPackLayer findLayer(final SharedPackRuntime runtime, final ResourcePack.Key key) {
        return runtime.parsedLayersBottomToTop().stream()
                .filter(layer -> layer.sourceKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private record Fixture(SharedPackRuntimeCache cache, ResourcePackCacheMetrics metrics,
                           ResourcePackWorkScheduler scheduler) {
    }

    private record Session(SharedPackRuntime runtime, ResourcePackStorage storage) {
    }

    private record BuildCounts(long blobs, long layers, long runtimes, long motions) {

        private static BuildCounts capture(final ResourcePackCacheMetrics metrics) {
            return new BuildCounts(
                    metrics.getBlobBuilds(), metrics.getLayerBuilds(), metrics.getRuntimeBuilds(),
                    metrics.getMotionBuilds());
        }
    }
}
