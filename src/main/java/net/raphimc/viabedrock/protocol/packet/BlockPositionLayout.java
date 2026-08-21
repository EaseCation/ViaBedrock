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

/**
 * Wire-layout helpers for Bedrock {@code BlockVector3}.
 * <p>
 * Official Bedrock / Nukkit-MOT protocol 944+ ({@code V1_26_10}) encode Y as a
 * zigzag varint. NetEase 860 still uses the pre-944 layout:
 * {@code zigzag X, unsigned-varint Y, zigzag Z}. Writing three zigzag varints
 * into USE_ITEM / CONTAINER_OPEN on 860 turns Y=64 into Y=32, so chests and
 * right-click block use land on air.
 * <p>
 * Nukkit {@code getSignedBlockPosition()} / entity metadata POS always stay on
 * three zigzag varints on both 860 and official 975. Those packets must keep
 * using {@code SIGNED_BLOCK_POSITION}.
 */
public final class BlockPositionLayout {

    public static final int SIGNED_Y_PROTOCOL = 944;

    private BlockPositionLayout() {
    }

    public static boolean usesUnsignedY() {
        return usesUnsignedY(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesUnsignedY(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol > 0 && protocol < SIGNED_Y_PROTOCOL;
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}

