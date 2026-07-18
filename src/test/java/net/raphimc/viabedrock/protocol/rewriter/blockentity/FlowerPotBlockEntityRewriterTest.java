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
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.StringTag;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriterTestFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlowerPotBlockEntityRewriterTest {

    @Test
    void modernPlantBlockResolutionDoesNotModifyOriginalNbt() {
        final CompoundTag canonicalStates = new CompoundTag();
        canonicalStates.put("growth", new IntTag(1));
        final CompoundTag canonicalTag = blockState("test:flower", canonicalStates);
        final BedrockBlockState canonical = BedrockBlockState.fromNbt(canonicalTag);
        final BlockStateRewriter rewriter = BlockStateRewriterTestFactory.create(Map.of(canonical, 73));

        final CompoundTag plantStates = new CompoundTag();
        final IntTag invalidGrowth = new IntTag(0);
        final IntTag unknown = new IntTag(99);
        plantStates.put("growth", invalidGrowth);
        plantStates.put("unknown", unknown);
        final CompoundTag plantBlock = blockState("test:FLOWER", plantStates);
        final CompoundTag flowerPotTag = new CompoundTag();
        flowerPotTag.put("PlantBlock", plantBlock);
        final CompoundTag snapshot = flowerPotTag.copy();
        final StringTag originalName = plantBlock.getStringTag("name");

        final int runtimeId = FlowerPotBlockEntityRewriter.resolvePlantBlockState(rewriter, plantBlock);

        assertEquals(73, runtimeId);
        assertEquals(snapshot, flowerPotTag);
        assertSame(plantBlock, flowerPotTag.get("PlantBlock"));
        assertSame(originalName, plantBlock.get("name"));
        assertSame(plantStates, plantBlock.get("states"));
        assertSame(invalidGrowth, plantStates.get("growth"));
        assertSame(unknown, plantStates.get("unknown"));
        assertFalse(plantBlock.contains("version"));
    }

    private static CompoundTag blockState(final String name, final CompoundTag states) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.put("states", states);
        return tag;
    }

}
