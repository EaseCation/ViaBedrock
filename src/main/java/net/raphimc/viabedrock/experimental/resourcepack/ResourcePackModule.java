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
package net.raphimc.viabedrock.experimental.resourcepack;

import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Registers fork-specific resource pack rewriters and handles post-pack-stack initialization.
 */
public class ResourcePackModule implements FeatureModule {

    // initRuntimeData() builds a BedrockMotion PackManager (loading dozens of packs) and per-bone
    // entity models - several seconds of CPU work. It used to run synchronously on the Bedrock IO
    // thread (Netty NIO Client IO #N) inside the RESOURCE_PACK_STACK handler, which blocked that
    // thread from answering the server's keep-alive and caused the connection to be timed out on the
    // first (cache-cold) join. Offload it here so the IO thread keeps processing packets; consumers
    // (CustomEntity) already null-check the converterData entries and degrade gracefully until ready.
    private static final ExecutorService RUNTIME_DATA_EXECUTOR = Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "ViaBedrock RuntimeData Executor");
        t.setDaemon(true);
        return t;
    });

    public ResourcePackModule() {
        ResourcePackRewriter.registerRewriter(new UITextureResourceRewriter());
    }

    @Override
    public void onResourcePackStackSet(final UserConnection user) {
        final ResourcePackStorage resourcePackStorage = user.get(ResourcePackStorage.class);
        RUNTIME_DATA_EXECUTOR.execute(() -> {
            final long start = System.nanoTime();
            try {
                ResourcePackRewriter.initRuntimeData(resourcePackStorage);
                ViaBedrock.getPlatform().getLogger().info(
                        "Initialized entity runtime data in " + ((System.nanoTime() - start) / 1_000_000L) + "ms (async)");
            } catch (Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to initialize entity runtime data", e);
            }
        });
    }

}
