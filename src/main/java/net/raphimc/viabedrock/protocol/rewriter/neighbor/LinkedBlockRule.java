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
package net.raphimc.viabedrock.protocol.rewriter.neighbor;

import com.google.common.collect.BiMap;
import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSet;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.data.enums.DyeColor;
import net.raphimc.viabedrock.protocol.rewriter.blockentity.BedBlockEntityRewriter;

import java.util.Locale;
import java.util.Map;

/**
 * Two-part blocks (door halves, bed parts) whose shared Java properties Bedrock splits across the two blocks and
 * lets the client recombine. Each half is recomputed independently from itself and its partner:
 * <ul>
 *     <li>Door: Bedrock stores {@code facing/open} on the lower half and {@code hinge} on the upper half, so each
 *     half pulls the missing shared property from its partner.</li>
 *     <li>Bed: dye colour lives only in the block entity NBT (baked into the Java id) and {@link BlockNeighborView#getJavaBlockState}
 *     is palette-only, so a partner fetched that way is a colourless {@code white_bed}. Each half re-derives its
 *     colour from its own block entity (via {@link BedBlockEntityRewriter#getColor}), so the partner is never
 *     downgraded to white.</li>
 * </ul>
 */
public final class LinkedBlockRule implements NeighborAwareBlockRule {

    private static final String HALF = "half";
    private static final String LOWER = "lower";
    private static final String UPPER = "upper";
    private static final String FACING = "facing";
    private static final String OPEN = "open";
    private static final String HINGE = "hinge";
    private static final String POWERED = "powered";
    private static final String OCCUPIED = "occupied";
    private static final String PART = "part";
    private static final String BED_SUFFIX = "_bed";

    private final BiMap<BlockState, Integer> javaBlockStates;
    private final Int2ObjectMap<BlockState> javaBlockStatesById = new Int2ObjectOpenHashMap<>();
    private final IntSet doorStates = new IntOpenHashSet();
    private final IntSet bedStates = new IntOpenHashSet();

    public LinkedBlockRule(final BiMap<BlockState, Integer> javaBlockStates) {
        this.javaBlockStates = javaBlockStates;

        for (Map.Entry<BlockState, Integer> entry : javaBlockStates.entrySet()) {
            final BlockState state = entry.getKey();
            final int id = entry.getValue();
            this.javaBlockStatesById.put(id, state);
            if (isDoor(state)) {
                this.doorStates.add(id);
            } else if (isBed(state)) {
                this.bedStates.add(id);
            }
        }
    }

    @Override
    public boolean handles(final int javaBlockStateId) {
        return this.doorStates.contains(javaBlockStateId) || this.bedStates.contains(javaBlockStateId);
    }

    @Override
    public int recompute(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        if (this.doorStates.contains(javaBlockStateId)) {
            return recomputeDoor(view, pos, javaBlockStateId);
        }
        if (this.bedStates.contains(javaBlockStateId)) {
            return recomputeBed(view, pos, javaBlockStateId);
        }
        return javaBlockStateId;
    }

    private int recomputeDoor(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        final BlockState self = this.javaBlockStatesById.get(javaBlockStateId);
        if (self == null) return javaBlockStateId;

        final String half = self.properties().get(HALF);
        final BlockPosition otherPos;
        if (LOWER.equals(half)) {
            otherPos = pos.getRelative(BlockFace.TOP);
        } else if (UPPER.equals(half)) {
            otherPos = pos.getRelative(BlockFace.BOTTOM);
        } else {
            return javaBlockStateId;
        }

        final BlockState other = this.javaBlockStatesById.get(view.getJavaBlockState(otherPos));
        final BlockState lowerState = LOWER.equals(half) ? self : other;
        final BlockState upperState = UPPER.equals(half) ? self : other;
        if (!isDoorHalfPair(self, lowerState, upperState)) {
            return javaBlockStateId;
        }

        final String facing = firstNonNull(lowerState.properties().get(FACING), upperState.properties().get(FACING));
        final String open = firstNonNull(lowerState.properties().get(OPEN), upperState.properties().get(OPEN));
        final String hinge = firstNonNull(upperState.properties().get(HINGE), lowerState.properties().get(HINGE));
        final String powered = firstNonNull(lowerState.properties().get(POWERED), upperState.properties().get(POWERED));

        BlockState fixed = self;
        fixed = withProperty(fixed, FACING, facing);
        fixed = withProperty(fixed, OPEN, open);
        fixed = withProperty(fixed, HINGE, hinge);
        fixed = withProperty(fixed, POWERED, powered);
        return this.javaBlockStates.getOrDefault(fixed, javaBlockStateId);
    }

    private int recomputeBed(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        final BlockState self = this.javaBlockStatesById.get(javaBlockStateId);
        if (self == null) return javaBlockStateId;

        // A bed's dye colour lives only in the Bedrock block entity NBT and is baked into the Java id; a palette-only
        // neighbor lookup yields the colourless white_bed base. Re-derive this half's colour from its own block
        // entity (same source and RED fallback as BedBlockEntityRewriter) so the partner is rebuilt with the true
        // dyed identifier instead of downgrading to white.
        final BedrockBlockEntity blockEntity = view.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.tag() == null) {
            return javaBlockStateId;
        }

        final DyeColor color = BedBlockEntityRewriter.getColor(blockEntity.tag());
        final BlockState fixed = self.withIdentifier(color.name().toLowerCase(Locale.ROOT) + BED_SUFFIX);
        return this.javaBlockStates.getOrDefault(fixed, javaBlockStateId);
    }

    private static boolean isDoor(final BlockState state) {
        return state.identifier().endsWith("_door")
                && state.properties().containsKey(HALF)
                && state.properties().containsKey(FACING)
                && state.properties().containsKey(OPEN)
                && state.properties().containsKey(HINGE);
    }

    private static boolean isBed(final BlockState state) {
        return state.identifier().endsWith(BED_SUFFIX)
                && state.properties().containsKey(PART)
                && state.properties().containsKey(FACING)
                && state.properties().containsKey(OCCUPIED);
    }

    private static boolean isDoorHalfPair(final BlockState self, final BlockState lowerState, final BlockState upperState) {
        return lowerState != null
                && upperState != null
                && lowerState.namespacedIdentifier().equals(self.namespacedIdentifier())
                && upperState.namespacedIdentifier().equals(self.namespacedIdentifier())
                && LOWER.equals(lowerState.properties().get(HALF))
                && UPPER.equals(upperState.properties().get(HALF));
    }

    private static BlockState withProperty(final BlockState state, final String key, final String value) {
        if (value == null) {
            return state;
        }
        return state.withProperty(key, value);
    }

    private static String firstNonNull(final String first, final String second) {
        return first != null ? first : second;
    }

}
