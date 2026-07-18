/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.Tag;
import net.raphimc.viabedrock.api.chunk.bitarray.BitArrayVersion;
import net.raphimc.viabedrock.api.chunk.datapalette.BedrockDataPalette;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.api.util.BlockStateHasher;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersistentBlockStatePaletteTest {

    @Test
    void resolvesSequentialHashedAndCustomPersistentEntriesInPlace() {
        final CompoundTag sequentialCanonicalTag = blockState("test:sequential", 1);
        final CompoundTag hashedCanonicalTag = blockState("test:hashed", 2);
        final int hashedRuntimeId = BlockStateHasher.hash(hashedCanonicalTag);
        hashedCanonicalTag.putInt("network_id", hashedRuntimeId);
        final CompoundTag customCanonicalTag = blockState("custom:runtime", 3);
        final int customRuntimeId = BlockStateHasher.hash(customCanonicalTag);
        customCanonicalTag.putInt("network_id", customRuntimeId);

        final Map<BedrockBlockState, Integer> mappings = new LinkedHashMap<>();
        mappings.put(BedrockBlockState.fromNbt(sequentialCanonicalTag), 5);
        mappings.put(BedrockBlockState.fromNbt(hashedCanonicalTag), hashedRuntimeId);
        mappings.put(BedrockBlockState.fromNbt(customCanonicalTag), customRuntimeId);
        final BlockStateRewriter rewriter = BlockStateRewriterTestFactory.create(mappings);

        final CompoundTag sequentialPersistent = dirtyCopy(sequentialCanonicalTag);
        final CompoundTag hashedPersistent = dirtyCopy(hashedCanonicalTag);
        final CompoundTag customPersistent = dirtyCopy(customCanonicalTag);
        final List<Tag> persistentEntries = List.of(sequentialPersistent, hashedPersistent, customPersistent);
        final BedrockDataPalette palette = new BedrockDataPalette(
                persistentEntries, BitArrayVersion.V2.createArray(4096));

        palette.resolvePersistentIds(tag -> rewriter.bedrockIdOwned((CompoundTag) tag));

        assertFalse(palette.usesPersistentIds());
        assertEquals(5, palette.idByIndex(0));
        assertEquals(hashedRuntimeId, palette.idByIndex(1));
        assertEquals(customRuntimeId, palette.idByIndex(2));
        for (Tag entry : persistentEntries) {
            final CompoundTag resolved = (CompoundTag) entry;
            assertFalse(resolved.getCompoundTag("states").contains("unknown"));
            assertNotNull(resolved.getIntTag("version"));
        }
    }

    private static CompoundTag dirtyCopy(final CompoundTag canonical) {
        final CompoundTag dirty = canonical.copy();
        dirty.getCompoundTag("states").put("unknown", new IntTag(99));
        final String name = dirty.getStringTag("name").getValue();
        final int separator = name.indexOf(':');
        dirty.putString("name", name.substring(0, separator + 1) + name.substring(separator + 1).toUpperCase());
        return dirty;
    }

    private static CompoundTag blockState(final String name, final int variant) {
        final CompoundTag states = new CompoundTag();
        states.put("variant", new IntTag(variant));
        return BlockStateRewriterOwnershipTest.blockState(name, states);
    }

}
