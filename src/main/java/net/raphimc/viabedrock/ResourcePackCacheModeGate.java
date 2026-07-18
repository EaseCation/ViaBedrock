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

final class ResourcePackCacheModeGate {

    private volatile boolean sharedCacheEnabled;
    private boolean initialized;

    synchronized boolean initialize(final boolean resourcePackTranslationEnabled,
                                    final boolean configuredSharedCacheEnabled) {
        if (this.initialized) {
            throw new IllegalStateException("Resource pack cache mode is already initialized");
        }
        this.sharedCacheEnabled = resourcePackTranslationEnabled && configuredSharedCacheEnabled;
        this.initialized = true;
        return this.sharedCacheEnabled;
    }

    boolean isSharedCacheEnabled() {
        return this.sharedCacheEnabled;
    }

    <T> T activeComponent(final T component, final String unavailableMessage) {
        if (!this.sharedCacheEnabled) {
            return null;
        }
        if (component == null) {
            throw new IllegalStateException(unavailableMessage);
        }
        return component;
    }

    synchronized void reset() {
        this.sharedCacheEnabled = false;
        this.initialized = false;
    }

}
