/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock;

import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viaversion.configuration.AbstractViaConfig;
import com.viaversion.viaversion.platform.NoopInjector;
import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

final class APlatformBootstrapTest {

    private static boolean loaded;

    @Test
    void platformLoadsBeforeConnectionBackedTests() throws InterruptedException {
        ensureLoaded();
    }

    private static synchronized void ensureLoaded() throws InterruptedException {
        if (loaded) {
            return;
        }

        try {
            if (!Via.isLoaded()) {
                ViaManagerImpl.initAndLoad(new TestPlatform(), new NoopInjector(),
                        new ViaCommandHandler(false), ViaPlatformLoader.NOOP);
            }
            awaitMappingCompletion();
            loaded = true;
        } catch (final CompletionException e) {
            throw new AssertionError("Async ViaVersion mapping loading failed", e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static void awaitMappingCompletion() throws InterruptedException {
        final var protocolManager = Via.getManager().getProtocolManager();
        if (protocolManager.hasLoadedMappings()) {
            return;
        }
        final long deadline = System.nanoTime() + 60_000_000_000L;
        while (!protocolManager.hasLoadedMappings() && !protocolManager.checkForMappingCompletion(true)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for ViaVersion mapping completion");
            }
            Thread.sleep(100L);
        }
    }

    private static final class TestPlatform extends UserConnectionViaVersionPlatform {

        private TestPlatform() {
            super(null);
        }

        @Override
        public String getPlatformName() {
            return "ViaBedrock Test";
        }

        @Override
        public String getPlatformVersion() {
            return "test";
        }

        @Override
        public Logger createLogger(final String name) {
            return Logger.getGlobal();
        }

        @Override
        protected AbstractViaConfig createConfig() {
            return new AbstractViaConfig(null, null) {
                @Override
                public void reload() {
                }
            };
        }
    }
}
