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
package net.raphimc.viabedrock.experimental.inventory;

import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseHandContextTest {

    @Test
    void resolvesMainHandToSelectedHotbarSlot() {
        final BedrockItem mainItem = new BedrockItem(1);
        final ItemUseHandContext context = ItemUseHandContext.create(
                InteractionHand.MAIN_HAND,
                (byte) 0,
                6,
                mainItem,
                (byte) 119,
                new BedrockItem(2)
        );

        assertEquals(InteractionHand.MAIN_HAND, context.hand());
        assertEquals(0, context.containerId());
        assertEquals(6, context.containerSlot());
        assertEquals(6, context.transactionHotbarSlot());
        assertSame(mainItem, context.item());
        assertTrue(context.isMainHand());
    }

    @Test
    void resolvesOffhandToPrivateProtocolMarker() {
        final BedrockItem offhandItem = new BedrockItem(2);
        final ItemUseHandContext context = ItemUseHandContext.create(
                InteractionHand.OFF_HAND,
                (byte) 0,
                6,
                new BedrockItem(1),
                (byte) 119,
                offhandItem
        );

        assertEquals(InteractionHand.OFF_HAND, context.hand());
        assertEquals(119, Byte.toUnsignedInt(context.containerId()));
        assertEquals(0, context.containerSlot());
        assertEquals(ItemUseHandContext.JAVA_OFFHAND_HOTBAR_SLOT, context.transactionHotbarSlot());
        assertSame(offhandItem, context.item());
        assertEquals(6, context.entityTransactionHotbarSlot(6));
        assertEquals(0, context.entityTransactionHotbarSlot(-4));
    }

}
