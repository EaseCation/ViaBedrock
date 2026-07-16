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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.function.IntUnaryOperator;

final class JavaItemStackLimits {

    static final int UNSUPPORTED = 0;
    static final int MAX_SUPPORTED = 99;

    private JavaItemStackLimits() {
    }

    static Resolver forTracker(final InventoryTracker tracker) {
        return item -> resolve(item, tracker);
    }

    static int resolve(final BedrockItem bedrockItem, final InventoryTracker tracker) {
        if (bedrockItem == null || bedrockItem.isEmpty()) {
            return UNSUPPORTED;
        }

        try {
            final ItemRewriter itemRewriter = tracker.user().get(ItemRewriter.class);
            if (itemRewriter == null) {
                return UNSUPPORTED;
            }
            return resolve(itemRewriter.javaItem(bedrockItem.copy()));
        } catch (final RuntimeException e) {
            return UNSUPPORTED;
        }
    }

    static int resolve(final Item javaItem) {
        return resolve(
                javaItem,
                BedrockProtocol.MAPPINGS.getJavaItems().size(),
                BedrockProtocol.MAPPINGS::getJavaItemMaxStackSize
        );
    }

    static int resolve(final Item javaItem, final int itemCount, final IntUnaryOperator vanillaLimitLookup) {
        if (javaItem == null || javaItem.isEmpty()) {
            return UNSUPPORTED;
        }

        final Integer componentLimit = javaItem.dataContainer().get(StructuredDataKey.MAX_STACK_SIZE);
        if (componentLimit != null) {
            return isValid(componentLimit) ? componentLimit : UNSUPPORTED;
        }
        if (javaItem.dataContainer().hasEmpty(StructuredDataKey.MAX_STACK_SIZE)) {
            return 1;
        }

        final int identifier = javaItem.identifier();
        if (identifier < 0 || identifier >= itemCount) {
            return UNSUPPORTED;
        }
        try {
            final int vanillaLimit = vanillaLimitLookup.applyAsInt(identifier);
            return isValid(vanillaLimit) ? vanillaLimit : UNSUPPORTED;
        } catch (final RuntimeException e) {
            return UNSUPPORTED;
        }
    }

    private static boolean isValid(final int limit) {
        return limit >= 1 && limit <= MAX_SUPPORTED;
    }

    @FunctionalInterface
    interface Resolver {

        int maxStackSize(BedrockItem item);

    }

}
