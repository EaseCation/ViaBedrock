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

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.util.EnumUtil;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wire-layout helpers for Bedrock {@code PLAYER_AUTH_INPUT} (packet 0x90 / 144).
 * <p>
 * Official Bedrock and Nukkit-MOT still encode input flags as a 64-bit unsigned varlong
 * before protocol 2168. NetEase inserts extra unused bits immediately before
 * {@code ClientAckServerData} (ordinal 44): protocol &gt;= 686 adds 1 bit, protocol
 * &gt;= 819 (including NetEase 860) adds 2 bits. Nukkit keeps wire bits 0-43 on their
 * vanilla ordinals, skips the extra NetEase bits, and maps {@code wire >= 44 + extra}
 * back to {@code ordinal = wire - extra}. Encoding therefore leaves flags {@code < 44}
 * alone and writes flags {@code >= 44} at {@code value + extra}.
 * <p>
 * Nukkit only inspects bits 0-63 and rejects unsigned varlongs longer than 10 bytes, so
 * NetEase flags that would land on bit 64+ (for example {@code SneakCurrentRaw}) are omitted.
 * <p>
 * The previous mapping dropped bits 14/15 and shifted 16-43 down by 2. That turned
 * join-time {@code HandledTeleport(37)} into wire bit 35 ({@code PERFORM_BLOCK_ACTIONS}).
 * Nukkit then treated the remaining analog-move floats as a block-action payload and
 * failed the 65-byte prefixed batch with {@code Sent malformed packet}.
 */
public final class PlayerAuthInputLayout {

    public static final int RECEIVED_SERVER_DATA_ORDINAL = 44;
    public static final int PERFORM_BLOCK_ACTIONS_ORDINAL = 35;
    public static final int HANDLED_TELEPORT_ORDINAL = 37;
    public static final int TWO_EXTRA_FLAGS_PROTOCOL = 819;
    public static final int ONE_EXTRA_FLAG_PROTOCOL = 686;
    public static final int NUKKIT_FLAG_BIT_LIMIT = 64;

    private PlayerAuthInputLayout() {
    }

    public static BigInteger encodeBitmask(final Set<PlayerAuthInputPacket_InputData> inputData) {
        return encodeBitmask(inputData, emulateNetEase(), netEaseProtocol());
    }

    public static BigInteger encodeBitmask(final Set<PlayerAuthInputPacket_InputData> inputData,
                                           final boolean emulateNetEase, final int protocol) {
        final int extraFlags = extraInputFlags(emulateNetEase, protocol);
        if (extraFlags <= 0) {
            return EnumUtil.getBigBitmaskFromEnumSet(inputData, PlayerAuthInputPacket_InputData::getValue);
        }
        return bitmask(inputData, extraFlags);
    }

    public static int extraInputFlags(final boolean emulateNetEase, final int protocol) {
        if (!emulateNetEase) {
            return 0;
        }
        if (protocol >= TWO_EXTRA_FLAGS_PROTOCOL) {
            return 2;
        }
        if (protocol >= ONE_EXTRA_FLAG_PROTOCOL) {
            return 1;
        }
        return 0;
    }

    public static int wireBit(final int value, final int extraFlags) {
        if (extraFlags <= 0 || value < RECEIVED_SERVER_DATA_ORDINAL) {
            return value;
        }
        return value + extraFlags;
    }

    public static BigInteger bitmask(final Set<PlayerAuthInputPacket_InputData> inputData, final int extraFlags) {
        BigInteger bitmask = BigInteger.ZERO;
        for (final PlayerAuthInputPacket_InputData flag : inputData) {
            final int bit = wireBit(flag.getValue(), extraFlags);
            if (bit >= 0 && bit < NUKKIT_FLAG_BIT_LIMIT) {
                bitmask = bitmask.setBit(bit);
            }
        }
        return bitmask;
    }

    public static Set<Integer> decodeToVanillaOrdinals(final BigInteger wireBits, final int extraFlags) {
        final Set<Integer> ordinals = new TreeSet<>();
        final int firstShiftedOrdinal = RECEIVED_SERVER_DATA_ORDINAL + extraFlags;
        for (int i = 0; i < NUKKIT_FLAG_BIT_LIMIT; i++) {
            int offset = 0;
            if (extraFlags > 0 && i >= RECEIVED_SERVER_DATA_ORDINAL) {
                if (i < firstShiftedOrdinal) {
                    continue;
                }
                offset = -extraFlags;
            }
            if (wireBits.testBit(i)) {
                ordinals.add(i + offset);
            }
        }
        return ordinals;
    }

    public static Set<PlayerAuthInputPacket_InputData> decodeToInputData(final BigInteger wireBits, final int extraFlags) {
        final Set<PlayerAuthInputPacket_InputData> flags = EnumSet.noneOf(PlayerAuthInputPacket_InputData.class);
        for (final int ordinal : decodeToVanillaOrdinals(wireBits, extraFlags)) {
            final PlayerAuthInputPacket_InputData flag = PlayerAuthInputPacket_InputData.getByValue(ordinal);
            if (flag != null) {
                flags.add(flag);
            }
        }
        return flags;
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
