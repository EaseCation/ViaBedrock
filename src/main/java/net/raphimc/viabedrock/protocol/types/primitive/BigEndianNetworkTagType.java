/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.types.primitive;

import com.viaversion.nbt.io.TagRegistry;
import com.viaversion.nbt.limiter.TagLimiter;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.api.io.BigEndianNetworkByteBufInputStream;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Reads a named compound encoded as MOT big-endian network NBT. */
public final class BigEndianNetworkTagType extends Type<CompoundTag> {

    public BigEndianNetworkTagType() {
        super("BigEndianNetworkCompoundTag", CompoundTag.class);
    }

    @Override
    public CompoundTag read(final ByteBuf buffer) {
        final byte id = buffer.readByte();
        if (id == 0) {
            return null;
        }

        try {
            // The root name is present in named NBT, although MOT sends it empty.
            BedrockTypes.STRING.read(buffer);
            final Tag tag = TagRegistry.read(id, new BigEndianNetworkByteBufInputStream(buffer), TagLimiter.noop(), 0);
            if (!(tag instanceof CompoundTag compoundTag)) {
                throw new IOException("MOT entity property root is not a compound tag");
            }
            return compoundTag;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void write(final ByteBuf buffer, final CompoundTag value) {
        throw new UnsupportedOperationException("MOT entity property registry is clientbound only");
    }
}
