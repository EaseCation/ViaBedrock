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
package net.raphimc.viabedrock.protocol.storage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GlobalLightCache {

    private static final GlobalLightCache INSTANCE = new GlobalLightCache();
    private static final int MAX_ENTRIES = 4096;
    private static final int LIGHT_COMPUTE_THREADS = 2;

    public static GlobalLightCache getInstance() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<Long, LightCacheEntry> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(LIGHT_COMPUTE_THREADS, r -> {
        final Thread t = new Thread(r, "ViaBedrock-LightCompute");
        t.setDaemon(true);
        return t;
    });

    private GlobalLightCache() {
    }

    public void submitAsync(final Runnable task) {
        this.executor.submit(task);
    }

    public record LightCacheEntry(byte[][] skyLight, byte[][] blockLight, long timestamp) {
    }

    public LightCacheEntry get(final long key) {
        final LightCacheEntry entry = this.cache.get(key);
        if (entry == null) return null;
        return new LightCacheEntry(deepCopy(entry.skyLight), deepCopy(entry.blockLight), entry.timestamp);
    }

    public void put(final long key, final byte[][] skyLight, final byte[][] blockLight) {
        if (this.cache.size() >= MAX_ENTRIES) {
            this.evictOldest();
        }
        this.cache.put(key, new LightCacheEntry(deepCopy(skyLight), deepCopy(blockLight), System.currentTimeMillis()));
    }

    public void invalidate(final long key) {
        this.cache.remove(key);
    }

    public void clear() {
        this.cache.clear();
    }

    private void evictOldest() {
        final long cutoff = this.cache.values().stream()
                .mapToLong(LightCacheEntry::timestamp)
                .sorted()
                .skip(MAX_ENTRIES / 4)
                .findFirst().orElse(Long.MAX_VALUE);
        this.cache.entrySet().removeIf(e -> e.getValue().timestamp < cutoff);
    }

    private static byte[][] deepCopy(final byte[][] data) {
        if (data == null) return null;
        final byte[][] copy = new byte[data.length][];
        for (int i = 0; i < data.length; i++) {
            copy[i] = data[i] != null ? data[i].clone() : null;
        }
        return copy;
    }

}
