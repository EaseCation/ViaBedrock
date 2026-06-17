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
package net.raphimc.viabedrock.api.util;

import java.util.Set;

/**
 * Vanilla blocks whose destroy time is 0 (hardness == 0), i.e. they always break instantly for every
 * player regardless of tool / haste. The vanilla Java client breaks such blocks by sending only a single
 * {@code START_DESTROY_BLOCK} (no {@code STOP_DESTROY_BLOCK}); ViaBedrock must therefore complete the
 * Bedrock break in the START handler, otherwise the block is never broken on the Bedrock server and the
 * client prediction is reverted (see {@code ClientPlayerPackets}).
 *
 * <p>The set is keyed by the <b>Java</b> block identifier (without namespace, e.g. {@code wheat}) because
 * Java block names are stable across versions, unlike the Bedrock palette names. It mirrors the blocks for
 * which {@code Block#getHardness() == 0} on the EaseCation Nukkit server. Custom (mod) blocks are handled
 * separately via the {@code seconds_to_destroy} synced over the BedrockLoader custom-mapping channel.
 *
 * <p>Matching is exact, so an identifier present here but absent on a given version is simply a no-op
 * (never a false positive). Tool-accelerated instant breaking of non-zero-hardness blocks is intentionally
 * out of scope.
 */
public final class InstantBreakBlocks {

    private static final Set<String> VANILLA_INSTANT_BREAK = Set.of(
            // Crops / farmland plants
            "wheat", "carrots", "potatoes", "beetroots",
            "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
            "nether_wart", "torchflower_crop", "pitcher_crop", "sweet_berry_bush",
            // Saplings / propagules
            "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling", "acacia_sapling",
            "dark_oak_sapling", "cherry_sapling", "pale_oak_sapling", "mangrove_propagule", "bamboo_sapling",
            // Small flowers
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
            "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose",
            "torchflower", "closed_eyeblossom", "open_eyeblossom",
            // Tall flowers / double plants
            "sunflower", "lilac", "rose_bush", "peony", "pink_petals", "wildflowers",
            // Grass / ferns / bushes
            "short_grass", "grass", "fern", "tall_grass", "large_fern", "dead_bush", "bush", "firefly_bush",
            // Aquatic plants
            "seagrass", "tall_seagrass", "kelp", "kelp_plant", "lily_pad", "sea_pickle",
            // Nether / cave vegetation
            "crimson_roots", "warped_roots", "nether_sprouts", "crimson_fungus", "warped_fungus",
            "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant",
            "cave_vines", "cave_vines_plant", "hanging_roots", "spore_blossom", "small_dripleaf",
            "azalea", "flowering_azalea",
            // Mushrooms
            "brown_mushroom", "red_mushroom",
            // Sugar cane
            "sugar_cane",
            // Torches / lights
            "torch", "wall_torch", "soul_torch", "soul_wall_torch",
            "redstone_torch", "redstone_wall_torch", "redstone_wire",
            // Misc zero-hardness
            "tnt", "slime_block", "honey_block", "fire", "soul_fire", "frogspawn",
            "tripwire", "scaffolding", "flower_pot", "decorated_pot", "end_rod"
    );

    private InstantBreakBlocks() {
    }

    /**
     * @param javaIdentifier the Java block identifier without namespace (e.g. {@code wheat})
     * @return whether the vanilla block always breaks instantly (hardness 0)
     */
    public static boolean isVanillaInstantBreak(final String javaIdentifier) {
        return VANILLA_INSTANT_BREAK.contains(javaIdentifier);
    }

}
