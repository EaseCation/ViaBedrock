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
package net.raphimc.viabedrock.platform;

import com.viaversion.viaversion.api.configuration.Config;
import net.raphimc.viabedrock.protocol.provider.BlobCacheProvider;
import net.raphimc.viabedrock.protocol.provider.ResourcePackProvider;
import net.raphimc.viabedrock.protocol.provider.impl.*;

import java.util.function.Supplier;

public interface ViaBedrockConfig extends Config {

    /**
     * @return If true, enables experimental features. These features are almost certainly not fully stable/tested and may cause unexpected issues
     */
    boolean shouldEnableExperimentalFeatures();

    /**
     * @return If true, Java swords receive a client-only blocking use animation. Requires experimental features.
     */
    boolean shouldEnableSwordBlockingAnimation();

    /**
     * @return The blob cache mode to use.
     */
    BlobCacheMode getBlobCacheMode();

    /**
     * @return If true, starts the resource pack HTTP server and enables resource pack translation
     */
    boolean shouldTranslateResourcePacks();

    /**
     * @return The host to use for the resource pack HTTP server.
     */
    String getResourcePackHost();

    /**
     * @return The port to use for the resource pack HTTP server.
     */
    int getResourcePackPort();

    /**
     * @return The URL to use for the resource pack HTTP server.
     */
    String getResourcePackUrl();

    /**
     * @return The pack cache mode to use.
     */
    PackCacheMode getPackCacheMode();

    default boolean isSharedResourcePackCacheEnabled() {
        return true;
    }

    /**
     * Compatibility setting reserved for a future fully scoped observation cache. It never enables
     * the legacy UUID/version provider fast path while the shared resource-pack cache is active.
     */
    default boolean shouldTrustDeclaredPackAlias() {
        return false;
    }

    default int getResourcePackCacheMemoryBudgetMiB() {
        return 0;
    }

    default int getResourcePackCacheMemoryHardLimitMiB() {
        return 0;
    }

    default int getResourcePackCacheCpuWorkers() {
        return 0;
    }

    default int getResourcePackCacheIoWorkers() {
        return 4;
    }

    default int getResourcePackCacheQueueCapacity() {
        return 64;
    }

    default int getResourcePackCacheIdleExpireMinutes() {
        return 30;
    }

    default int getResourcePackCacheBuildTimeoutSeconds() {
        return 120;
    }

    default int getResourcePackCacheDiskBudgetMiB() {
        return 20_480;
    }

    default int getResourcePackCacheDiskIdleDays() {
        return 7;
    }

    default int getResourcePackMaxArchiveMiB() {
        return 2_048;
    }

    default int getResourcePackMaxExpandedMiB() {
        return 4_096;
    }

    default int getResourcePackMaxEntryMiB() {
        return 512;
    }

    default int getResourcePackMaxEntries() {
        return 100_000;
    }

    default int getResourcePackMaxCompressionRatio() {
        return 200;
    }

    /**
     * @return If true, translates bedrock's showCoordinates game rule to java's reduced debug info flag
     */
    boolean shouldTranslateShowCoordinatesGameRule();

    /**
     * @return If true, disables the internal server blacklist. This will allow you to connect to any server, even if it's known to ban ViaBedrock clients
     */
    boolean shouldDisableServerBlacklist();

    /**
     * @return The language code to send to the bedrock server (e.g. "en_us", "zh_cn"). Empty = "en_us"
     */
    String getLanguage();

    /**
     * @return The shared secret for ViaProxy authentication bridge. When set, generates HMAC-signed identity tokens in the skin JWT. Empty = disabled
     */
    String getViaProxyAuthSecret();

    /**
     * @return If true, enables server-side entity animation using Display Entities when ViaBedrockUtility mod is not present.
     * When disabled, custom entities will only show as invisible interaction boxes (upstream behavior).
     * This setting has no effect when ViaBedrockUtility mod is installed (client-side rendering is always used).
     */
    boolean shouldEnableServerEntityAnimation();

    /**
     * @return The timeout in milliseconds for fetching Java Edition skin from Mojang API during login.
     * Set to 0 to disable Java skin fetching (always use Steve).
     */
    int getJavaSkinFetchTimeout();

    /**
     * @return If true, sends a custom Java tab list header and footer when joining the server.
     */
    boolean shouldSendTabList();

    /**
     * @return The Java tab list header template.
     */
    String getTabListHeader();

    /**
     * @return The Java tab list footer template.
     */
    String getTabListFooter();

    /**
     * @return If true, applies Floyd-Steinberg dithering when converting Bedrock map pixels (true ARGB) to the
     * limited Java map color palette. This trades hard color banding for fine noise, which greatly improves
     * smooth gradients such as faces/portraits. Requires experimental features.
     */
    boolean shouldDitherMaps();

    boolean isCustomMappingSyncEnabled();

    int getCustomMappingSyncTimeoutMs();

    int getCustomMappingSyncMaxSnapshotBytes();

    int getCustomMappingSyncMaxPayloadBytes();

    int getCustomMappingSyncMaxCustomBlockStates();

    int getCustomMappingSyncMaxCustomBlockEntityTypes();

    int getCustomMappingSyncMaxJavaBlockStateId();

    CustomMappingSyncFailureMode getCustomMappingSyncFailureMode();

    String getCustomMappingSyncDefaultFallbackBlock();

    boolean isCustomMappingSyncChunksEnabled();

    boolean shouldSendCustomMappingSyncResult();

    /**
     * @return The movement watchdog mode. A fail-safe net for the rare case where the backend never sends the
     * packet that would unlock movement after a world/dimension/server switch (see ViaBedrock movement docs).
     * OFF = disabled (behaviour identical to upstream); OBSERVE = only log when a lock stays stuck past the
     * timeout, without changing behaviour (default, most conservative); ACTIVE = additionally perform the
     * conservative, idempotent recovery (re-send the standard handshake finalization the backend omitted).
     */
    MovementWatchdogMode getMovementWatchdogMode();

    /**
     * @return Ticks (20/s) a dimension change may stay un-finalized before the watchdog acts. Default 200 (10s),
     * far above any normal handshake so normal players never trigger it.
     */
    int getMovementWatchdogDimensionChangeTimeoutTicks();

    /**
     * @return Ticks the player may stay stuck in an unloaded chunk section before the watchdog re-requests chunks.
     */
    int getMovementWatchdogChunkStuckTimeoutTicks();

    /**
     * @return Throttle (in ticks) between repeated chunk-radius re-requests while stuck in an unloaded chunk.
     */
    int getMovementWatchdogChunkRadiusRequestIntervalTicks();

    enum MovementWatchdogMode {
        OFF,
        OBSERVE,
        ACTIVE;

        public static MovementWatchdogMode byName(final String name) {
            for (MovementWatchdogMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return OBSERVE;
        }
    }

    enum CustomMappingSyncFailureMode {
        SAFE_FALLBACK,
        KICK;

        public static CustomMappingSyncFailureMode byName(final String name) {
            for (CustomMappingSyncFailureMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return SAFE_FALLBACK;
        }
    }

    enum BlobCacheMode {

        /**
         * The blob cache will be disabled.
         */
        DISABLED(NoOpBlobCacheProvider::new),
        /**
         * The blob cache will be enabled and blobs will be stored in memory.
         */
        MEMORY(InMemoryBlobCacheProvider::new),
        /**
         * The blob cache will be enabled and blobs will be stored on disk.
         */
        DISK(DiskBlobCacheProvider::new);

        private final Supplier<BlobCacheProvider> providerSupplier;

        BlobCacheMode(final Supplier<BlobCacheProvider> providerSupplier) {
            this.providerSupplier = providerSupplier;
        }

        public static BlobCacheMode byName(String name) {
            for (BlobCacheMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }

            return DISABLED;
        }

        public BlobCacheProvider createProvider() {
            return this.providerSupplier.get();
        }

    }

    enum PackCacheMode {

        /**
         * The pack cache will be disabled.
         */
        DISABLED(NoOpResourcePackProvider::new),
        /**
         * The pack cache will be enabled and packs will be stored in memory.
         */
        MEMORY(InMemoryResourcePackProvider::new),
        /**
         * The pack cache will be enabled and packs will be stored on disk.
         */
        DISK(DiskResourcePackProvider::new);

        private final Supplier<ResourcePackProvider> providerSupplier;

        PackCacheMode(final Supplier<ResourcePackProvider> providerSupplier) {
            this.providerSupplier = providerSupplier;
        }

        public static PackCacheMode byName(String name) {
            for (PackCacheMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }

            return DISABLED;
        }

        public ResourcePackProvider createProvider() {
            return this.providerSupplier.get();
        }

    }

}
