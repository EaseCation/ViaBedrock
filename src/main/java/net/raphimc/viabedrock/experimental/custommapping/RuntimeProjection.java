/*
 * Immutable connection runtime projection derived from SnapshotProfile and Bedrock START_GAME runtime ids.
 */
package net.raphimc.viabedrock.experimental.custommapping;

import java.util.List;

public record RuntimeProjection(
        List<ProjectedBlockState> blockStates,
        List<SnapshotProfile.BlockEntityTypeMapping> blockEntityTypes) {

    public RuntimeProjection {
        blockStates = List.copyOf(blockStates);
        blockEntityTypes = List.copyOf(blockEntityTypes);
    }

    public CustomMappingAccess toAccess() {
        final CustomMappingAccess.Builder builder = new CustomMappingAccess.Builder();
        for (SnapshotProfile.BlockEntityTypeMapping type : this.blockEntityTypes) {
            builder.addBlockEntityType(type.bedrockIdentifier(), type.javaIdentifier(), type.javaRawId(), type.rule());
        }
        for (ProjectedBlockState state : this.blockStates) {
            builder.addBlockState(state.runtimeId(), state.bedrockIdentifier(), state.sourceJavaRawId(), state.fallbackJavaRawId(), state.emit(), state.filter(), state.rule());
        }
        return builder.build();
    }

    public boolean isEmpty() {
        return this.blockStates.isEmpty() && this.blockEntityTypes.isEmpty();
    }

    public record ProjectedBlockState(
            int runtimeId,
            String bedrockIdentifier,
            int sourceJavaRawId,
            int targetJavaRawId,
            int fallbackJavaRawId,
            int emit,
            int filter,
            CustomMappingAccess.BlockEntityRule rule) {
    }
}
