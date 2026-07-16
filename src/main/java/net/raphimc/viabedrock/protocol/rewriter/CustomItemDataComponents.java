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
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;

final class CustomItemDataComponents {

    static void applyMaxStackSize(final Item item, final Integer maxStackSize, final boolean fallbackOnly) {
        if (maxStackSize == null) {
            return;
        }
        final StructuredDataContainer data = item.dataContainer();
        if (fallbackOnly && (data.get(StructuredDataKey.MAX_STACK_SIZE) != null || data.hasEmpty(StructuredDataKey.MAX_STACK_SIZE))) {
            return;
        }
        data.set(StructuredDataKey.MAX_STACK_SIZE, maxStackSize);
    }

    private CustomItemDataComponents() {
    }

}
