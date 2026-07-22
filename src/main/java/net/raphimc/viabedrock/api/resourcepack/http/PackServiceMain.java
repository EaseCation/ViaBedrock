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

import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PackServiceMain {

    private static final Logger LOGGER = Logger.getLogger(PackServiceMain.class.getName());

    private PackServiceMain() {
    }

    public static void main(final String[] args) throws Exception {
        final PackServiceConfig config = PackServiceConfig.fromEnvironment();
        final PackServiceMetrics metrics = new PackServiceMetrics();
        final PackServiceStore store = new PackServiceStore(config, metrics);
        final PackServiceHttpServer server = new PackServiceHttpServer(config, store, metrics);
        final CountDownLatch stopped = new CountDownLatch(1);
        final Thread shutdownHook = new Thread(() -> {
            try {
                server.close();
            } catch (Throwable error) {
                LOGGER.log(Level.WARNING, "Failed to stop the resource pack service cleanly", error);
            } finally {
                stopped.countDown();
            }
        }, "Pack Service Shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            server.start();
            LOGGER.info(() -> "Started Java resource pack service: public=" + config.publicAddress()
                    + ", internal=" + config.internalAddress() + ", metrics=" + config.metricsAddress()
                    + ", data=" + config.dataDirectory());
            stopped.await();
        } catch (Throwable error) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
            }
            server.close();
            throw error;
        }
    }
}
