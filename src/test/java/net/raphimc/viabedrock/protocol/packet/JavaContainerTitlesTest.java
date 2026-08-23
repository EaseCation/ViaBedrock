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

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaContainerTitlesTest {

    @Test
    void enderChestUsesJavaLangKeyNotBedrockTag() {
        assertEquals("container.enderchest", JavaContainerTitles.key("ender_chest", ContainerType.CONTAINER));
        assertEquals("container.shulkerBox", JavaContainerTitles.key("shulker_box", ContainerType.CONTAINER));
        assertEquals("container.crafting", JavaContainerTitles.key("crafting_table", ContainerType.WORKBENCH));
        assertEquals("container.repair", JavaContainerTitles.key("anvil", ContainerType.ANVIL));
        assertEquals("container.grindstone_title", JavaContainerTitles.key("grindstone", ContainerType.GRINDSTONE));
        assertEquals("container.upgrade", JavaContainerTitles.key("smithing_table", ContainerType.SMITHING_TABLE));
        assertEquals("container.blast_furnace", JavaContainerTitles.key("blast_furnace", ContainerType.BLAST_FURNACE));
        assertEquals("container.hopper", JavaContainerTitles.key("hopper", ContainerType.HOPPER));
    }

    @Test
    void dummyAirFallsBackToContainerType() {
        assertEquals("container.chest", JavaContainerTitles.key("air", ContainerType.CONTAINER));
        assertEquals("container.chest", JavaContainerTitles.key(null, ContainerType.CONTAINER));
        assertEquals("container.hopper", JavaContainerTitles.key(null, ContainerType.HOPPER));
    }
}
