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
package net.raphimc.viabedrock.protocol.types.model;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;

/**
 * Wire-id helpers for Bedrock {@code ContainerSlotType}.
 * <p>
 * Nukkit-MOT inserts a hole at {@code RECIPE_ITEMS} (17) on NetEase so every later
 * slot type is {@code id + 1} on the wire. Official Bedrock keeps the generated
 * {@link ContainerEnumName} values. Reading a NetEase 860 {@code DynamicContainer}
 * (wire 64) as official 63 would look like {@code RecipeFoodContainer} and drop
 * bundle / dynamic-container updates.
 */
public final class ContainerSlotTypeLayout {

    public static final int RECIPE_ITEMS_ID = ContainerEnumName.RecipeItemsContainer.getValue();

    private ContainerSlotTypeLayout() {
    }

    public static boolean usesNetEaseIdShift() {
        return usesNetEaseIdShift(emulateNetEase());
    }

    public static boolean usesNetEaseIdShift(final boolean emulateNetEase) {
        return emulateNetEase;
    }

    public static int toWire(final ContainerEnumName name) {
        return toWire(name, usesNetEaseIdShift());
    }

    public static int toWire(final ContainerEnumName name, final boolean netEaseShift) {
        if (name == null) {
            return 0;
        }
        final int id = name.getValue();
        if (netEaseShift && id >= RECIPE_ITEMS_ID) {
            return id + 1;
        }
        return id;
    }

    public static ContainerEnumName fromWire(final int wireId) {
        return fromWire(wireId, usesNetEaseIdShift());
    }

    public static ContainerEnumName fromWire(final int wireId, final boolean netEaseShift) {
        if (netEaseShift) {
            if (wireId == RECIPE_ITEMS_ID) {
                return null;
            }
            return ContainerEnumName.getByValue(wireId > RECIPE_ITEMS_ID ? wireId - 1 : wireId);
        }
        return ContainerEnumName.getByValue(wireId);
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig() != null && ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }
}

