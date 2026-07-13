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

import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions;

import java.util.Map;

public final class BedrockArmorProtectionRegistry {

    private final ItemDefinitions itemDefinitions;
    private final Map<String, Integer> vanillaProtection;

    public BedrockArmorProtectionRegistry(final ItemDefinitions itemDefinitions, final Map<String, Integer> vanillaProtection) {
        this.itemDefinitions = itemDefinitions;
        this.vanillaProtection = vanillaProtection;
    }

    public int protection(final String bedrockIdentifier) {
        if (bedrockIdentifier == null) {
            return 0;
        }

        final ItemDefinitions.ItemDefinition itemDefinition = this.itemDefinitions.get(bedrockIdentifier);
        if (itemDefinition != null && itemDefinition.networkDefinition()) {
            return itemDefinition.armorProtection() != null ? itemDefinition.armorProtection() : 0;
        }
        return this.vanillaProtection.getOrDefault(bedrockIdentifier, 0);
    }

}
