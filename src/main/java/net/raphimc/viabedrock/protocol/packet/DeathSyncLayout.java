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
 * Wire-layout helpers for MOT death / immediate-respawn packets.
 * <p>
 * Nukkit-MOT {@code Player.kill()} (protocol 860) does not emit
 * {@code UPDATE_ATTRIBUTES(health=0)} or {@code ActorEvent.DEATH} for the local
 * player. After {@code PlayerDeathEvent} it writes:
 * <ol>
 *     <li>{@code DeathInfoPacket} (189) — string translation key + string[] parameters
 *     — only when {@code SHOW_DEATH_MESSAGES} is on and the death message is non-empty;</li>
 *     <li>{@code RespawnPacket} (45) with {@code STATE_SEARCHING_FOR_SPAWN=0};</li>
 *     <li>{@code SetHealthPacket} (42) with {@code UnsignedVarInt health = getMaxHealth()}
 *     only when {@code doImmediateRespawn} is true.</li>
 * </ol>
 * {@code RespawnProcessor} later replies to the client's
 * {@code STATE_CLIENT_READY_TO_SPAWN=2} with {@code STATE_READY_TO_SPAWN=1}.
 * Immediate-respawn never auto-calls {@code handleRespawnRequest()}; the Java
 * client still has to send {@code PERFORM_RESPAWN} / {@code ClientReadyToSpawn}.
 * <p>
 * Official 975+ may append fields after the 860 payload; callers must
 * {@link PacketLeftoverLayout#discardUnreadInput} after a successful read.
 */
public final class DeathSyncLayout {

    private DeathSyncLayout() {
    }

    public static void writeDeathInfo(final ByteBuf buffer, final String messageTranslationKey, final String[] messageParameters) {
        BedrockTypes.STRING.write(buffer, messageTranslationKey != null ? messageTranslationKey : "");
        final String[] parameters = messageParameters != null ? messageParameters : new String[0];
        BedrockTypes.STRING_ARRAY.write(buffer, parameters);
    }

    public static DeathInfo readDeathInfo(final PacketWrapper wrapper) {
        final String message = wrapper.read(BedrockTypes.STRING);
        final String[] parameters = wrapper.read(BedrockTypes.STRING_ARRAY);
        return new DeathInfo(message, parameters != null ? parameters : new String[0]);
    }

    public static void writeSetHealth(final ByteBuf buffer, final int health) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, Math.max(0, health));
    }

    public static int readSetHealth(final PacketWrapper wrapper) {
        return wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
    }

    /**
     * MOT {@code SetHealthPacket} on immediate respawn writes {@code getMaxHealth()}
     * while the player is still dead ({@code this.health = 0}). Values {@code >= 1}
     * therefore mean "start the Java respawn handshake", not "the player is alive".
     */
    public static boolean isImmediateRespawnHealth(final int health) {
        return health >= 1;
    }

    public record DeathInfo(String messageTranslationKey, String[] messageParameters) {
    }
}
