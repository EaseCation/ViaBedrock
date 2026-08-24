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
package net.raphimc.viabedrock.experimental;

import net.raphimc.viabedrock.experimental.inventory.ItemUseHandContext;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExperimentalFeaturesClickBlockActionsTest {

    @Test
    void neteaseClickBlockOmitsPredictedSlotDelta() {
        final ItemUseHandContext hand = stoneHand();
        assertNull(ExperimentalFeatures.predictedClickBlockActions(hand, null, true));
    }

    @Test
    void officialClickBlockKeepsPredictedSlotDelta() {
        final ItemUseHandContext hand = stoneHand();
        final List<InventoryActionData> actions = ExperimentalFeatures.predictedClickBlockActions(hand, null, false);
        assertNotNull(actions);
        assertEquals(1, actions.size());
        assertEquals(hand.item().amount() - 1, actions.get(0).toItem().amount());
    }

    private static ItemUseHandContext stoneHand() {
        return new ItemUseHandContext(
                InteractionHand.MAIN_HAND,
                (byte) 0,
                0,
                0,
                new BedrockItem(1, (short) 0, (byte) 64, null, new String[0], new String[0], 0L, 1, null));
    }
}
