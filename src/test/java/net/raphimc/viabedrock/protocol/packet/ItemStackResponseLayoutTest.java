/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 */
package net.raphimc.viabedrock.protocol.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemStackResponseLayoutTest {

    @Test
    void netease860AndOfficial975KeepRequiredContainers() {
        assertFalse(ItemStackResponseLayout.usesOptionalContainerEntries(true, 860));
        assertFalse(ItemStackResponseLayout.usesOptionalContainerEntries(false, 975));
        assertTrue(ItemStackResponseLayout.usesOptionalContainerEntries(false, 2168));
        assertTrue(ItemStackResponseLayout.usesFullContainerName(true, 860));
        assertTrue(ItemStackResponseLayout.usesFilteredCustomName(true, 860));
        assertTrue(ItemStackResponseLayout.usesFilteredCustomName(false, 975));
    }

    @Test
    void readingNetease860OkEntryConsumesFullContainerNameAndCustomNames() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
            ItemStackResponseLayout.writeOkEntry(buffer, true, 860, -1, 60, 0, 12, 7);
            final ItemStackResponseLayout.DecodedResponse decoded = ItemStackResponseLayout.decode(buffer, true, 860);
            assertFalse(buffer.isReadable());
            assertFalse(decoded.anyRejected());
            assertEquals(1, decoded.entries().size());
            final ItemStackResponseLayout.DecodedEntry entry = decoded.entries().get(0);
            assertTrue(entry.ok());
            assertEquals(-1, entry.requestId());
            assertEquals(1, entry.containers().size());
            assertEquals(ContainerEnumName.CreatedOutputContainer, entry.containers().get(0).name());
            final ItemStackResponseLayout.DecodedSlot slot = entry.containers().get(0).slots().get(0);
            assertEquals(0, slot.slot());
            assertEquals(12, slot.count());
            assertEquals(7, slot.stackNetworkId());
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingNetease860As2168ReadsPastThePacket() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
            ItemStackResponseLayout.writeOkEntry(buffer, true, 860, -1, 60, 0, 12, 7);
            ItemStackResponseLayout.skip(buffer, false, 2168);
            assertTrue(buffer.isReadable(), "2168 optional booleans must desynchronize a 860 ITEM_STACK_RESPONSE");
        } finally {
            buffer.release();
        }
    }

    @Test
    void official975RejectedEntryHasNoContainerArray() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
            ItemStackResponseLayout.writeRejectedEntry(buffer, false, 975, -3);
            final ItemStackResponseLayout.DecodedResponse decoded = ItemStackResponseLayout.skip(buffer, false, 975);
            assertFalse(buffer.isReadable());
            assertTrue(decoded.anyRejected());
            assertEquals(1, decoded.requestIds().length);
            assertEquals(-3, decoded.requestIds()[0]);
        } finally {
            buffer.release();
        }
    }

    @Test
    void protocol2168RejectedEntryStillHasOptionalBooleans() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1);
            ItemStackResponseLayout.writeRejectedEntry(buffer, false, 2168, -5);
            ItemStackResponseLayout.skip(buffer, false, 2168);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

}
