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
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe.RecipeIngredient;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
import net.raphimc.viabedrock.experimental.types.inventory.InstanceItemType;
import net.raphimc.viabedrock.experimental.types.inventory.RecipeIngredientType;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CraftingDataEntryType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class CraftingDataModule implements FeatureModule {

    @Override
    public void onStorageRegistration(final UserConnection user) {
        user.put(new RecipeRegistry(user));
    }

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.CRAFTING_DATA, null, wrapper -> {
            wrapper.cancel();

            final RecipeRegistry registry = wrapper.user().get(RecipeRegistry.class);

            try {
                readCraftingData(wrapper, registry);
            } catch (final Throwable e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error parsing CRAFTING_DATA packet", e);
                registry.clear();
            }
        });
    }

    private static void readCraftingData(final PacketWrapper wrapper, final RecipeRegistry registry) {
        final List<BedrockRecipe> parsedRecipes = new ArrayList<>();

        final int recipeCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

        for (int i = 0; i < recipeCount; i++) {
            final int rawType = wrapper.read(BedrockTypes.VAR_INT);
            final CraftingDataEntryType type = CraftingDataEntryType.getByValue(rawType);

            if (type == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown CraftingDataEntryType: " + rawType + ", stopping parse at recipe " + i + "/" + recipeCount);
                return;
            }

            switch (type) {
                case ShapelessRecipe, UserDataShapelessRecipe, ShapelessChemistryRecipe -> readShapelessRecipe(wrapper, parsedRecipes);
                case ShapedRecipe, ShapedChemistryRecipe -> readShapedRecipe(wrapper, parsedRecipes);
                case FurnaceRecipe -> skipFurnaceRecipe(wrapper);
                case FurnaceAuxRecipe -> skipFurnaceAuxRecipe(wrapper);
                case MultiRecipe -> skipMultiRecipe(wrapper);
                case SmithingTransformRecipe -> skipSmithingTransformRecipe(wrapper);
                case SmithingTrimRecipe -> skipSmithingTrimRecipe(wrapper);
            }
        }

        // Skip brewing data
        final int brewingCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < brewingCount; i++) {
            wrapper.read(BedrockTypes.VAR_INT); // input id
            wrapper.read(BedrockTypes.VAR_INT); // input meta
            wrapper.read(BedrockTypes.VAR_INT); // reagent id
            wrapper.read(BedrockTypes.VAR_INT); // reagent meta
            wrapper.read(BedrockTypes.VAR_INT); // output id
            wrapper.read(BedrockTypes.VAR_INT); // output meta
        }

        // Skip container mix data
        final int containerCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < containerCount; i++) {
            wrapper.read(BedrockTypes.VAR_INT); // input id
            wrapper.read(BedrockTypes.VAR_INT); // reagent id
            wrapper.read(BedrockTypes.VAR_INT); // output id
        }

        // Skip material reducer data
        final int materialReducerCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < materialReducerCount; i++) {
            wrapper.read(BedrockTypes.VAR_INT); // input id
            final int outputCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            for (int j = 0; j < outputCount; j++) {
                wrapper.read(BedrockTypes.VAR_INT); // output id
                wrapper.read(BedrockTypes.VAR_INT); // output count
            }
        }

        final boolean cleanRecipes = wrapper.read(Types.BOOLEAN);

        if (cleanRecipes) {
            registry.clear();
        }
        for (final BedrockRecipe recipe : parsedRecipes) {
            registry.addRecipe(recipe);
        }

        ViaBedrock.getPlatform().getLogger().fine("[CraftingData] Parsed " + parsedRecipes.size() + " crafting recipes (cleanRecipes=" + cleanRecipes + ", total=" + registry.recipeCount() + ")");
    }

    private static void readShapelessRecipe(final PacketWrapper wrapper, final List<BedrockRecipe> recipes) {
        final String recipeId = wrapper.read(BedrockTypes.STRING);

        final int ingredientCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final List<RecipeIngredient> ingredients = new ArrayList<>(ingredientCount);
        for (int j = 0; j < ingredientCount; j++) {
            ingredients.add(wrapper.read(RecipeIngredientType.INSTANCE));
        }

        final int outputCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final BedrockItem primaryOutput;
        final List<BedrockItem> extraOutputs = new ArrayList<>();
        if (outputCount > 0) {
            primaryOutput = wrapper.read(InstanceItemType.INSTANCE);
            for (int j = 1; j < outputCount; j++) {
                extraOutputs.add(wrapper.read(InstanceItemType.INSTANCE));
            }
        } else {
            primaryOutput = BedrockItem.empty();
        }

        wrapper.read(BedrockTypes.UUID); // uuid
        final String tag = wrapper.read(BedrockTypes.STRING);
        final int priority = wrapper.read(BedrockTypes.VAR_INT);
        wrapper.read(Types.BYTE); // unlocking requirement ordinal
        final int networkId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

        recipes.add(new BedrockRecipe(
                recipeId, BedrockRecipe.RecipeType.SHAPELESS, 0, 0,
                ingredients, primaryOutput, extraOutputs, tag, priority, networkId, true
        ));
    }

    private static void readShapedRecipe(final PacketWrapper wrapper, final List<BedrockRecipe> recipes) {
        final String recipeId = wrapper.read(BedrockTypes.STRING);
        final int width = wrapper.read(BedrockTypes.VAR_INT);
        final int height = wrapper.read(BedrockTypes.VAR_INT);

        final List<RecipeIngredient> ingredients = new ArrayList<>(width * height);
        for (int j = 0; j < width * height; j++) {
            ingredients.add(wrapper.read(RecipeIngredientType.INSTANCE));
        }

        final int outputCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final BedrockItem primaryOutput;
        final List<BedrockItem> extraOutputs = new ArrayList<>();
        if (outputCount > 0) {
            primaryOutput = wrapper.read(InstanceItemType.INSTANCE);
            for (int j = 1; j < outputCount; j++) {
                extraOutputs.add(wrapper.read(InstanceItemType.INSTANCE));
            }
        } else {
            primaryOutput = BedrockItem.empty();
        }

        wrapper.read(BedrockTypes.UUID); // uuid
        final String tag = wrapper.read(BedrockTypes.STRING);
        final int priority = wrapper.read(BedrockTypes.VAR_INT);
        final boolean assumeSymmetry = wrapper.read(Types.BOOLEAN);
        wrapper.read(Types.BYTE); // unlocking requirement ordinal
        final int networkId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);

        recipes.add(new BedrockRecipe(
                recipeId, BedrockRecipe.RecipeType.SHAPED, width, height,
                ingredients, primaryOutput, extraOutputs, tag, priority, networkId, assumeSymmetry
        ));
    }

    private static void skipFurnaceRecipe(final PacketWrapper wrapper) {
        wrapper.read(BedrockTypes.VAR_INT); // input id
        wrapper.read(InstanceItemType.INSTANCE); // output
        wrapper.read(BedrockTypes.STRING); // tag
    }

    private static void skipFurnaceAuxRecipe(final PacketWrapper wrapper) {
        wrapper.read(BedrockTypes.VAR_INT); // input id
        wrapper.read(BedrockTypes.VAR_INT); // input data
        wrapper.read(InstanceItemType.INSTANCE); // output
        wrapper.read(BedrockTypes.STRING); // tag
    }

    private static void skipMultiRecipe(final PacketWrapper wrapper) {
        wrapper.read(BedrockTypes.UUID); // uuid
        wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // network id
    }

    private static void skipSmithingTransformRecipe(final PacketWrapper wrapper) {
        wrapper.read(BedrockTypes.STRING); // recipe id
        wrapper.read(RecipeIngredientType.INSTANCE); // template
        wrapper.read(RecipeIngredientType.INSTANCE); // base
        wrapper.read(RecipeIngredientType.INSTANCE); // addition
        wrapper.read(InstanceItemType.INSTANCE); // output
        wrapper.read(BedrockTypes.STRING); // tag
        wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // network id
    }

    private static void skipSmithingTrimRecipe(final PacketWrapper wrapper) {
        wrapper.read(BedrockTypes.STRING); // recipe id
        wrapper.read(RecipeIngredientType.INSTANCE); // template
        wrapper.read(RecipeIngredientType.INSTANCE); // base
        wrapper.read(RecipeIngredientType.INSTANCE); // addition
        wrapper.read(BedrockTypes.STRING); // tag
        wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // network id
    }

}
