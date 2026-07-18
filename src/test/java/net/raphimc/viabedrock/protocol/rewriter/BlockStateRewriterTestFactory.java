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

import net.raphimc.viabedrock.api.chunk.blockstate.BlockStateUpgrader;
import net.raphimc.viabedrock.api.model.BedrockBlockState;

import java.util.Map;

public final class BlockStateRewriterTestFactory {

    private static final BlockStateUpgrader BLOCK_STATE_UPGRADER = new BlockStateUpgrader();

    private BlockStateRewriterTestFactory() {
    }

    public static BlockStateRewriter create(final Map<BedrockBlockState, Integer> blockStateMappings) {
        return new BlockStateRewriter(BLOCK_STATE_UPGRADER, blockStateMappings);
    }

}
