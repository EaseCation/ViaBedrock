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
package net.raphimc.viabedrock.experimental.types.inventory;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Reads a BedrockItem in "instance item" format used in CRAFTING_DATA recipe outputs.
 * This format differs from BedrockItemType in that it does NOT have a hasNetId boolean field.
 */
public class InstanceItemType extends Type<BedrockItem> {

    public static final InstanceItemType INSTANCE = new InstanceItemType();

    public InstanceItemType() {
        super(BedrockItem.class);
    }

    @Override
    public BedrockItem read(final ByteBuf buffer) {
        final int id = BedrockTypes.VAR_INT.read(buffer);
        if (id == 0) {
            return BedrockItem.empty();
        }

        final BedrockItem item = new BedrockItem(id);
        item.setAmount(buffer.readUnsignedShortLE());
        item.setData(BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
        item.setBlockRuntimeId(BedrockTypes.VAR_INT.read(buffer));

        final int userDataLength = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        if (userDataLength > 0) {
            final ByteBuf userData = buffer.readSlice(userDataLength);
            try {
                final short marker = userData.readShortLE();
                if (marker == 0) {
                    return item;
                } else if (marker != -1) {
                    throw new IllegalStateException("Expected -1 marker but got " + marker);
                }
                final byte version = userData.readByte();
                if (version == 1) {
                    item.setTag((CompoundTag) BedrockTypes.TAG_LE.read(userData));
                    item.setCanPlace(BedrockTypes.UTF8_STRING_ARRAY.read(userData));
                    item.setCanBreak(BedrockTypes.UTF8_STRING_ARRAY.read(userData));
                }
            } catch (IndexOutOfBoundsException ignored) {
            }
        }

        return item;
    }

    @Override
    public void write(final ByteBuf buffer, final BedrockItem value) {
        throw new UnsupportedOperationException();
    }

}
