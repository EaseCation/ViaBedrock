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
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.experimental.storage.CreativeContentCache;
import net.raphimc.viabedrock.experimental.types.inventory.InstanceItemType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire-layout helpers for Bedrock CREATIVE_CONTENT (packet 0x91 / 145).
 * <p>
 * Official 975 and NetEase 860 both write groups from protocol 776, then a
 * 1-based creative net-id plus an instance item for each entry. Nukkit 860
 * still uses the legacy item codec rather than NetworkItemStackDescriptor.
 */
public final class CreativeContentLayout {

    public static final int GROUPS_PROTOCOL = 776;

    private CreativeContentLayout() {
    }

    public static boolean usesGroups(final boolean emulateNetEase, final int protocol) {
        return protocol >= GROUPS_PROTOCOL;
    }

    /**
     * Nukkit writes CREATIVE_CONTENT entries with putSlot(..., true) / instance-item
     * layout: no stack-network-id boolean. Reading them with ItemRewriter.itemType()
     * consumes the next field as that boolean and desynchronizes the rest of the packet.
     */
    public static Type<BedrockItem> itemType() {
        return InstanceItemType.INSTANCE;
    }

    public static List<CreativeContentCache.Entry> read(final PacketWrapper wrapper, final Type<BedrockItem> itemType,
                                                        final boolean emulateNetEase, final int protocol) {
        if (usesGroups(emulateNetEase, protocol)) {
            final int groupCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            for (int i = 0; i < groupCount; i++) {
                skipGroup(wrapper, itemType, protocol);
            }
        }
        final int itemCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final List<CreativeContentCache.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            final int netId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            final BedrockItem item = wrapper.read(itemType);
            if (usesGroups(emulateNetEase, protocol)) {
                wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            }
            if (item != null && !item.isEmpty()) {
                entries.add(new CreativeContentCache.Entry(netId, item.copy()));
            }
        }
        return entries;
    }

    public static List<CreativeContentCache.Entry> read(final ByteBuf buffer, final Type<BedrockItem> itemType,
                                                        final boolean emulateNetEase, final int protocol) {
        if (usesGroups(emulateNetEase, protocol)) {
            final int groupCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            for (int i = 0; i < groupCount; i++) {
                skipGroup(buffer, itemType, protocol);
            }
        }
        final int itemCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        final List<CreativeContentCache.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            final int netId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            final BedrockItem item = itemType.read(buffer);
            if (usesGroups(emulateNetEase, protocol)) {
                BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            }
            if (item != null && !item.isEmpty()) {
                entries.add(new CreativeContentCache.Entry(netId, item.copy()));
            }
        }
        return entries;
    }

    public static void write(final ByteBuf buffer, final List<CreativeContentCache.Entry> entries,
                             final Type<BedrockItem> itemType, final boolean emulateNetEase, final int protocol) {
        if (usesGroups(emulateNetEase, protocol)) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0);
        }
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, entries.size());
        for (final CreativeContentCache.Entry entry : entries) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, entry.netId());
            itemType.write(buffer, entry.item());
            if (usesGroups(emulateNetEase, protocol)) {
                BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0);
            }
        }
    }

    private static void skipGroup(final PacketWrapper wrapper, final Type<BedrockItem> itemType, final int protocol) {
        if (protocol >= ItemStackRequestLayout.UNSIGNED_ACTION_TYPE_PROTOCOL) {
            wrapper.read(Types.BYTE);
        } else {
            wrapper.read(BedrockTypes.INT_LE);
        }
        wrapper.read(BedrockTypes.STRING);
        wrapper.read(itemType);
    }

    private static void skipGroup(final ByteBuf buffer, final Type<BedrockItem> itemType, final int protocol) {
        if (protocol >= ItemStackRequestLayout.UNSIGNED_ACTION_TYPE_PROTOCOL) {
            buffer.readByte();
        } else {
            BedrockTypes.INT_LE.read(buffer);
        }
        BedrockTypes.STRING.read(buffer);
        itemType.read(buffer);
    }
}
