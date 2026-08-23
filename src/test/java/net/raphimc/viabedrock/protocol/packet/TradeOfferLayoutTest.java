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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeOfferLayoutTest {

    @Test
    void parseOffersKeepsMotNetIdsAndIngredientCounts() {
        final CompoundTag buyA = item("minecraft:emerald", 3);
        final CompoundTag buyB = item("minecraft:book", 1);
        final CompoundTag sell = item("minecraft:enchanted_book", 1);
        final CompoundTag recipe = new CompoundTag();
        recipe.putInt("netId", 0x20000001);
        recipe.putInt("uses", 2);
        recipe.putInt("maxUses", 12);
        recipe.putInt("rewardExp", 1);
        recipe.putInt("demand", 4);
        recipe.putFloat("priceMultiplierA", 0.05F);
        recipe.put("buyA", buyA);
        recipe.put("buyB", buyB);
        recipe.put("sell", sell);
        final ListTag<CompoundTag> recipes = new ListTag<>(CompoundTag.class);
        recipes.add(recipe);
        final CompoundTag offers = new CompoundTag();
        offers.put("Recipes", recipes);

        final List<TradeOfferLayout.Offer> parsed = TradeOfferLayout.parseOffers(offers);
        assertEquals(1, parsed.size());
        final TradeOfferLayout.Offer offer = parsed.get(0);
        assertEquals(0x20000001, offer.netId());
        assertEquals(3, offer.buyACount());
        assertEquals(1, offer.buyBCount());
        assertEquals(2, offer.uses());
        assertEquals(12, offer.maxUses());
        assertEquals(1, offer.rewardExp());
        assertEquals(4, offer.demand());
        assertEquals(0.05F, offer.priceMultiplier());
    }

    @Test
    void parseOffersSkipsNonTradeNetIds() {
        final CompoundTag recipe = new CompoundTag();
        recipe.putInt("netId", 42);
        recipe.put("sell", item("minecraft:stone", 1));
        final ListTag<CompoundTag> recipes = new ListTag<>(CompoundTag.class);
        recipes.add(recipe);
        final CompoundTag offers = new CompoundTag();
        offers.put("Recipes", recipes);

        assertTrue(TradeOfferLayout.parseOffers(offers).isEmpty());
    }

    private static CompoundTag item(final String name, final int count) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        tag.putByte("Count", (byte) count);
        tag.putShort("Damage", (short) 0);
        return tag;
    }
}
