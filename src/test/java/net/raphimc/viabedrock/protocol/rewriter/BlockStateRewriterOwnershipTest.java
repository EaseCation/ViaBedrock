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

import com.viaversion.nbt.tag.ByteTag;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.StringTag;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockStateRewriterOwnershipTest {

    @Test
    void borrowedInputRemainsDeeplyUnchangedAndMatchesOwnedResolution() {
        final CompoundTag canonicalStates = new CompoundTag();
        canonicalStates.put("enabled", new ByteTag((byte) 1));
        final BedrockBlockState canonical = BedrockBlockState.fromNbt(blockState("test:owned_block", canonicalStates));
        final BlockStateRewriter rewriter = BlockStateRewriterTestFactory.create(Map.of(canonical, 37));

        final CompoundTag borrowedStates = new CompoundTag();
        final ByteTag invalidEnabled = new ByteTag((byte) 0);
        final IntTag unknown = new IntTag(42);
        borrowedStates.put("enabled", invalidEnabled);
        borrowedStates.put("unknown", unknown);
        final CompoundTag borrowed = blockState("test:OWNED_BLOCK", borrowedStates);
        final CompoundTag snapshot = borrowed.copy();
        final StringTag originalName = borrowed.getStringTag("name");

        final int borrowedId = rewriter.bedrockId(borrowed);

        assertEquals(37, borrowedId);
        assertEquals(snapshot, borrowed);
        assertSame(originalName, borrowed.get("name"));
        assertSame(borrowedStates, borrowed.get("states"));
        assertSame(invalidEnabled, borrowedStates.get("enabled"));
        assertSame(unknown, borrowedStates.get("unknown"));
        assertFalse(borrowed.contains("version"));

        final CompoundTag owned = borrowed.copy();
        final int ownedId = rewriter.bedrockIdOwned(owned);

        assertEquals(borrowedId, ownedId);
        assertEquals("test:owned_block", owned.getStringTag("name").getValue());
        assertEquals((byte) 1, owned.getCompoundTag("states").getByteTag("enabled").asByte());
        assertFalse(owned.getCompoundTag("states").contains("unknown"));
        assertNotNull(owned.getIntTag("version"));
        assertEquals(snapshot, borrowed);
    }

    static CompoundTag blockState(final String name, final CompoundTag states) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.put("states", states);
        return tag;
    }

}
