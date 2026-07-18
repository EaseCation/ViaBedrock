/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.chunk.blockstate;

import com.viaversion.nbt.tag.ByteTag;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class BlockStateSanitizerTest {

    @Test
    void preservesValidStateTagsAndRemovesUnknownProperties() {
        final CompoundTag canonicalStates = new CompoundTag();
        canonicalStates.put("enabled", new ByteTag((byte) 1));
        final CompoundTag canonical = blockState("test:block", canonicalStates);
        final BlockStateSanitizer sanitizer = new BlockStateSanitizer(List.of(BedrockBlockState.fromNbt(canonical)));

        final ByteTag enabled = new ByteTag((byte) 1);
        final CompoundTag states = new CompoundTag();
        states.put("enabled", enabled);
        states.put("unknown", new IntTag(42));
        final CompoundTag input = blockState("test:block", states);

        sanitizer.sanitize(input);

        assertSame(enabled, states.get("enabled"));
        assertFalse(states.contains("unknown"));
        assertEquals(1, states.size());
    }

    @Test
    void correctsInvalidValuesAndFillsMissingKnownPropertiesInPlace() {
        final CompoundTag canonicalStates = new CompoundTag();
        canonicalStates.put("facing", new IntTag(2));
        canonicalStates.put("enabled", new ByteTag((byte) 1));
        final BlockStateSanitizer sanitizer = new BlockStateSanitizer(
                List.of(BedrockBlockState.fromNbt(blockState("test:block", canonicalStates))));

        final CompoundTag states = new CompoundTag();
        states.put("facing", new IntTag(99));
        final CompoundTag input = blockState("test:block", states);

        sanitizer.sanitize(input);

        assertSame(states, input.get("states"));
        assertEquals(2, states.getIntTag("facing").asInt());
        assertEquals((byte) 1, states.getByteTag("enabled").asByte());
        assertEquals(2, states.size());
    }

    private static CompoundTag blockState(final String name, final CompoundTag states) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.put("states", states);
        return tag;
    }
}
