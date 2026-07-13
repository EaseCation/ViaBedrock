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
package net.raphimc.viabedrock.protocol.rewriter;

import net.raphimc.viabedrock.protocol.model.BedrockItem;

import java.util.function.Function;
import java.util.function.ToIntFunction;

public final class BedrockArmorValueResolver {

    public static final int MAX_ARMOR_VALUE = 20;

    private final Function<BedrockItem, String> identifierResolver;
    private final ToIntFunction<String> protectionResolver;

    public BedrockArmorValueResolver(final Function<BedrockItem, String> identifierResolver, final ToIntFunction<String> protectionResolver) {
        this.identifierResolver = identifierResolver;
        this.protectionResolver = protectionResolver;
    }

    public int resolve(final BedrockItem[] armorItems) {
        long protection = 0;
        for (BedrockItem armorItem : armorItems) {
            final String identifier = this.identifierResolver.apply(armorItem);
            protection += Math.max(0, this.protectionResolver.applyAsInt(identifier));
            if (protection >= MAX_ARMOR_VALUE) {
                return MAX_ARMOR_VALUE;
            }
        }
        return (int) protection;
    }

}
