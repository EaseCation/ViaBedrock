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
package net.raphimc.viabedrock.protocol.model;

import com.viaversion.viaversion.libs.fastutil.ints.Int2IntMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.List;
import java.util.Objects;

public record EntityProperties(
        Int2IntMap intProperties,
        Int2ObjectMap<Float> floatProperties,
        String entityIdentifier,
        List<EntityPropertyValue> namedProperties
) {

    public EntityProperties(final Int2IntMap intProperties, final Int2ObjectMap<Float> floatProperties) {
        this(intProperties, floatProperties, null, List.of());
    }

    public EntityProperties {
        Objects.requireNonNull(intProperties, "intProperties");
        Objects.requireNonNull(floatProperties, "floatProperties");
        namedProperties = namedProperties == null ? List.of() : List.copyOf(namedProperties);
    }

    public static EntityProperties empty() {
        return new EntityProperties(new Int2IntOpenHashMap(), new Int2ObjectOpenHashMap<>());
    }

    public EntityProperties withNamedProperties(final String entityIdentifier, final List<EntityPropertyValue> namedProperties) {
        return new EntityProperties(this.intProperties, this.floatProperties, entityIdentifier, namedProperties);
    }

    public EntityPropertyValue namedProperty(final String name) {
        for (final EntityPropertyValue property : this.namedProperties) {
            if (property.name().equals(name)) {
                return property;
            }
        }
        return null;
    }
}
