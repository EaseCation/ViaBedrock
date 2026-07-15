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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.minecraft.codec.CodecContext;
import com.viaversion.viaversion.api.minecraft.item.HashedItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.codec.CodecRegistryContext;
import com.viaversion.viaversion.codec.hash.HashFunction;
import com.viaversion.viaversion.codec.hash.HashOps;
import com.viaversion.viaversion.data.item.ItemHasherBase;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;

import java.util.Map;

final class JavaItemHasher {

    private final CodecContext context;

    JavaItemHasher(final Protocol<?, ?, ?, ?> protocol, final CodecContext.RegistryAccess delegate,
                   final CompoundTag javaRegistries) {
        this.context = new CodecRegistryContext(protocol, new JavaRegistryAccess(delegate, javaRegistries), true);
    }

    static JavaItemHasher forConnection(final UserConnection user) {
        final Protocol<?, ?, ?, ?> javaProtocol = Via.getManager().getProtocolManager()
                .getProtocol(ProtocolConstants.JAVA_PROTOCOL_CLASS);
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        if (javaProtocol == null || gameSession == null) {
            return null;
        }

        return new JavaItemHasher(
                javaProtocol,
                CodecContext.RegistryAccess.of(javaProtocol),
                gameSession.getJavaRegistries());
    }

    HashedItem toHashedItem(final Item item) {
        return ItemHasherBase.toHashedItem(new HashOps(this.context, HashFunction.CRC32C), item);
    }

    private record JavaRegistryAccess(CodecContext.RegistryAccess delegate,
                                      CompoundTag javaRegistries) implements CodecContext.RegistryAccess {

        @Override
        public Key item(final int id) {
            return this.delegate.item(id);
        }

        @Override
        public Key attributeModifier(final int id) {
            return this.delegate.attributeModifier(id);
        }

        @Override
        public Key dataComponentType(final int id) {
            return this.delegate.dataComponentType(id);
        }

        @Override
        public Key entity(final int id) {
            return this.delegate.entity(id);
        }

        @Override
        public Key blockEntity(final int id) {
            return this.delegate.blockEntity(id);
        }

        @Override
        public Key sound(final int id) {
            return this.delegate.sound(id);
        }

        @Override
        public Key key(final MappingData.MappingType mappingType, final int id) {
            return this.delegate.key(mappingType, id);
        }

        @Override
        public int id(final MappingData.MappingType mappingType, final String identifier) {
            return this.delegate.id(mappingType, identifier);
        }

        @Override
        public Key registryKey(final String registry, final int id) {
            final CompoundTag entries = this.javaRegistries.getCompoundTag(Key.namespaced(registry));
            if (entries != null && id >= 0 && id < entries.size()) {
                int index = 0;
                for (final Map.Entry<String, Tag> entry : entries.entrySet()) {
                    if (index++ == id) {
                        return Key.of(entry.getKey());
                    }
                }
            }

            return Key.of("viabedrock", "unknown/" + Key.stripNamespace(registry) + "/" + id);
        }

        @Override
        public CodecContext.RegistryAccess withMapped(final boolean mapped) {
            return new JavaRegistryAccess(this.delegate.withMapped(mapped), this.javaRegistries);
        }
    }
}
