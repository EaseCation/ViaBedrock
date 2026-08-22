/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextUtilTest {

    @Test
    void lastLineKeepsSingleLineNames() {
        assertEquals("[开发中]ReBlock", TextUtil.lastLine("[开发中]ReBlock"));
        assertNull(TextUtil.lastLine(null));
    }

    @Test
    void lastLineUsesTheBottomNametagRow() {
        assertEquals("[开发中]ReBlock 游玩总人数:0",
                TextUtil.lastLine("[开发中]ReBlock\n[开发中]ReBlock 游玩总人数:0"));
        assertEquals("游玩总人数:0",
                TextUtil.lastLine(TextUtil.trimBlankLines("[开发中]ReBlock\n游玩总人数:0\n")));
    }

    @Test
    void toSingleLineKeepsOrdinaryHudText() {
        assertEquals("[开发中]ReBlock", TextUtil.toSingleLine("[开发中]ReBlock"));
        assertNull(TextUtil.toSingleLine(null));
        assertEquals("", TextUtil.toSingleLine("\n\n"));
    }

    @Test
    void toSingleLineFlattensBossBarAndScoreboardTitles() {
        assertEquals("[开发中]ReBlock [开发中]ReBlock 游玩总人数:0",
                TextUtil.toSingleLine("[开发中]ReBlock\n[开发中]ReBlock 游玩总人数:0"));
        assertEquals("Boss 第一阶段 剩余 12s",
                TextUtil.toSingleLine("Boss\n第一阶段\n剩余 12s\n"));
        assertEquals("Lobby", TextUtil.toSingleLine("\nLobby\n"));
        assertEquals("a §rb", TextUtil.toSingleLine("a\n\n§rb"));
        assertEquals("a b", TextUtil.toSingleLine("a\n\n§r\nb"));
    }
}
