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

import com.viaversion.viaversion.util.Config;
import com.viaversion.viaversion.util.ConfigSection;

import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class ViaBedrockConfig extends Config implements net.raphimc.viabedrock.platform.ViaBedrockConfig {

    private boolean enableExperimentalFeatures;
    private BlobCacheMode blobCacheMode;
    private boolean translateResourcePacks;
    private String resourcePackHost;
    private int resourcePackPort;
    private String resourcePackUrl;
    private PackCacheMode packCacheMode;
    private boolean translateShowCoordinatesGameRule;
    private boolean disableServerBlacklist;
    private String language;
    private String viaProxyAuthSecret;
    private boolean enableServerEntityAnimation;
    private int javaSkinFetchTimeout;
    private boolean ditherMaps;
    private boolean customMappingSyncEnabled;
    private int customMappingSyncTimeoutMs;
    private int customMappingSyncMaxSnapshotBytes;
    private int customMappingSyncMaxPayloadBytes;
    private int customMappingSyncMaxCustomBlockStates;
    private int customMappingSyncMaxCustomBlockEntityTypes;
    private int customMappingSyncMaxJavaBlockStateId;
    private CustomMappingSyncFailureMode customMappingSyncFailureMode;
    private String customMappingSyncDefaultFallbackBlock;
    private boolean customMappingSyncEnableChunks;
    private boolean customMappingSyncSendSyncResult;
    private MovementWatchdogMode movementWatchdogMode;
    private int movementWatchdogDimensionChangeTimeoutTicks;
    private int movementWatchdogChunkStuckTimeoutTicks;
    private int movementWatchdogChunkRadiusRequestIntervalTicks;

    public ViaBedrockConfig(final File configFile, final Logger logger) {
        super(configFile, logger);
    }

    @Override
    public void reload() {
        super.reload();
        this.loadFields();
    }

    private void loadFields() {
        this.enableExperimentalFeatures = this.getBoolean("enable-experimental-features", false);
        this.blobCacheMode = BlobCacheMode.byName(this.getString("blob-cache", "disk"));
        this.translateResourcePacks = this.getBoolean("translate-resource-packs", true);
        this.resourcePackHost = this.getString("resource-pack-host", "127.0.0.1");
        this.resourcePackPort = this.getInt("resource-pack-port", 0);
        this.resourcePackUrl = this.getString("resource-pack-url", "");
        this.packCacheMode = PackCacheMode.byName(this.getString("pack-cache", "disk"));
        this.translateShowCoordinatesGameRule = this.getBoolean("translate-show-coordinates-game-rule", false);
        this.disableServerBlacklist = this.getBoolean("disable-server-blacklist", false);
        this.language = this.getString("language", "");
        this.viaProxyAuthSecret = this.getString("viaproxy-auth-secret", "");
        this.enableServerEntityAnimation = this.getBoolean("enable-server-entity-animation", true);
        this.javaSkinFetchTimeout = this.getInt("java-skin-fetch-timeout", 1000);
        this.ditherMaps = this.getBoolean("dither-maps", true);
        final ConfigSection customMappingSync = this.getSection("customMappingSync");
        this.customMappingSyncEnabled = getBoolean(customMappingSync, "enabled", true);
        this.customMappingSyncTimeoutMs = getInt(customMappingSync, "timeoutMs", 10000);
        this.customMappingSyncMaxSnapshotBytes = getInt(customMappingSync, "maxSnapshotBytes", 16 * 1024 * 1024);
        this.customMappingSyncMaxPayloadBytes = getInt(customMappingSync, "maxPayloadBytes", 28672);
        this.customMappingSyncMaxCustomBlockStates = getInt(customMappingSync, "maxCustomBlockStates", 65536);
        this.customMappingSyncMaxCustomBlockEntityTypes = getInt(customMappingSync, "maxCustomBlockEntityTypes", 4096);
        this.customMappingSyncMaxJavaBlockStateId = getInt(customMappingSync, "maxJavaBlockStateId", 1048575);
        this.customMappingSyncFailureMode = CustomMappingSyncFailureMode.byName(getString(customMappingSync, "failureMode", "SAFE_FALLBACK"));
        this.customMappingSyncDefaultFallbackBlock = getString(customMappingSync, "defaultFallbackBlock", "minecraft:stone");
        this.customMappingSyncEnableChunks = getBoolean(customMappingSync, "enableChunks", true);
        this.customMappingSyncSendSyncResult = getBoolean(customMappingSync, "sendSyncResult", false);
        final ConfigSection movementWatchdog = this.getSection("movementWatchdog");
        this.movementWatchdogMode = MovementWatchdogMode.byName(getString(movementWatchdog, "mode", "observe"));
        this.movementWatchdogDimensionChangeTimeoutTicks = getInt(movementWatchdog, "dimensionChangeTimeoutTicks", 200);
        this.movementWatchdogChunkStuckTimeoutTicks = getInt(movementWatchdog, "chunkStuckTimeoutTicks", 200);
        this.movementWatchdogChunkRadiusRequestIntervalTicks = getInt(movementWatchdog, "chunkRadiusRequestIntervalTicks", 100);
    }

    private static boolean getBoolean(final ConfigSection section, final String key, final boolean def) {
        return section != null ? section.getBoolean(key, def) : def;
    }

    private static int getInt(final ConfigSection section, final String key, final int def) {
        return section != null ? section.getInt(key, def) : def;
    }

    private static String getString(final ConfigSection section, final String key, final String def) {
        return section != null ? section.getString(key, def) : def;
    }

    @Override
    public URL getDefaultConfigURL() {
        return this.getClass().getClassLoader().getResource("assets/viabedrock/viabedrock.yml");
    }

    @Override
    protected void handleConfig(Map<String, Object> map) {
    }

    @Override
    public boolean shouldEnableExperimentalFeatures() {
        return this.enableExperimentalFeatures;
    }

    @Override
    public List<String> getUnsupportedOptions() {
        return Collections.emptyList();
    }

    @Override
    public BlobCacheMode getBlobCacheMode() {
        return this.blobCacheMode;
    }

    @Override
    public boolean shouldTranslateResourcePacks() {
        return this.translateResourcePacks;
    }

    @Override
    public String getResourcePackHost() {
        return this.resourcePackHost;
    }

    @Override
    public int getResourcePackPort() {
        return this.resourcePackPort;
    }

    @Override
    public String getResourcePackUrl() {
        return this.resourcePackUrl;
    }

    @Override
    public PackCacheMode getPackCacheMode() {
        return this.packCacheMode;
    }

    @Override
    public boolean shouldTranslateShowCoordinatesGameRule() {
        return this.translateShowCoordinatesGameRule;
    }

    @Override
    public boolean shouldDisableServerBlacklist() {
        return this.disableServerBlacklist;
    }

    @Override
    public String getLanguage() {
        return this.language;
    }

    @Override
    public String getViaProxyAuthSecret() {
        return this.viaProxyAuthSecret;
    }

    @Override
    public boolean shouldEnableServerEntityAnimation() {
        return this.enableServerEntityAnimation;
    }

    @Override
    public int getJavaSkinFetchTimeout() {
        return this.javaSkinFetchTimeout;
    }

    @Override
    public boolean shouldDitherMaps() {
        return this.ditherMaps;
    }

    @Override
    public boolean isCustomMappingSyncEnabled() {
        return this.customMappingSyncEnabled;
    }

    @Override
    public int getCustomMappingSyncTimeoutMs() {
        return this.customMappingSyncTimeoutMs;
    }

    @Override
    public int getCustomMappingSyncMaxSnapshotBytes() {
        return this.customMappingSyncMaxSnapshotBytes;
    }

    @Override
    public int getCustomMappingSyncMaxPayloadBytes() {
        return this.customMappingSyncMaxPayloadBytes;
    }

    @Override
    public int getCustomMappingSyncMaxCustomBlockStates() {
        return this.customMappingSyncMaxCustomBlockStates;
    }

    @Override
    public int getCustomMappingSyncMaxCustomBlockEntityTypes() {
        return this.customMappingSyncMaxCustomBlockEntityTypes;
    }

    @Override
    public int getCustomMappingSyncMaxJavaBlockStateId() {
        return this.customMappingSyncMaxJavaBlockStateId;
    }

    @Override
    public CustomMappingSyncFailureMode getCustomMappingSyncFailureMode() {
        return this.customMappingSyncFailureMode;
    }

    @Override
    public String getCustomMappingSyncDefaultFallbackBlock() {
        return this.customMappingSyncDefaultFallbackBlock;
    }

    @Override
    public boolean isCustomMappingSyncChunksEnabled() {
        return this.customMappingSyncEnableChunks;
    }

    @Override
    public boolean shouldSendCustomMappingSyncResult() {
        return this.customMappingSyncSendSyncResult;
    }

    @Override
    public MovementWatchdogMode getMovementWatchdogMode() {
        return this.movementWatchdogMode;
    }

    @Override
    public int getMovementWatchdogDimensionChangeTimeoutTicks() {
        return this.movementWatchdogDimensionChangeTimeoutTicks;
    }

    @Override
    public int getMovementWatchdogChunkStuckTimeoutTicks() {
        return this.movementWatchdogChunkStuckTimeoutTicks;
    }

    @Override
    public int getMovementWatchdogChunkRadiusRequestIntervalTicks() {
        return this.movementWatchdogChunkRadiusRequestIntervalTicks;
    }

}
