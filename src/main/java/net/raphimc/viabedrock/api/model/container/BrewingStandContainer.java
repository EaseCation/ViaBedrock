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
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

public class BrewingStandContainer extends Container {

    public BrewingStandContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        super(user, containerId, ContainerType.BREWING_STAND, title, position, 5, CustomBlockTags.BREWING_STAND);
    }

    @Override
    public int javaSlot(final int slot) {
        return switch (slot) {
            case 0 -> 3; // ingredient -> Java slot 3
            case 1 -> 0; // potion 1 -> Java slot 0
            case 2 -> 1; // potion 2 -> Java slot 1
            case 3 -> 2; // potion 3 -> Java slot 2
            default -> slot; // fuel (4) -> Java slot 4
        };
    }

    @Override
    public Item[] getJavaItems() {
        final ItemRewriter itemRewriter = this.user.get(ItemRewriter.class);
        final Item[] javaItems = new Item[5];
        javaItems[0] = itemRewriter.javaItem(this.items[1]); // potion 1
        javaItems[1] = itemRewriter.javaItem(this.items[2]); // potion 2
        javaItems[2] = itemRewriter.javaItem(this.items[3]); // potion 3
        javaItems[3] = itemRewriter.javaItem(this.items[0]); // ingredient
        javaItems[4] = itemRewriter.javaItem(this.items[4]); // fuel
        return javaItems;
    }

}
