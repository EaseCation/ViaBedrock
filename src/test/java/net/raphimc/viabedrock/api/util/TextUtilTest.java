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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextUtilTest {

    @Test
    void nametagAlwaysShownTreatsMinusOneAsAlwaysOn() {
        assertTrue(TextUtil.nametagAlwaysShown(null));
        assertTrue(TextUtil.nametagAlwaysShown((byte) 1));
        assertTrue(TextUtil.nametagAlwaysShown((byte) -1));
        assertTrue(TextUtil.nametagAlwaysShown(255));
        assertFalse(TextUtil.nametagAlwaysShown((byte) 0));
    }

    @Test
    void stringToTextComponentKeepsNametagNewlines() {
        final String json = TextUtil.textComponentToJson(TextUtil.stringToTextComponent("Title\nSubtitle"));
        assertTrue(json.contains("Title") && json.contains("Subtitle"), json);
        assertTrue(json.contains("\\n") || json.contains("\n"), json);
    }

    @Test
    void nametagValuePrefersRawTextOverName() {
        assertEquals("raw\nline", TextUtil.nametagValue("name", "raw\nline"));
        assertEquals("name", TextUtil.nametagValue("name", ""));
        assertEquals("name", TextUtil.nametagValue("name", "§r"));
        assertEquals("raw", TextUtil.nametagValue(null, "raw"));
        assertNull(TextUtil.nametagValue("§r", "§l"));
        assertNull(TextUtil.nametagValue(null, null));
    }

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
