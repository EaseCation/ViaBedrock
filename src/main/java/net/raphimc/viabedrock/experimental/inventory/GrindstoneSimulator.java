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
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Java grindstone result clicks become MOT {@code CraftRepairAndDisenchant}
 * ({@code recipeId=0}, {@code times=1}, {@code repairCost=0}) plus Consume of
 * equipment/ingredient and Take from HUD created-output slot 50.
 */
public final class GrindstoneSimulator {

    static final int TODO_GRINDSTONE_RESULT = -8;

    private GrindstoneSimulator() {
    }

    public static boolean isGrindstone(final Container container) {
        return container != null && container.type() == ContainerType.GRINDSTONE;
    }

    public static List<InventoryActionData> simulateTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isGrindstone(container)) {
            return null;
        }
        final BedrockItem input = container.getItem(0);
        if (input == null || input.isEmpty()) {
            return null;
        }
        final BedrockItem cursor = SlotMapper.getCursorItem(tracker);
        if (cursor != null && !cursor.isEmpty()) {
            return null;
        }
        final BedrockItem additional = container.getItem(1);
        final int additionalCount = additional != null && !additional.isEmpty() ? Math.min(1, additional.amount()) : 0;
        final BedrockItem result = input.copy();
        final List<InventoryActionData> actions = new ArrayList<>();
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_GRINDSTONE_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, result, BedrockItem.empty()));
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, container.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                0, input.copy(), BedrockItem.empty()));
        if (additionalCount > 0) {
            final BedrockItem leftover = additional.copy();
            leftover.setAmount(additional.amount() - additionalCount);
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.ContainerInventory, container.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                    1, additional.copy(), leftover.amount() <= 0 ? BedrockItem.empty() : leftover));
        }
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, BedrockItem.empty(), result.copy()));
        return actions;
    }

    public static boolean isTakeResult(final List<InventoryActionData> actions) {
        return CartographySimulator.hasTodoMarker(actions, TODO_GRINDSTONE_RESULT);
    }

    public static boolean sendTakeResult(final UserConnection user, final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isGrindstone(container)) {
            return false;
        }
        final BedrockItem input = container.getItem(0);
        if (input == null || input.isEmpty()) {
            return false;
        }
        final BedrockItem additional = container.getItem(1);
        final int additionalCount = additional != null && !additional.isEmpty() ? Math.min(1, additional.amount()) : 0;
        final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeGrindstoneApply(
                tracker, 1, additionalCount, 1);
        if (encoded.unsupported() || encoded.isEmpty()) {
            return false;
        }
        final PacketWrapper request = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, user);
        request.write(Types.REMAINING_BYTES, encoded.payload());
        request.sendToServer(BedrockProtocol.class);
        return true;
    }
}
