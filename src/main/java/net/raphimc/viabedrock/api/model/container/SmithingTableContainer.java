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
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

/**
 * MOT / Bedrock smithing slots are equipment, ingredient, template, result.
 * Java 1.20+ uses template, base, addition, result.
 */
public class SmithingTableContainer extends Container {

    public SmithingTableContainer(final UserConnection user, final byte containerId, final TextComponent title, final BlockPosition position) {
        super(user, containerId, ContainerType.SMITHING_TABLE, title, position, 4);
    }

    @Override
    public int javaSlot(final int slot) {
        return switch (slot) {
            case 0 -> 1; // equipment -> Java base
            case 1 -> 2; // ingredient -> Java addition
            case 2 -> 0; // template -> Java template
            default -> slot; // result stays 3
        };
    }

    @Override
    public int bedrockSlot(final int javaSlot) {
        return switch (javaSlot) {
            case 0 -> 2; // Java template -> Bedrock slot 2
            case 1 -> 0; // Java base -> Bedrock slot 0
            case 2 -> 1; // Java addition -> Bedrock slot 1
            default -> javaSlot; // result stays 3
        };
    }

    @Override
    public Item[] getJavaItems() {
        final ItemRewriter itemRewriter = this.user.get(ItemRewriter.class);
        final Item[] javaItems = new Item[4];
        javaItems[0] = itemRewriter.javaItem(this.items[2]); // template
        javaItems[1] = itemRewriter.javaItem(this.items[0]); // equipment
        javaItems[2] = itemRewriter.javaItem(this.items[1]); // ingredient
        javaItems[3] = itemRewriter.javaItem(this.items[3]); // result
        return javaItems;
    }
}
