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
package net.raphimc.viabedrock.protocol.packet;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;

/**
 * Bedrock CONTAINER_OPEN has no title string. ViaBedrock previously used
 * {@code "container." + bedrockBlockTag}, which shows raw keys such as
 * {@code container.ender_chest} on Java 1.21. These are the vanilla lang keys.
 */
public final class JavaContainerTitles {

    private JavaContainerTitles() {
    }

    public static String key(final String bedrockBlockTag, final ContainerType type) {
        if (bedrockBlockTag == null || bedrockBlockTag.isEmpty() || "air".equals(bedrockBlockTag)) {
            return keyForType(type);
        }
        return switch (bedrockBlockTag) {
            case "crafting_table" -> "container.crafting";
            case "anvil" -> "container.repair";
            case "brewing_stand" -> "container.brewing";
            case "enchanting_table" -> "container.enchant";
            case "ender_chest" -> "container.enderchest";
            case "shulker_box" -> "container.shulkerBox";
            case "blast_furnace" -> "container.blast_furnace";
            case "smoker" -> "container.smoker";
            case "grindstone" -> "container.grindstone_title";
            case "smithing_table" -> "container.upgrade";
            case "cartography_table" -> "container.cartography_table";
            case "stonecutter" -> "container.stonecutter";
            case "loom" -> "container.loom";
            case "crafter" -> "container.crafter";
            case "barrel" -> "container.barrel";
            case "beacon" -> "container.beacon";
            case "lectern" -> "container.lectern";
            case "hopper" -> "container.hopper";
            case "furnace" -> "container.furnace";
            case "chest", "trapped_chest" -> "container.chest";
            case "dispenser" -> "container.dispenser";
            case "dropper" -> "container.dropper";
            // Unmapped NetEase custom tags have no Java lang key. Falling back
            // to container.<tag> shows a raw translation key on JE 1.21.
            default -> keyForType(type);
        };
    }

    public static String keyForType(final ContainerType type) {
        if (type == null) {
            return "container.chest";
        }
        return switch (type) {
            case CONTAINER, MINECART_CHEST, CHEST_BOAT -> "container.chest";
            case HOPPER, MINECART_HOPPER -> "container.hopper";
            case DISPENSER -> "container.dispenser";
            case DROPPER -> "container.dropper";
            case FURNACE -> "container.furnace";
            case BLAST_FURNACE -> "container.blast_furnace";
            case SMOKER -> "container.smoker";
            case BREWING_STAND -> "container.brewing";
            case ANVIL -> "container.repair";
            case WORKBENCH -> "container.crafting";
            case ENCHANTMENT -> "container.enchant";
            case BEACON -> "container.beacon";
            case LOOM -> "container.loom";
            case LECTERN -> "container.lectern";
            case GRINDSTONE -> "container.grindstone_title";
            case STONECUTTER -> "container.stonecutter";
            case CARTOGRAPHY -> "container.cartography_table";
            case SMITHING_TABLE -> "container.upgrade";
            case CRAFTER -> "container.crafter";
            case INVENTORY -> "container.inventory";
            case HORSE -> "entity.minecraft.horse";
            default -> "container.chest";
        };
    }
}
