/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.api.model.container;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

/**
 * Crafting table container. The 3x3 grid is stored in the HUD container (slots 32-40),
 * output is HUD slot 50. This container exposes 10 slots to Java (output + 9 grid).
 * Java slot layout for minecraft:crafting (46 total):
 *   0 = output, 1-9 = grid, 10-36 = main inventory, 37-45 = hotbar
 */
public class CraftingTableContainer extends Container {

    public CraftingTableContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        super(user, containerId, ContainerType.WORKBENCH, title, position, 10, CustomBlockTags.CRAFTING_TABLE);
    }

    @Override
    public byte javaContainerId() {
        return 1;
    }

    @Override
    public Item[] getJavaItems() {
        final ItemRewriter itemRewriter = this.user.get(ItemRewriter.class);
        final InventoryTracker tracker = this.user.get(InventoryTracker.class);
        final Container hudContainer = tracker.getHudContainer();
        final Container inventoryContainer = tracker.getInventoryContainer();

        // Java crafting window: 46 slots
        // slot 0 = output (HUD 50)
        // slots 1-9 = 3x3 grid (HUD 32-40)
        // slots 10-36 = main inventory (inventory 9-35)
        // slots 37-45 = hotbar (inventory 0-8)
        final Item[] javaItems = new Item[46];

        // Output
        javaItems[0] = itemRewriter.javaItem(hudContainer.getItem(50));

        // 3x3 grid
        for (int i = 0; i < 9; i++) {
            javaItems[1 + i] = itemRewriter.javaItem(hudContainer.getItem(32 + i));
        }

        // Main inventory (9-35)
        for (int i = 9; i <= 35; i++) {
            javaItems[1 + i] = itemRewriter.javaItem(inventoryContainer.getItem(i));
        }

        // Hotbar (0-8)
        for (int i = 0; i < 9; i++) {
            javaItems[37 + i] = itemRewriter.javaItem(inventoryContainer.getItem(i));
        }

        return javaItems;
    }

}
