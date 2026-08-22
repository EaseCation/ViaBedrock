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
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wire layout for NetEase SyncSkin (packet 236 / 0xEC).
 * <p>
 * MOT {@code SyncSkinPacket.decode()} reads an unsigned-varint count, then
 * {@code {flag, uuid, string1} * count}, then {@code string2/3/4 * count}, then
 * the official {@code getSkin(protocol)} blob. For protocol 860 that skin blob
 * matches {@link net.raphimc.viabedrock.protocol.types.model.SkinType}:
 * LInt animation counts, string arm size / skin color, and the 465+/568+
 * boolean trailer. NukkitMaster echoes this packet to the sender after a
 * client skin change; ConfirmSkin remains the broadcast path for other players.
 */
public final class SyncSkinLayout {

    private SyncSkinLayout() {
    }

    public record Entry(boolean flag, UUID uuid, String string1, String string2, String string3, String string4) {
    }

    public record Packet(List<Entry> entries, SkinData skin) {
    }

    public static Packet readPacket(final PacketWrapper wrapper) {
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final boolean[] flags = new boolean[count];
        final UUID[] uuids = new UUID[count];
        final String[] string1 = new String[count];
        for (int i = 0; i < count; i++) {
            flags[i] = wrapper.read(Types.BOOLEAN);
            uuids[i] = wrapper.read(BedrockTypes.UUID);
            string1[i] = wrapper.read(BedrockTypes.STRING);
        }
        final String[] string2 = new String[count];
        for (int i = 0; i < count; i++) {
            string2[i] = wrapper.read(BedrockTypes.STRING);
        }
        final String[] string3 = new String[count];
        for (int i = 0; i < count; i++) {
            string3[i] = wrapper.read(BedrockTypes.STRING);
        }
        final String[] string4 = new String[count];
        for (int i = 0; i < count; i++) {
            string4[i] = wrapper.read(BedrockTypes.STRING);
        }
        final SkinData skin = wrapper.read(BedrockTypes.SKIN);
        final List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(flags[i], uuids[i], string1[i], string2[i], string3[i], string4[i]));
        }
        return new Packet(entries, skin);
    }

    public static Packet readPacket(final ByteBuf buffer) {
        final int count = BedrockTypes.UNSIGNED_VAR_INT.readPrimitive(buffer);
        final boolean[] flags = new boolean[count];
        final UUID[] uuids = new UUID[count];
        final String[] string1 = new String[count];
        for (int i = 0; i < count; i++) {
            flags[i] = buffer.readBoolean();
            uuids[i] = BedrockTypes.UUID.read(buffer);
            string1[i] = BedrockTypes.STRING.read(buffer);
        }
        final String[] string2 = new String[count];
        for (int i = 0; i < count; i++) {
            string2[i] = BedrockTypes.STRING.read(buffer);
        }
        final String[] string3 = new String[count];
        for (int i = 0; i < count; i++) {
            string3[i] = BedrockTypes.STRING.read(buffer);
        }
        final String[] string4 = new String[count];
        for (int i = 0; i < count; i++) {
            string4[i] = BedrockTypes.STRING.read(buffer);
        }
        final SkinData skin = BedrockTypes.SKIN.read(buffer);
        final List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(flags[i], uuids[i], string1[i], string2[i], string3[i], string4[i]));
        }
        return new Packet(entries, skin);
    }

    public static void writePacket(final ByteBuf buffer, final List<Entry> entries, final SkinData skin) {
        BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(buffer, entries.size());
        for (Entry entry : entries) {
            buffer.writeBoolean(entry.flag());
            BedrockTypes.UUID.write(buffer, entry.uuid());
            BedrockTypes.STRING.write(buffer, entry.string1() != null ? entry.string1() : "");
        }
        for (Entry entry : entries) {
            BedrockTypes.STRING.write(buffer, entry.string2() != null ? entry.string2() : "");
        }
        for (Entry entry : entries) {
            BedrockTypes.STRING.write(buffer, entry.string3() != null ? entry.string3() : "");
        }
        for (Entry entry : entries) {
            BedrockTypes.STRING.write(buffer, entry.string4() != null ? entry.string4() : "");
        }
        BedrockTypes.SKIN.write(buffer, skin);
    }
}
