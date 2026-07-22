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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PackServiceConfigTest {

    @Test
    void loadsBoundedEnvironmentConfiguration() {
        final Map<String, String> environment = new HashMap<>();
        environment.put("PACK_SERVICE_SHARED_SECRET", "secret");
        environment.put("PACK_SERVICE_PUBLIC_PORT", "18080");
        environment.put("PACK_SERVICE_INTERNAL_PORT", "18081");
        environment.put("PACK_SERVICE_METRICS_PORT", "19462");
        environment.put("PACK_SERVICE_MAX_UPLOAD_MIB", "32");
        final PackServiceConfig config = PackServiceConfig.fromEnvironment(environment);
        assertEquals(18080, config.publicAddress().getPort());
        assertEquals(18081, config.internalAddress().getPort());
        assertEquals(19462, config.metricsAddress().getPort());
        assertEquals(32L * 1024L * 1024L, config.maxUploadBytes());
        assertEquals("secret", config.sharedSecret());
    }

    @Test
    void requiresSecretAndDistinctPorts() {
        assertThrows(IllegalArgumentException.class, () -> PackServiceConfig.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> PackServiceConfig.fromEnvironment(Map.of(
                "PACK_SERVICE_SHARED_SECRET", "secret",
                "PACK_SERVICE_PUBLIC_PORT", "8080",
                "PACK_SERVICE_INTERNAL_PORT", "8080")));
    }
}
