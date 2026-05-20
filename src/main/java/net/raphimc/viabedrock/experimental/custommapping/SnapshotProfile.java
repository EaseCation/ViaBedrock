/*
 * Validated and locally degraded immutable profile derived from a custom mapping snapshot.
 */
package net.raphimc.viabedrock.experimental.custommapping;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

import java.util.*;
import java.util.logging.Level;

public final class SnapshotProfile {

    private final Map<CustomMappingSnapshot.TypedBedrockState, BlockStateMapping> blockStatesByState;
    private final List<BlockEntityTypeMapping> blockEntityTypes;
    private final List<String> diagnostics;
    private final long cacheKey;

    private SnapshotProfile(
            final Map<CustomMappingSnapshot.TypedBedrockState, BlockStateMapping> blockStatesByState,
            final List<BlockEntityTypeMapping> blockEntityTypes,
            final List<String> diagnostics,
            final long cacheKey) {
        this.blockStatesByState = Map.copyOf(blockStatesByState);
        this.blockEntityTypes = List.copyOf(blockEntityTypes);
        this.diagnostics = List.copyOf(diagnostics);
        this.cacheKey = cacheKey;
    }

    public static SnapshotProfile empty() {
        return new SnapshotProfile(Map.of(), List.of(), List.of(), CustomMappingProfileCache.EMPTY_KEY);
    }

    public static SnapshotProfile fromSnapshot(final CustomMappingSnapshot snapshot) {
        return fromSnapshot(snapshot, CustomMappingProfileCache.UNKNOWN_KEY);
    }

    public static SnapshotProfile fromSnapshot(final CustomMappingSnapshot snapshot, final long cacheKey) {
        final int defaultFallbackRawId = defaultFallbackRawId(snapshot.vanillaBlockStateCount());
        final List<String> diagnostics = new ArrayList<>();
        final List<BlockEntityTypeMapping> entityTypes = new ArrayList<>();
        final Map<String, BlockEntityTypeMapping> entityTypesByBedrockIdentifier = new HashMap<>();
        for (CustomMappingSnapshot.BlockEntityTypeEntry type : snapshot.blockEntityTypes()) {
            final CustomMappingAccess.BlockEntityRule rule = type.rule() == CustomMappingAccess.BlockEntityRule.NONE
                    ? CustomMappingAccess.BlockEntityRule.DROP : type.rule();
            final BlockEntityTypeMapping mapping = new BlockEntityTypeMapping(type.bedrockIdentifier(), type.javaIdentifier(), type.javaRawId(), rule);
            entityTypes.add(mapping);
            entityTypesByBedrockIdentifier.put(type.bedrockIdentifier(), mapping);
        }

        final Map<CustomMappingSnapshot.TypedBedrockState, BlockStateMapping> states = new HashMap<>();
        for (CustomMappingSnapshot.BlockStateEntry state : snapshot.blockStates()) {
            int fallbackJavaRawId = state.fallbackJavaRawId();
            if (fallbackJavaRawId < 0 || fallbackJavaRawId >= snapshot.vanillaBlockStateCount()) {
                diagnostics.add("Degraded invalid fallback for " + state.bedrockState().toUntypedBlockStateString() + ": " + fallbackJavaRawId);
                fallbackJavaRawId = defaultFallbackRawId;
            }

            int emit = state.emit();
            int filter = state.filter();
            if (emit < 0 || emit > 15) {
                diagnostics.add("Degraded invalid emit light for " + state.bedrockState().toUntypedBlockStateString() + ": " + emit);
                emit = 0;
            }
            if (filter < 0 || filter > 15) {
                diagnostics.add("Degraded invalid filter light for " + state.bedrockState().toUntypedBlockStateString() + ": " + filter);
                filter = BedrockProtocol.MAPPINGS.getFilterLight(fallbackJavaRawId);
            }

            CustomMappingAccess.BlockEntityRule rule = state.blockEntityRule();
            if (rule == CustomMappingAccess.BlockEntityRule.MOD_BLOCK && !entityTypesByBedrockIdentifier.containsKey(state.bedrockState().identifier())) {
                diagnostics.add("Degraded missing mod_block block entity type for " + state.bedrockState().toUntypedBlockStateString());
                rule = CustomMappingAccess.BlockEntityRule.DROP;
            }

            states.put(state.bedrockState(), new BlockStateMapping(state.javaRawId(), fallbackJavaRawId, emit, filter, rule));
        }

        if (!diagnostics.isEmpty()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Custom mapping snapshot was locally degraded: " + diagnostics.size() + " issue(s)");
        }
        return new SnapshotProfile(states, entityTypes, diagnostics, cacheKey);
    }

    public BlockStateMapping blockState(final BlockState runtimeState) {
        return this.blockStatesByState.get(CustomMappingSnapshot.TypedBedrockState.fromRuntimeState(runtimeState));
    }

    public List<BlockEntityTypeMapping> blockEntityTypes() {
        return this.blockEntityTypes;
    }

    public List<String> diagnostics() {
        return this.diagnostics;
    }

    public long cacheKey() {
        return this.cacheKey;
    }

    public record BlockStateMapping(int javaRawId, int fallbackJavaRawId, int emit, int filter, CustomMappingAccess.BlockEntityRule rule) {
    }

    public record BlockEntityTypeMapping(String bedrockIdentifier, String javaIdentifier, int javaRawId, CustomMappingAccess.BlockEntityRule rule) {
    }

    private static int defaultFallbackRawId(final int vanillaBlockStateCount) {
        final String configured = ViaBedrock.getConfig().getCustomMappingSyncDefaultFallbackBlock();
        int fallback = rawIdFor(configured);
        if (fallback >= 0 && fallback < vanillaBlockStateCount) return fallback;
        fallback = rawIdFor("minecraft:stone");
        if (fallback >= 0 && fallback < vanillaBlockStateCount) return fallback;
        return 0;
    }

    private static int rawIdFor(final String blockStateString) {
        try {
            final Integer id = BedrockProtocol.MAPPINGS.getJavaBlockStates().get(BlockState.fromString(blockStateString));
            return id != null ? id : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
