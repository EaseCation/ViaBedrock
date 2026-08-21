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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Trailer helpers for Bedrock {@code LEVEL_SOUND_EVENT} (packet 0x7B / 123).
 * <p>
 * Official Bedrock 1.21.90+ / protocol 974+ and Nukkit-MOT's matching branch append
 * an optional {@code fireAtPosition} vector after the little-endian entity unique id.
 * NetEase / Nukkit-MOT protocol 860 still stops after {@code entityUniqueId}
 * ({@code protocol >= 785}). Treating that packet as 974+ tries to read an optional
 * boolean past the 42-byte payload and aborts the Java connection with
 * {@code SOUND}/{@code OptionalType} extra-data.
 */
public final class LevelSoundEventLayout {

    public static final int ENTITY_UNIQUE_ID_PROTOCOL = 785;
    public static final int FIRE_AT_POSITION_PROTOCOL = 974;

    private LevelSoundEventLayout() {
    }

    public static boolean usesEntityUniqueId() {
        return usesEntityUniqueId(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesEntityUniqueId(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= ENTITY_UNIQUE_ID_PROTOCOL;
    }

    public static boolean usesFireAtPosition() {
        return usesFireAtPosition(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesFireAtPosition(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= FIRE_AT_POSITION_PROTOCOL;
    }

    public static void skipTrailer(final PacketWrapper wrapper) {
        if (usesEntityUniqueId()) {
            wrapper.read(BedrockTypes.LONG_LE);
        }
        if (usesFireAtPosition()) {
            wrapper.read(BedrockTypes.OPTIONAL_POSITION_3F);
        }
    }

    public static void writeTrailer(final PacketWrapper wrapper, final long entityUniqueId) {
        if (usesEntityUniqueId()) {
            wrapper.write(BedrockTypes.LONG_LE, entityUniqueId);
        }
        if (usesFireAtPosition()) {
            wrapper.write(BedrockTypes.OPTIONAL_POSITION_3F, null);
        }
    }

    public static void skipTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesEntityUniqueId(emulateNetEase, protocol)) {
            BedrockTypes.LONG_LE.read(buffer);
        }
        if (usesFireAtPosition(emulateNetEase, protocol)) {
            BedrockTypes.OPTIONAL_POSITION_3F.read(buffer);
        }
    }

    public static void writeTrailer(final ByteBuf buffer, final long entityUniqueId,
                                    final Position3f fireAtPosition, final boolean emulateNetEase, final int protocol) {
        if (usesEntityUniqueId(emulateNetEase, protocol)) {
            BedrockTypes.LONG_LE.write(buffer, entityUniqueId);
        }
        if (usesFireAtPosition(emulateNetEase, protocol)) {
            BedrockTypes.OPTIONAL_POSITION_3F.write(buffer, fireAtPosition);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
