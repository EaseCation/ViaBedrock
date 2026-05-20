package net.raphimc.viabedrock.experimental.custommapping;

import com.viaversion.nbt.tag.CompoundTag;
import net.raphimc.viabedrock.protocol.model.BlockProperties;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeProjectionCache {
    private static final int KEY_SCHEMA_VERSION = 2;
    private static final String PROJECTION_ALGORITHM_VERSION = "runtime-projection-v1";
    private static final String LIGHT_SEMANTIC_ALGORITHM_VERSION = "block-light-semantics-v1";
    private static final int MAX_ENTRIES = 256;
    private static final long TTL_MILLIS = 30 * 60 * 1000L;
    private static final RuntimeProjectionCache INSTANCE = new RuntimeProjectionCache();

    public static RuntimeProjectionCache getInstance() {
        return INSTANCE;
    }

    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);

    private RuntimeProjectionCache() {
    }

    public synchronized RuntimeProjection get(final Key key) {
        final long now = System.currentTimeMillis();
        pruneExpired(now);
        final Entry entry = this.entries.get(key);
        if (entry == null) return null;
        entry.lastAccessMillis = now;
        return entry.projection;
    }

    public synchronized void put(final Key key, final RuntimeProjection projection) {
        this.entries.put(key, new Entry(projection, System.currentTimeMillis()));
        evictOverflow();
    }

    public static Key key(final SnapshotProfile profile, final BlockProperties[] blockProperties, final boolean hashedRuntimeBlockIds, final boolean useSyntheticSourceIds) {
        return new Key(profile.cacheKey(), runtimeKey(blockProperties, hashedRuntimeBlockIds, useSyntheticSourceIds));
    }

    private static long runtimeKey(final BlockProperties[] blockProperties, final boolean hashedRuntimeBlockIds, final boolean useSyntheticSourceIds) {
        long hash = 0xcbf29ce484222325L;
        hash = fnv1a(hash, KEY_SCHEMA_VERSION);
        hash = fnv1a(hash, CustomMappingProfileCache.SYNC_MAPPING_VERSION);
        hash = fnv1a(hash, PROJECTION_ALGORITHM_VERSION);
        hash = fnv1a(hash, LIGHT_SEMANTIC_ALGORITHM_VERSION);
        hash = fnv1a(hash, ProtocolConstants.BEDROCK_PROTOCOL_VERSION);
        hash = fnv1a(hash, ProtocolConstants.BEDROCK_VERSION_NAME);
        hash = fnv1a(hash, hashedRuntimeBlockIds ? 1 : 0);
        hash = fnv1a(hash, useSyntheticSourceIds ? 1 : 0);
        hash = fnv1a(hash, blockProperties.length);
        for (final BlockProperties blockProperty : blockProperties) {
            hash = fnv1a(hash, blockProperty.name().hashCode());
            final CompoundTag properties = blockProperty.properties();
            hash = fnv1a(hash, properties != null ? properties.toString().hashCode() : 0);
        }
        return hash;
    }

    private void pruneExpired(final long now) {
        final Iterator<Map.Entry<Key, Entry>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().lastAccessMillis > TTL_MILLIS) {
                iterator.remove();
            }
        }
    }

    private void evictOverflow() {
        while (this.entries.size() > MAX_ENTRIES) {
            final Iterator<Key> iterator = this.entries.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private static long fnv1a(long hash, final int value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private static long fnv1a(long hash, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash = fnv1a(hash, bytes.length);
        for (final byte b : bytes) {
            hash ^= b & 0xFFL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    public record Key(long profileKey, long runtimeKey) {
    }

    private static final class Entry {
        private final RuntimeProjection projection;
        private long lastAccessMillis;

        private Entry(final RuntimeProjection projection, final long lastAccessMillis) {
            this.projection = projection;
            this.lastAccessMillis = lastAccessMillis;
        }
    }
}
