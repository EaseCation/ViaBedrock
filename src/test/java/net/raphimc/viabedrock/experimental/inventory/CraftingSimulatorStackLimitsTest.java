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

import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingSimulatorStackLimitsTest {

    private static final int OUTPUT_ID = 2;
    private static final int INGREDIENT_ID = 3;

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
    private final InventoryTracker tracker = new InventoryTracker(this.user);
    private final RecipeRegistry recipes = new RecipeRegistry(this.user);

    CraftingSimulatorStackLimitsTest() {
        this.user.put(this.recipes);
    }

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void fullArmorCursorDoesNotConsumeAnotherCraft() {
        this.prepareRecipe(1);
        this.tracker.getHudContainer().setItemSilent(0, item(OUTPUT_ID, 1));

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftPickup(
                false, this.tracker, ignored -> 1);

        assertNotNull(actions);
        assertTrue(actions.isEmpty());
        assertEquals(1, this.tracker.getHudContainer().getItem(28).amount());
        assertEquals(1, this.tracker.getHudContainer().getItem(0).amount());
    }

    @Test
    void quickMoveSplitsCraftOutputIntoLegalArmorStacks() {
        this.prepareRecipe(2);

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftQuickMove(
                false, this.tracker, ignored -> 1);

        assertNotNull(actions);
        final List<InventoryActionData> inventoryActions = inventoryActions(actions);
        assertEquals(2, inventoryActions.size());
        assertEquals(List.of(9, 10), inventoryActions.stream().map(InventoryActionData::slot).toList());
        assertTrue(inventoryActions.stream().allMatch(action -> action.toItem().amount() == 1));
    }

    @Test
    void quickMoveFailsAtomicallyWhenNoLegalTargetExists() {
        this.prepareRecipe(1);
        for (int slot = 0; slot < this.tracker.getInventoryContainer().size(); slot++) {
            this.tracker.getInventoryContainer().setItemSilent(slot, item(99, 1));
        }

        assertNull(CraftingSimulator.simulateCraftQuickMove(false, this.tracker, ignored -> 1));
        assertEquals(1, this.tracker.getHudContainer().getItem(28).amount());
    }

    @Test
    void quickMoveAccumulatesAllCraftsAvailableInTheGrid() {
        this.prepareRecipe(4, 16);

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftQuickMove(
                false, this.tracker, ignored -> 64);

        assertNotNull(actions);
        assertEquals(0, actions.stream()
                .filter(action -> action.source().type() == InventorySourceType.ContainerInventory)
                .filter(action -> action.source().containerId() == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue())
                .filter(action -> action.slot() == 28)
                .findFirst().orElseThrow().toItem().amount());
        final List<InventoryActionData> inventoryActions = inventoryActions(actions);
        assertEquals(1, inventoryActions.size());
        assertEquals(64, inventoryActions.get(0).toItem().amount());
        assertEquals(16, actions.stream()
                .filter(action -> action.source().type() == InventorySourceType.NonImplementedFeatureTODO)
                .filter(action -> action.source().containerId() == -5)
                .findFirst().orElseThrow().toItem().amount());
    }

    @Test
    void unresolvedCraftOutputLimitUsesAuthoritativeRollback() {
        this.prepareRecipe(1);

        assertNull(CraftingSimulator.simulateCraftPickup(
                false, this.tracker, ignored -> JavaItemStackLimits.UNSUPPORTED));
        assertEquals(1, this.tracker.getHudContainer().getItem(28).amount());
    }

    private void prepareRecipe(final int outputAmount) {
        prepareRecipe(outputAmount, 1);
    }

    private void prepareRecipe(final int outputAmount, final int ingredientAmount) {
        this.recipes.clear();
        this.tracker.getHudContainer().clearItems();
        this.tracker.getInventoryContainer().clearItems();
        this.tracker.getHudContainer().setItemSilent(28, item(INGREDIENT_ID, ingredientAmount));
        this.recipes.addRecipe(new BedrockRecipe(
                "test:armor",
                BedrockRecipe.RecipeType.SHAPELESS,
                0,
                0,
                List.of(new BedrockRecipe.RecipeIngredient(
                        INGREDIENT_ID,
                        BedrockRecipe.RecipeIngredient.ANY_DAMAGE,
                        1
                )),
                item(OUTPUT_ID, outputAmount),
                List.of(),
                "crafting_table",
                0,
                1,
                false
        ));
    }

    private static List<InventoryActionData> inventoryActions(final List<InventoryActionData> actions) {
        return actions.stream()
                .filter(action -> action.source().type() == InventorySourceType.ContainerInventory)
                .filter(action -> action.source().containerId() == ContainerID.CONTAINER_ID_INVENTORY.getValue())
                .toList();
    }

    private static BedrockItem item(final int id, final int amount) {
        return new BedrockItem(id, (short) 0, (byte) amount);
    }

}
