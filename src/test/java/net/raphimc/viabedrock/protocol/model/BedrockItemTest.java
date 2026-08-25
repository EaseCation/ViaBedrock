/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockItemTest {

    @Test
    void amount128RemainsNonEmptyThroughCopy() {
        assertUnsignedAmount(128);
    }

    @Test
    void amount200RemainsNonEmptyThroughCopy() {
        assertUnsignedAmount(200);
    }

    @Test
    void amount255RemainsNonEmptyThroughCopy() {
        assertUnsignedAmount(255);
    }

    private static void assertUnsignedAmount(final int amount) {
        final BedrockItem item = new BedrockItem(355);
        item.setAmount(amount);

        assertEquals(amount, item.amount());
        assertFalse(item.isEmpty());
        assertEquals(amount, item.copy().amount());
        assertFalse(item.copy().isEmpty());
    }

    @Test
    void zeroAmountIsEmpty() {
        final BedrockItem item = new BedrockItem(355);
        item.setAmount(0);

        assertEquals(0, item.amount());
        assertTrue(item.isEmpty());
    }

    @Test
    void stackIdentityIncludesRestrictionsAndBlockingTicks() {
        final BedrockItem item = new BedrockItem(355, (short) 0, (byte) 1);
        item.setCanPlace(new String[]{"minecraft:stone"});
        item.setCanBreak(new String[]{"minecraft:oak_log"});
        item.setBlockingTicks(42L);

        assertFalse(item.isDifferent(item.copy()));

        final BedrockItem differentCanPlace = item.copy();
        differentCanPlace.setCanPlace(new String[]{"minecraft:dirt"});
        assertTrue(item.isDifferent(differentCanPlace));

        final BedrockItem differentCanBreak = item.copy();
        differentCanBreak.setCanBreak(new String[]{"minecraft:birch_log"});
        assertTrue(item.isDifferent(differentCanBreak));

        final BedrockItem differentBlockingTicks = item.copy();
        differentBlockingTicks.setBlockingTicks(43L);
        assertTrue(item.isDifferent(differentBlockingTicks));
    }
}
