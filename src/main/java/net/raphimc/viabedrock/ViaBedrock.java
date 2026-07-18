/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock;

import net.raphimc.viabedrock.api.resourcepack.http.ResourcePackHttpServer;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntimeCache;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackArchiveStore;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.platform.ViaBedrockPlatform;

import java.net.InetSocketAddress;
import java.util.logging.Level;

public class ViaBedrock {

    public static final String VERSION = "${version}";
    public static final String IMPL_VERSION = "git-ViaBedrock-${version}:${commit_hash}";

    private static ViaBedrockPlatform platform;
    private static ViaBedrockConfig config;
    private static ResourcePackHttpServer resourcePackServer;
    private static JavaPackCache javaPackCache;
    private static ResourcePackCacheMetrics resourcePackCacheMetrics;
    private static ResourcePackWorkScheduler resourcePackWorkScheduler;
    private static SharedPackRuntimeCache sharedPackRuntimeCache;
    private static ResourcePackArchiveStore resourcePackArchiveStore;
    private static final ResourcePackCacheModeGate RESOURCE_PACK_CACHE_MODE = new ResourcePackCacheModeGate();
    private static Thread shutdownHook;

    private ViaBedrock() {
    }

    public static void init(final ViaBedrockPlatform platform, final ViaBedrockConfig config) {
        if (ViaBedrock.platform != null) throw new IllegalStateException("ViaBedrock is already initialized");

        ViaBedrock.platform = platform;
        ViaBedrock.config = config;
        final boolean translateResourcePacks = config.shouldTranslateResourcePacks();
        final boolean sharedCacheEnabled = ViaBedrock.RESOURCE_PACK_CACHE_MODE.initialize(
                translateResourcePacks, config.isSharedResourcePackCacheEnabled());

        if (translateResourcePacks) {
            try {
                ViaBedrock.resourcePackCacheMetrics = new ResourcePackCacheMetrics();
                ViaBedrock.resourcePackCacheMetrics.register();
                ViaBedrock.resourcePackWorkScheduler = new ResourcePackWorkScheduler(config, ViaBedrock.resourcePackCacheMetrics);
                final int totalDiskBudgetMiB = config.getResourcePackCacheDiskBudgetMiB();
                final int casDiskBudgetMiB = sharedCacheEnabled
                        ? totalDiskBudgetMiB / 2 + totalDiskBudgetMiB % 2 : 0;
                final int artifactDiskBudgetMiB = totalDiskBudgetMiB - casDiskBudgetMiB;
                if (sharedCacheEnabled) {
                    ViaBedrock.resourcePackArchiveStore = new ResourcePackArchiveStore(
                            platform.getServerPacksFolder().toPath(), ViaBedrock.resourcePackWorkScheduler,
                            ViaBedrock.resourcePackCacheMetrics, config, casDiskBudgetMiB);
                    ViaBedrock.sharedPackRuntimeCache = new SharedPackRuntimeCache(
                            config, ViaBedrock.resourcePackCacheMetrics, ViaBedrock.resourcePackWorkScheduler,
                            ViaBedrock.resourcePackArchiveStore);
                }
                ViaBedrock.javaPackCache = new JavaPackCache(
                        platform.getJavaPacksCacheFolder(), ViaBedrock.resourcePackWorkScheduler,
                        ViaBedrock.resourcePackCacheMetrics, artifactDiskBudgetMiB,
                        config.getResourcePackCacheDiskIdleDays());
                ViaBedrock.resourcePackServer = new ResourcePackHttpServer(new InetSocketAddress(config.getResourcePackHost(), config.getResourcePackPort()));
                platform.getLogger().log(Level.INFO, "Started resource pack HTTP server on " + resourcePackServer.getUrl());
                ViaBedrock.registerShutdownHook();
            } catch (Throwable e) {
                ViaBedrock.shutdownResourcePackServices();
                throw new IllegalStateException("Failed to initialize resource pack services", e);
            }
        }
        if (config.shouldEnableExperimentalFeatures()) {
            platform.getLogger().log(Level.WARNING, "Experimental features are enabled. These features might not be fully stable/tested and may cause issues.");
        }
    }

    public static ViaBedrockPlatform getPlatform() {
        return ViaBedrock.platform;
    }

    public static ViaBedrockConfig getConfig() {
        return ViaBedrock.config;
    }

    public static ResourcePackHttpServer getResourcePackServer() {
        return ViaBedrock.resourcePackServer;
    }

    public static JavaPackCache getJavaPackCache() {
        return ViaBedrock.javaPackCache;
    }

    public static ResourcePackCacheMetrics getResourcePackCacheMetrics() {
        return ViaBedrock.resourcePackCacheMetrics;
    }

    public static ResourcePackWorkScheduler getResourcePackWorkScheduler() {
        return ViaBedrock.resourcePackWorkScheduler;
    }

    public static SharedPackRuntimeCache getSharedPackRuntimeCache() {
        return ViaBedrock.RESOURCE_PACK_CACHE_MODE.activeComponent(ViaBedrock.sharedPackRuntimeCache,
                "Shared resource pack cache is active but unavailable");
    }

    public static ResourcePackArchiveStore getResourcePackArchiveStore() {
        return ViaBedrock.RESOURCE_PACK_CACHE_MODE.activeComponent(ViaBedrock.resourcePackArchiveStore,
                "Shared resource pack archive store is active but unavailable");
    }

    public static boolean isSharedResourcePackCacheEnabled() {
        return ViaBedrock.RESOURCE_PACK_CACHE_MODE.isSharedCacheEnabled();
    }

    public static synchronized void shutdown() {
        shutdownResourcePackServices();
        final Thread hook = ViaBedrock.shutdownHook;
        ViaBedrock.shutdownHook = null;
        if (hook != null && hook != Thread.currentThread()) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException | SecurityException ignored) {
            }
        }
    }

    private static void registerShutdownHook() {
        if (ViaBedrock.shutdownHook != null) return;
        final Thread hook = new Thread(ViaBedrock::shutdown, "ViaBedrock Shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            ViaBedrock.shutdownHook = hook;
        } catch (IllegalStateException | SecurityException e) {
            ViaBedrock.platform.getLogger().log(Level.WARNING,
                    "Could not register ViaBedrock resource-pack shutdown hook", e);
        }
    }

    private static void shutdownResourcePackServices() {
        if (ViaBedrock.resourcePackServer != null) {
            try {
                ViaBedrock.resourcePackServer.stop();
            } catch (Throwable ignored) {
            }
            ViaBedrock.resourcePackServer = null;
        }
        if (ViaBedrock.resourcePackWorkScheduler != null) {
            try {
                ViaBedrock.resourcePackWorkScheduler.shutdown();
            } catch (Throwable ignored) {
            }
            ViaBedrock.resourcePackWorkScheduler = null;
        }
        if (ViaBedrock.resourcePackCacheMetrics != null) {
            ViaBedrock.resourcePackCacheMetrics.unregister();
            ViaBedrock.resourcePackCacheMetrics = null;
        }
        ViaBedrock.RESOURCE_PACK_CACHE_MODE.reset();
        ViaBedrock.resourcePackArchiveStore = null;
        ViaBedrock.sharedPackRuntimeCache = null;
        ViaBedrock.javaPackCache = null;
    }

}
