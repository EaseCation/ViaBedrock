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

import net.raphimc.viabedrock.protocol.model.JavaSkinData;
import net.raphimc.viabedrock.protocol.model.JavaSkinSource;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalJavaSkinStorageTest {

    @Test
    void retainsFutureSourceAndWaitTimeout() {
        final JavaSkinData skin = new JavaSkinData(
                new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), null, true, "netease:123");
        final CompletableFuture<JavaSkinData> future = CompletableFuture.completedFuture(skin);

        final ExternalJavaSkinStorage storage = new ExternalJavaSkinStorage(future, JavaSkinSource.NETEASE, 500);

        assertSame(future, storage.future());
        assertEquals(JavaSkinSource.NETEASE, storage.source());
        assertEquals(500, storage.waitTimeoutMs());
    }

    @Test
    void enforcesShortLoginWaitBudget() {
        final CompletableFuture<JavaSkinData> future = new CompletableFuture<>();

        new ExternalJavaSkinStorage(future, JavaSkinSource.NETEASE, 1);
        new ExternalJavaSkinStorage(future, JavaSkinSource.NETEASE, 1_000);
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalJavaSkinStorage(future, JavaSkinSource.NETEASE, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalJavaSkinStorage(future, JavaSkinSource.NETEASE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalJavaSkinStorage(future, JavaSkinSource.NETEASE, 1_001));
    }

}
