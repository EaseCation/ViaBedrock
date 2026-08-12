/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.custommapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CustomMappingAccessTest {

    @Test
    void resolvesCustomIdentifierFromProjectedJavaState() {
        final CustomMappingAccess.Builder builder = new CustomMappingAccess.Builder();
        builder.addBlockState(100, "easecation:cherry_leaves", 200, 0, 0, 15, 1.0F, CustomMappingAccess.BlockEntityRule.NONE);
        final CustomMappingAccess access = builder.build();

        assertEquals("easecation:cherry_leaves", access.identifierByJavaBlockStateId(200));
        assertNull(access.identifierByJavaBlockStateId(201));
    }

}
