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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.NumberTag;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.data.PotionContents;
import com.viaversion.viaversion.api.minecraft.item.data.PotionEffect;
import com.viaversion.viaversion.api.minecraft.item.data.PotionEffectData;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.data.PotionEffects1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.data.Potions1_20_5;

import java.util.ArrayList;
import java.util.List;

final class LegacyItemTagRewriter {

    static void apply(final StructuredDataContainer data, final CompoundTag tag) {
        final PotionContents potionContents = potionContents(tag);
        if (potionContents != null) {
            data.set(StructuredDataKey.POTION_CONTENTS1_21_2, potionContents);
        }
    }

    static PotionContents potionContents(final CompoundTag tag) {
        final int potionId = Potions1_20_5.keyToId(tag.getString("Potion", ""));
        final NumberTag customColorTag = tag.getNumberTag("CustomPotionColor");
        final ListTag<CompoundTag> customEffectsTag = tag.getListTag("custom_potion_effects", CompoundTag.class);
        if (potionId == -1 && customColorTag == null && customEffectsTag == null) {
            return null;
        }

        final List<PotionEffect> customEffects = new ArrayList<>();
        if (customEffectsTag != null) {
            for (CompoundTag effectTag : customEffectsTag) {
                final int effectId = PotionEffects1_20_5.keyToId(effectTag.getString("id", ""));
                if (effectId != -1) {
                    customEffects.add(new PotionEffect(effectId, effectData(effectTag)));
                }
            }
        }

        return new PotionContents(
                potionId != -1 ? potionId : null,
                customColorTag != null ? customColorTag.asInt() : null,
                customEffects.toArray(PotionEffect[]::new)
        );
    }

    private static PotionEffectData effectData(final CompoundTag tag) {
        final CompoundTag hiddenEffectTag = tag.getCompoundTag("hidden_effect");
        return new PotionEffectData(
                tag.getInt("amplifier", 0),
                tag.getInt("duration", 0),
                tag.getBoolean("ambient", false),
                tag.getBoolean("show_particles", true),
                tag.getBoolean("show_icon", true),
                hiddenEffectTag != null ? effectData(hiddenEffectTag) : null
        );
    }

    private LegacyItemTagRewriter() {
    }

}
