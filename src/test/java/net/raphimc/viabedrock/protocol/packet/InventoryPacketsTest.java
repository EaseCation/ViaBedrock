/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryPacketsTest {

    @Test
    void blocksHotbarChangesUntilNetEaseOffhandRestoreCompletes() {
        assertTrue(InventoryPackets.shouldBlockNetEaseHotbarChange(true, true, false));
        assertTrue(InventoryPackets.shouldBlockNetEaseHotbarChange(true, false, true));
        assertTrue(InventoryPackets.shouldBlockNetEaseHotbarChange(true, true, true));
        assertFalse(InventoryPackets.shouldBlockNetEaseHotbarChange(true, false, false));
        assertFalse(InventoryPackets.shouldBlockNetEaseHotbarChange(false, true, true));
    }

}
