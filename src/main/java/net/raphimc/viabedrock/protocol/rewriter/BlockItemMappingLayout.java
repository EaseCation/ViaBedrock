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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.NumberTag;
import com.viaversion.viaversion.libs.fastutil.ints.IntSortedSet;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.experimental.custommapping.RuntimeProjectionBuilder;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemVersion;
import net.raphimc.viabedrock.protocol.model.BlockProperties;
import net.raphimc.viabedrock.protocol.model.ItemEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Safe lookup helpers for Bedrock block-item → Java item mapping.
 * <p>
 * Nukkit-MOT 860 START_GAME palettes (and NetEase extras such as
 * {@code askyblockwar:*} / {@code minecraft:cinnabar_*} walls) can list an
 * identifier in {@code bedrockToJavaBlockItems} while the session
 * {@code validBlockStates} table has no runtime ids. Official ViaBedrock
 * then called {@code IntSortedSet.firstInt()} on null and kicked Java 1.21.11
 * from {@code ADD_PLAYER} / {@code MOB_EQUIPMENT}. A missing palette must
 * fall through to the paper/custom-item path instead of throwing.
 */
public final class BlockItemMappingLayout {

    private BlockItemMappingLayout() {
    }

    /**
     * Default block runtime id for a block item that arrived with runtime 0.
     * {@code 0} means "unknown" and the caller must not treat it as a mapped state.
     */
    public static int fallbackBlockRuntimeId(final IntSortedSet validBlockStates) {
        if (validBlockStates == null || validBlockStates.isEmpty()) {
            return 0;
        }
        return validBlockStates.firstInt();
    }

    /**
     * Keep the MOT 860 wire runtime when the session palette is missing or empty.
     * Official ViaBedrock called {@code firstInt()} on that empty set during item
     * decode and kicked Java 1.21.11. A populated palette still remaps unknown
     * states to the first known runtime id.
     */
    public static int sanitizeBlockRuntimeId(final IntSortedSet validBlockStates, final int blockRuntimeId) {
        if (validBlockStates == null || validBlockStates.isEmpty() || validBlockStates.contains(blockRuntimeId)) {
            return blockRuntimeId;
        }
        return validBlockStates.firstInt();
    }

    /**
     * MOT custom block items use {@code itemId = 255 - nukkitId} and never appear
     * in ITEM_REGISTRY. Inventory slots still carry that negative varint.
     */
    public static int customBlockItemId(final int nukkitBlockId) {
        return 255 - nukkitBlockId;
    }

    public static Integer customBlockItemId(final CompoundTag blockProperties) {
        if (!(blockProperties != null && blockProperties.get("vanilla_block_data") instanceof CompoundTag vanillaBlockData)) {
            return null;
        }
        if (!(vanillaBlockData.get("block_id") instanceof NumberTag blockIdTag)) {
            return null;
        }
        return customBlockItemId(blockIdTag.asInt());
    }

    public static ItemEntry[] mergeCustomBlockItems(final ItemEntry[] itemEntries, final BlockProperties[] blockProperties) {
        if (blockProperties == null || blockProperties.length == 0) {
            return itemEntries != null ? itemEntries : new ItemEntry[0];
        }
        final Set<String> vanillaIdentifiers = BedrockProtocol.MAPPINGS.getBedrockBlockStates().stream()
                .map(BedrockBlockState::namespacedIdentifier)
                .collect(Collectors.toSet());
        return mergeCustomBlockItems(itemEntries, RuntimeProjectionBuilder.collectEffectiveCustomBlockProperties(blockProperties, vanillaIdentifiers));
    }

    public static ItemEntry[] mergeCustomBlockItems(final ItemEntry[] itemEntries, final Map<String, CompoundTag> customBlockProperties) {
        if (customBlockProperties == null || customBlockProperties.isEmpty()) {
            return itemEntries != null ? itemEntries : new ItemEntry[0];
        }

        final List<ItemEntry> merged = new ArrayList<>();
        if (itemEntries != null) {
            merged.addAll(Arrays.asList(itemEntries));
        }

        final Set<String> knownIdentifiers = merged.stream()
                .map(entry -> entry.identifier().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<Integer> knownIds = merged.stream()
                .map(ItemEntry::id)
                .collect(Collectors.toCollection(HashSet::new));

        for (Map.Entry<String, CompoundTag> customBlock : customBlockProperties.entrySet()) {
            final String identifier = customBlock.getKey();
            if (knownIdentifiers.contains(identifier)) {
                continue;
            }
            final Integer itemId = customBlockItemId(customBlock.getValue());
            if (itemId == null || knownIds.contains(itemId)) {
                continue;
            }
            knownIdentifiers.add(identifier);
            knownIds.add(itemId);
            merged.add(new ItemEntry(identifier, itemId, false, ItemVersion.None, null));
        }
        return merged.toArray(ItemEntry[]::new);
    }

}
