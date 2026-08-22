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
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;

/**
 * Furnace / blast furnace / smoker container.
 * Bedrock and Java both use the same 3-slot layout: 0 = smelting input, 1 = fuel, 2 = result,
 * so no slot remapping is required ({@link #javaSlot(int)} stays identity).
 */
public class FurnaceContainer extends Container {

    public FurnaceContainer(final UserConnection user, final byte containerId, final ContainerType type, final TextComponent title, final BlockPosition position) {
        super(user, containerId, type, title, position, 3, blockTagFor(type));
    }

    private static String blockTagFor(final ContainerType type) {
        return switch (type) {
            case BLAST_FURNACE -> CustomBlockTags.BLAST_FURNACE;
            case SMOKER -> CustomBlockTags.SMOKER;
            default -> CustomBlockTags.FURNACE;
        };
    }


}
