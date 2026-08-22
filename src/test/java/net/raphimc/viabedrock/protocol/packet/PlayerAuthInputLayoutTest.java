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
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAuthInputLayoutTest {

    @Test
    void extraFlagsMatchNukkitThresholds() {
        assertEquals(0, PlayerAuthInputLayout.extraInputFlags(false, 860));
        assertEquals(0, PlayerAuthInputLayout.extraInputFlags(true, 685));
        assertEquals(1, PlayerAuthInputLayout.extraInputFlags(true, 686));
        assertEquals(2, PlayerAuthInputLayout.extraInputFlags(true, 819));
        assertEquals(2, PlayerAuthInputLayout.extraInputFlags(true, 860));
        assertFalse(PlayerAuthInputLayout.usesCameraDeparted(false, 860));
        assertFalse(PlayerAuthInputLayout.usesCameraDeparted(false, 975));
        assertFalse(PlayerAuthInputLayout.usesCameraDeparted(true, 421));
        assertTrue(PlayerAuthInputLayout.usesCameraDeparted(true, 422));
        assertTrue(PlayerAuthInputLayout.usesCameraDeparted(true, 860));
    }

    @Test
    void netease860KeepsUpLeftAndShiftsTailByTwo() {
        assertEquals(14, PlayerAuthInputLayout.wireBit(PlayerAuthInputPacket_InputData.UpLeft.getValue(), 2));
        assertEquals(15, PlayerAuthInputLayout.wireBit(PlayerAuthInputPacket_InputData.UpRight.getValue(), 2));
        assertEquals(37, PlayerAuthInputLayout.wireBit(PlayerAuthInputPacket_InputData.HandledTeleport.getValue(), 2));
        assertEquals(35, PlayerAuthInputLayout.wireBit(PlayerAuthInputPacket_InputData.PerformBlockActions.getValue(), 2));
        assertEquals(46, PlayerAuthInputLayout.wireBit(PlayerAuthInputPacket_InputData.ClientAckServerData.getValue(), 2));
        assertEquals(50, PlayerAuthInputLayout.wireBit(PlayerAuthInputPacket_InputData.BlockBreakingDelayEnabled.getValue(), 2));
        assertEquals(52, PlayerAuthInputLayout.wireBit(PlayerAuthInputPacket_InputData.VerticalCollision.getValue(), 2));
        assertEquals(66, PlayerAuthInputLayout.wireBit(PlayerAuthInputPacket_InputData.SneakCurrentRaw.getValue(), 2));
    }

    @Test
    void joinTeleportDoesNotSetPerformBlockActionsOnNetease860() {
        final Set<PlayerAuthInputPacket_InputData> input = EnumSet.of(
                PlayerAuthInputPacket_InputData.HandledTeleport,
                PlayerAuthInputPacket_InputData.BlockBreakingDelayEnabled,
                PlayerAuthInputPacket_InputData.VerticalCollision
        );
        final BigInteger wire = PlayerAuthInputLayout.encodeBitmask(input, true, 860);
        assertTrue(wire.testBit(37), "HandledTeleport must stay on vanilla bit 37");
        assertFalse(wire.testBit(35), "HandledTeleport must not collide with PERFORM_BLOCK_ACTIONS");
        assertTrue(wire.testBit(50), "BlockBreakingDelayEnabled must occupy wire bit 50");
        assertTrue(wire.testBit(52), "VerticalCollision must occupy wire bit 52");
        assertTrue(wire.bitLength() <= 64, "NetEase 860 flags must fit in Nukkit's unsigned varlong");

        final Set<PlayerAuthInputPacket_InputData> decoded = PlayerAuthInputLayout.decodeToInputData(wire, 2);
        assertEquals(input, decoded);
        assertFalse(decoded.contains(PlayerAuthInputPacket_InputData.PerformBlockActions));
        assertTrue(unsignedVarLongByteLength(wire) <= 10);
    }

    @Test
    void vanillaBitmaskDoesNotShiftTailFlags() {
        final Set<PlayerAuthInputPacket_InputData> input = EnumSet.of(
                PlayerAuthInputPacket_InputData.HandledTeleport,
                PlayerAuthInputPacket_InputData.BlockBreakingDelayEnabled
        );
        final BigInteger wire = PlayerAuthInputLayout.encodeBitmask(input, false, 860);
        assertTrue(wire.testBit(37));
        assertTrue(wire.testBit(48));
        assertFalse(wire.testBit(50));
        assertEquals(input, PlayerAuthInputLayout.decodeToInputData(wire, 0));
    }

    @Test
    void previousBrokenMappingWouldHaveSetPerformBlockActions() {
        final int brokenWireBit = 37 - 2;
        assertEquals(PlayerAuthInputLayout.PERFORM_BLOCK_ACTIONS_ORDINAL, brokenWireBit);
        final BigInteger broken = BigInteger.ZERO.setBit(brokenWireBit);
        final Set<Integer> decoded = PlayerAuthInputLayout.decodeToVanillaOrdinals(broken, 2);
        assertTrue(decoded.contains(PlayerAuthInputLayout.PERFORM_BLOCK_ACTIONS_ORDINAL));
        assertFalse(decoded.contains(PlayerAuthInputLayout.HANDLED_TELEPORT_ORDINAL));
    }

    @Test
    void netease860OmitsFlagsThatWouldOverflowThe64BitMask() {
        final Set<PlayerAuthInputPacket_InputData> input = EnumSet.of(
                PlayerAuthInputPacket_InputData.SneakCurrentRaw,
                PlayerAuthInputPacket_InputData.SneakPressedRaw,
                PlayerAuthInputPacket_InputData.JumpCurrentRaw
        );
        final BigInteger wire = PlayerAuthInputLayout.encodeBitmask(input, true, 860);
        assertEquals(63, PlayerAuthInputLayout.wireBit(PlayerAuthInputPacket_InputData.JumpCurrentRaw.getValue(), 2));
        assertTrue(wire.testBit(63));
        assertFalse(wire.testBit(64));
        assertFalse(wire.testBit(65));
        assertFalse(wire.testBit(66));
        assertEquals(EnumSet.of(PlayerAuthInputPacket_InputData.JumpCurrentRaw), PlayerAuthInputLayout.decodeToInputData(wire, 2));
        assertTrue(unsignedVarLongByteLength(wire) <= 10);
    }

    private static int unsignedVarLongByteLength(final BigInteger value) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.UNSIGNED_VAR_BIG_INTEGER.write(buffer, value);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }
}
