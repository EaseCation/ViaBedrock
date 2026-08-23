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
import net.raphimc.viabedrock.api.model.container.AnvilContainer;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.packet.FilterTextLayout;
import net.raphimc.viabedrock.protocol.storage.AnvilSessionStorage;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Java anvil result clicks become MOT {@code CraftRecipeOptional} rather than a
 * plain Take from {@code AnvilResultPreview}. MOT writes the real result into
 * HUD created-output slot 50 and then expects Consume of the input/material.
 */
public final class AnvilSimulator {

    // Distinct from CraftingSimulator's SOURCE_TODO crafting-result (-4).
    private static final int TODO_ANVIL_RESULT = -6;

    private AnvilSimulator() {
    }

    public static List<InventoryActionData> simulateTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!(container instanceof AnvilContainer anvil)) {
            return null;
        }
        final BedrockItem input = anvil.getItem(0);
        if (input == null || input.isEmpty()) {
            return null;
        }
        final BedrockItem cursor = SlotMapper.getCursorItem(tracker);
        if (cursor != null && !cursor.isEmpty()) {
            return null;
        }
        final BedrockItem material = anvil.getItem(1);
        final int materialCount = AnvilRepairCost.materialCount(input, material, tracker.user() != null ? tracker.user().get(net.raphimc.viabedrock.protocol.rewriter.ItemRewriter.class) : null);
        if (materialCount < 0) {
            return null;
        }
        // Java computes the anvil preview locally; MOT never fills AnvilResultPreview.
        // Use the input stack as a stand-in so the SAI request still consumes the inputs
        // and the Java cursor is not left holding a phantom item if the server rejects.
        final BedrockItem result = input.copy();
        final List<InventoryActionData> actions = new ArrayList<>();
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_ANVIL_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, result, BedrockItem.empty()));
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, anvil.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                0, input.copy(), BedrockItem.empty()));
        if (materialCount > 0) {
            final BedrockItem leftover = material.copy();
            leftover.setAmount(material.amount() - materialCount);
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.ContainerInventory, anvil.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                    1, material.copy(), leftover.amount() <= 0 ? BedrockItem.empty() : leftover));
        }
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, BedrockItem.empty(), result.copy()));
        return actions;
    }

    public static boolean isTakeResult(final List<InventoryActionData> actions) {
        if (actions == null) {
            return false;
        }
        for (final InventoryActionData action : actions) {
            if (action.source().type() == InventorySourceType.NonImplementedFeatureTODO
                    && action.source().containerId() == TODO_ANVIL_RESULT) {
                return true;
            }
        }
        return false;
    }

    public static boolean sendTakeResult(final UserConnection user, final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!(container instanceof AnvilContainer anvil)) {
            return false;
        }
        final BedrockItem input = anvil.getItem(0);
        if (input == null || input.isEmpty()) {
            return false;
        }
        final BedrockItem material = anvil.getItem(1);
        final int materialCount = AnvilRepairCost.materialCount(input, material, tracker.user() != null ? tracker.user().get(net.raphimc.viabedrock.protocol.rewriter.ItemRewriter.class) : null);
        if (materialCount < 0) {
            return false;
        }
        final AnvilSessionStorage session = user.get(AnvilSessionStorage.class);
        final String renameText = session != null ? session.renameText() : "";
        final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeAnvilApply(
                tracker, renameText, 1, materialCount, Math.max(1, input.amount() > 0 ? 1 : 0));
        if (encoded.unsupported() || encoded.isEmpty()) {
            return false;
        }
        final PacketWrapper request = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, user);
        request.write(Types.REMAINING_BYTES, encoded.payload());
        request.sendToServer(BedrockProtocol.class);
        return true;
    }

    public static void handleFilterEcho(final UserConnection user, final FilterTextLayout.Packet packet) {
        if (packet == null || !packet.fromServer()) {
            return;
        }
        final AnvilSessionStorage session = user.get(AnvilSessionStorage.class);
        if (session != null) {
            session.setRenameText(packet.text());
        }
    }
}
