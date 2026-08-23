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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Wire-layout helpers for Bedrock PLAYER_START_ITEM_COOLDOWN (packet 176).
 * <p>
 * Nukkit-MOT {@code PlayerStartItemCoolDownPacket.encode()} writes
 * {@code string itemCategory + varint coolDownDuration} with no protocol-version fork.
 * MOT currently emits {@code goat_horn} ({@code ItemGoatHorn}) and {@code shield}
 * ({@code Player.onBlock} when a shield is broken). Java 1.21.2+ cooldown packets
 * take an item Identifier plus remaining ticks; MOT categories without a
 * {@code :} are namespaced as {@code minecraft:<category>}.
 */
public final class PlayerStartItemCooldownLayout {

    private PlayerStartItemCooldownLayout() {
    }

    public static void write(final ByteBuf buffer, final String itemCategory, final int coolDownDuration) {
        BedrockTypes.STRING.write(buffer, itemCategory != null ? itemCategory : "");
        BedrockTypes.VAR_INT.writePrimitive(buffer, coolDownDuration);
    }

    public static Packet read(final ByteBuf buffer) {
        final String itemCategory = BedrockTypes.STRING.read(buffer);
        final int coolDownDuration = BedrockTypes.VAR_INT.readPrimitive(buffer);
        return new Packet(itemCategory, coolDownDuration);
    }

    public static Packet read(final PacketWrapper wrapper) {
        final String itemCategory = wrapper.read(BedrockTypes.STRING);
        final int coolDownDuration = wrapper.read(BedrockTypes.VAR_INT);
        return new Packet(itemCategory, coolDownDuration);
    }

    /**
     * Maps MOT's cooldown category onto a Java 1.21.2+ cooldown Identifier.
     * Empty categories have no Java counterpart and should be dropped.
     */
    public static String javaCooldownIdentifier(final String itemCategory) {
        if (itemCategory == null) {
            return null;
        }
        final String trimmed = itemCategory.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.indexOf(':') >= 0) {
            return trimmed;
        }
        return "minecraft:" + trimmed;
    }

    public record Packet(String itemCategory, int coolDownDuration) {
    }
}
