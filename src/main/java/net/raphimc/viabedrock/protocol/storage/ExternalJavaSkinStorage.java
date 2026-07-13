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

import com.viaversion.viaversion.api.connection.StorableObject;
import net.raphimc.viabedrock.protocol.model.JavaSkinData;
import net.raphimc.viabedrock.protocol.model.JavaSkinSource;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public record ExternalJavaSkinStorage(
        CompletableFuture<JavaSkinData> future,
        JavaSkinSource source,
        int waitTimeoutMs
) implements StorableObject {

    public ExternalJavaSkinStorage {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(source, "source");
        if (waitTimeoutMs < 1 || waitTimeoutMs > 1_000) {
            throw new IllegalArgumentException("waitTimeoutMs must be between 1 and 1000");
        }
    }

}
