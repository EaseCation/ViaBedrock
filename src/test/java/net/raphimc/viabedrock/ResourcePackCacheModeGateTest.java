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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackCacheModeGateTest {

    @Test
    void legacyStartupIgnoresSharedModeEnabledByConfigReload(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        final ViaBedrockConfig config = config(configFile, false);
        final ResourcePackCacheModeGate gate = new ResourcePackCacheModeGate();
        gate.initialize(config.shouldTranslateResourcePacks(), config.isSharedResourcePackCacheEnabled());

        reload(configFile, config, true);

        assertTrue(config.isSharedResourcePackCacheEnabled());
        assertFalse(gate.isSharedCacheEnabled());
    }

    @Test
    void sharedStartupIgnoresSharedModeDisabledByConfigReload(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        final ViaBedrockConfig config = config(configFile, true);
        final ResourcePackCacheModeGate gate = new ResourcePackCacheModeGate();
        gate.initialize(config.shouldTranslateResourcePacks(), config.isSharedResourcePackCacheEnabled());

        reload(configFile, config, false);

        assertFalse(config.isSharedResourcePackCacheEnabled());
        assertTrue(gate.isSharedCacheEnabled());
    }

    @Test
    void resetClearsModeAndAllowsTheNextStartupToRecaptureIt() {
        final ResourcePackCacheModeGate gate = new ResourcePackCacheModeGate();
        assertTrue(gate.initialize(true, true));

        gate.reset();

        assertFalse(gate.isSharedCacheEnabled());
        assertFalse(gate.initialize(true, false));
    }

    @Test
    void componentSelectionFailsFastInSharedModeAndIgnoresStaleSharedComponentsInLegacyMode() {
        final ResourcePackCacheModeGate gate = new ResourcePackCacheModeGate();
        final Object component = new Object();
        gate.initialize(true, true);

        assertSame(component, gate.activeComponent(component, "missing"));
        assertThrows(IllegalStateException.class, () -> gate.activeComponent(null, "missing"));

        gate.reset();
        gate.initialize(true, false);
        assertNull(gate.activeComponent(component, "missing"));
    }

    private static ViaBedrockConfig config(final Path configFile, final boolean enabled) throws Exception {
        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), Logger.getAnonymousLogger());
        reload(configFile, config, enabled);
        return config;
    }

    private static void reload(final Path configFile, final ViaBedrockConfig config,
                               final boolean enabled) throws Exception {
        Files.writeString(configFile, """
                translate-resource-packs: true
                resource-pack-cache:
                  enabled: %s
                """.formatted(enabled));
        config.reload();
    }

}
