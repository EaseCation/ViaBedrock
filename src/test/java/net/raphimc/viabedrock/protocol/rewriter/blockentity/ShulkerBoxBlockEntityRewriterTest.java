/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter.blockentity;

import com.viaversion.nbt.tag.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShulkerBoxBlockEntityRewriterTest {

    @Test
    void mapsBedrockFacingBytesToJavaFacingNames() {
        assertEquals("down", ShulkerBoxBlockEntityRewriter.javaFacingName(facingTag(0)));
        assertEquals("up", ShulkerBoxBlockEntityRewriter.javaFacingName(facingTag(1)));
        assertEquals("north", ShulkerBoxBlockEntityRewriter.javaFacingName(facingTag(2)));
        assertEquals("south", ShulkerBoxBlockEntityRewriter.javaFacingName(facingTag(3)));
        assertEquals("west", ShulkerBoxBlockEntityRewriter.javaFacingName(facingTag(4)));
        assertEquals("east", ShulkerBoxBlockEntityRewriter.javaFacingName(facingTag(5)));
        assertEquals("down", ShulkerBoxBlockEntityRewriter.javaFacingName(new CompoundTag()));
        assertEquals("down", ShulkerBoxBlockEntityRewriter.javaFacingName(facingTag(99)));
    }

    private static CompoundTag facingTag(final int facing) {
        final CompoundTag tag = new CompoundTag();
        tag.putByte("facing", (byte) facing);
        return tag;
    }

}
