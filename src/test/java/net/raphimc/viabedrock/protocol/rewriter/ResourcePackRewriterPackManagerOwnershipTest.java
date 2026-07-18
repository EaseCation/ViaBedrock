/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter;

import net.easecation.bedrockmotion.pack.PackManager;
import net.raphimc.viabedrock.api.resourcepack.cache.RuntimeStackKey;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourcePackRewriterPackManagerOwnershipTest {

    @Test
    void sharedTypedManagerIsNotAliasedThroughMutableRuntimeData() {
        final PackManager manager = new PackManager(List.of(), PackManager.Profile.SERVER_ANIMATION);
        final TypedManagerStorage storage = new TypedManagerStorage(manager);

        ResourcePackRewriter.initBedrockMotionPackManager(storage);

        assertSame(manager, storage.getBedrockMotionPackManager());
        assertFalse(storage.getRuntimeData().containsKey(
                ResourcePackRewriter.BEDROCK_MOTION_PACK_MANAGER_KEY));
    }

    @Test
    void unsharedCompatibilityStorageStillReadsLegacyManagerKey() {
        final PackManager manager = new PackManager(List.of(), PackManager.Profile.SERVER_ANIMATION);
        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(List.of());

        storage.getConverterData().put(ResourcePackRewriter.BEDROCK_MOTION_PACK_MANAGER_KEY, manager);

        assertSame(manager, storage.getBedrockMotionPackManager());
    }

    @Test
    void sharedStorageWithoutTypedManagerDoesNotFallBackToMutableData() {
        final TypedManagerStorage storage = new TypedManagerStorage(null, true);

        assertThrows(IllegalStateException.class,
                () -> ResourcePackRewriter.initBedrockMotionPackManager(storage));
    }

    private static final class TypedManagerStorage extends ResourcePackStorage {
        private final PackManager manager;
        private final Map<String, Object> runtimeData = new HashMap<>();
        private final boolean shared;

        private TypedManagerStorage(final PackManager manager) {
            this(manager, false);
        }

        private TypedManagerStorage(final PackManager manager, final boolean shared) {
            super(List.of());
            this.manager = manager;
            this.shared = shared;
        }

        @Override
        public PackManager getBedrockMotionPackManager() {
            return this.manager;
        }

        @Override
        public Map<String, Object> getRuntimeData() {
            return this.runtimeData;
        }

        @Override
        public RuntimeStackKey getRuntimeStackKey() {
            return this.shared ? RuntimeStackKey.compute(List.of()) : null;
        }
    }
}
