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

import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;

/**
 * Trailer helpers for Bedrock inventory-transaction item-use payloads.
 * <p>
 * Official Bedrock / Nukkit-MOT protocol 944+ append {@code clientCooldownState}
 * after {@code clientInteractPrediction}. NetEase 860 still ends at the
 * predicted-result varint. Writing that extra byte into PlayerAuthInput's
 * PERFORM_ITEM_INTERACTION payload (or a standalone USE_ITEM transaction)
 * shifts later analog/camera/raw-move fields and Nukkit fails the batch as
 * {@code Unable to decode PlayerAuthInputPacket} / {@code Sent malformed packet}.
 */
public final class InventoryTransactionLayout {

    public static final int CLIENT_COOLDOWN_STATE_PROTOCOL = 944;

    private InventoryTransactionLayout() {
    }

    public static boolean usesClientCooldownState() {
        return usesClientCooldownState(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesClientCooldownState(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= CLIENT_COOLDOWN_STATE_PROTOCOL;
    }

    public static byte readClientCooldownState(final ByteBuf buffer) {
        return readClientCooldownState(buffer, emulateNetEase(), netEaseProtocol());
    }

    public static byte readClientCooldownState(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (!usesClientCooldownState(emulateNetEase, protocol)) {
            return 0;
        }
        return buffer.readByte();
    }

    public static void writeClientCooldownState(final ByteBuf buffer, final byte clientCooldownState) {
        writeClientCooldownState(buffer, clientCooldownState, emulateNetEase(), netEaseProtocol());
    }

    public static void writeClientCooldownState(final ByteBuf buffer, final byte clientCooldownState,
                                                final boolean emulateNetEase, final int protocol) {
        if (usesClientCooldownState(emulateNetEase, protocol)) {
            buffer.writeByte(clientCooldownState);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
