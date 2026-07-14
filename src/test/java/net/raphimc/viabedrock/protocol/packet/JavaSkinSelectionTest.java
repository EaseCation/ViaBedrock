/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import net.raphimc.viabedrock.protocol.model.JavaSkinData;
import net.raphimc.viabedrock.protocol.model.JavaSkinSource;
import net.raphimc.viabedrock.protocol.storage.AuthData;
import net.raphimc.viabedrock.protocol.storage.ExternalJavaSkinStorage;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class JavaSkinSelectionTest {

    private static final UUID ONLINE_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OFFLINE_UUID = UUID.fromString("22222222-2222-3222-8222-222222222222");

    @Test
    void externalFutureAlwaysWinsWithoutCallingMojang() {
        final CompletableFuture<JavaSkinData> externalFuture = new CompletableFuture<>();
        final ExternalJavaSkinStorage external = new ExternalJavaSkinStorage(
                externalFuture, JavaSkinSource.NETEASE, 500);
        final AtomicInteger mojangCalls = new AtomicInteger();
        final AuthData authData = authData();

        LoginPackets.configureJavaSkinFuture(authData, external, 1_000, ONLINE_UUID, uuid -> {
            mojangCalls.incrementAndGet();
            return new CompletableFuture<>();
        });

        assertSame(externalFuture, authData.getJavaSkinFuture());
        assertEquals(JavaSkinSource.NETEASE, authData.getExternalJavaSkinSource());
        assertEquals(500, authData.getJavaSkinWaitTimeoutMs());
        assertEquals(0, mojangCalls.get());
    }

    @Test
    void mojangFetchRemainsAvailableWithoutExternalStorage() {
        final CompletableFuture<JavaSkinData> mojangFuture = new CompletableFuture<>();
        final AtomicInteger mojangCalls = new AtomicInteger();
        final AuthData authData = authData();

        LoginPackets.configureJavaSkinFuture(authData, null, 1_000, ONLINE_UUID, uuid -> {
            mojangCalls.incrementAndGet();
            return mojangFuture;
        });

        assertSame(mojangFuture, authData.getJavaSkinFuture());
        assertNull(authData.getExternalJavaSkinSource());
        assertEquals(1_000, authData.getJavaSkinWaitTimeoutMs());
        assertEquals(1, mojangCalls.get());
    }

    @Test
    void skipsMojangForDisabledMissingOrNonOnlineUuid() {
        final AtomicInteger mojangCalls = new AtomicInteger();

        LoginPackets.configureJavaSkinFuture(authData(), null, 0, ONLINE_UUID, uuid -> {
            mojangCalls.incrementAndGet();
            return new CompletableFuture<>();
        });
        LoginPackets.configureJavaSkinFuture(authData(), null, 1_000, null, uuid -> {
            mojangCalls.incrementAndGet();
            return new CompletableFuture<>();
        });
        LoginPackets.configureJavaSkinFuture(authData(), null, 1_000, OFFLINE_UUID, uuid -> {
            mojangCalls.incrementAndGet();
            return new CompletableFuture<>();
        });

        assertEquals(0, mojangCalls.get());
    }

    private static AuthData authData() {
        return new AuthData(null, null);
    }

}
