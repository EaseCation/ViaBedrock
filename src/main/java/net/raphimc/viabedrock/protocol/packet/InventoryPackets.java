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
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.fastutil.ints.IntObjectPair;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import com.viaversion.viaversion.libs.mcstructs.text.components.TranslationComponent;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import net.lenni0451.mcstructs_bedrock.forms.Form;
import net.lenni0451.mcstructs_bedrock.forms.elements.*;
import net.lenni0451.mcstructs_bedrock.forms.serializer.FormSerializer;
import net.lenni0451.mcstructs_bedrock.forms.types.ActionForm;
import net.lenni0451.mcstructs_bedrock.forms.types.CustomForm;
import net.lenni0451.mcstructs_bedrock.forms.types.ModalForm;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.model.container.*;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.inventory.ClientAuthInventoryModule;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.EquipmentSlot;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.logging.Level;

public class InventoryPackets {

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_OPEN, ClientboundPackets26_1.OPEN_SCREEN, wrapper -> {
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final BlockStateRewriter blockStateRewriter = wrapper.user().get(BlockStateRewriter.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final byte containerId = wrapper.read(Types.BYTE); // container id
            final byte rawType = wrapper.read(Types.BYTE); // type
            final ContainerType type = ContainerType.getByValue(rawType);
            if (type == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ContainerType: " + rawType);
                wrapper.cancel();
                return;
            }
            final BlockPosition position = wrapper.read(BedrockTypes.BLOCK_POSITION); // position
            wrapper.read(BedrockTypes.VAR_LONG); // entity unique id

            if (inventoryTracker.isAnyScreenOpen()) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Server tried to open container while another container is open");
                PacketFactory.sendBedrockContainerClose(wrapper.user(), (byte) -1, ContainerType.NONE);
                wrapper.cancel();
                return;
            }
            final BedrockBlockEntity blockEntity = chunkTracker.getBlockEntity(position);
            final String bedrockBlockTag = blockStateRewriter.tag(chunkTracker.getBlockState(position));
            // Map the Bedrock block tag to the Java container title translation key. Most align with
            // "container.<tag>", but several differ; an unmatched key shows as raw "container.crafting_table"
            // text on the Java client instead of a localized title.
            final String javaTitleKey = switch (bedrockBlockTag) {
                case null -> "container." + type.name().toLowerCase(java.util.Locale.ROOT);
                case "crafting_table" -> "container.crafting";
                case "anvil" -> "container.repair";
                case "brewing_stand" -> "container.brewing";
                case "enchanting_table" -> "container.enchant";
                default -> "container." + bedrockBlockTag;
            };
            TextComponent title = new TranslationComponent(javaTitleKey);
            if (blockEntity != null && blockEntity.tag().get("CustomName") instanceof StringTag customNameTag) {
                title = TextUtil.stringToTextComponent(wrapper.user().get(ResourcePackStorage.class).getTexts().translate(customNameTag.getValue()));
            }

            final Container container;
            int javaMenuId = BedrockProtocol.MAPPINGS.getBedrockToJavaContainers().getOrDefault(type, -1);
            switch (type) {
                case INVENTORY -> {
                    inventoryTracker.setCurrentContainer(new InventoryContainer(wrapper.user(), containerId, position, inventoryTracker.getInventoryContainer()));
                    wrapper.cancel();
                    return;
                }
                case CONTAINER -> {
                    final String blockTag = blockStateRewriter.tag(chunkTracker.getBlockState(position));
                    if (CustomBlockTags.ENDER_CHEST.equals(blockTag)) {
                        container = new EnderChestContainer(wrapper.user(), containerId, title, position);
                    } else if (CustomBlockTags.SHULKER_BOX.equals(blockTag)) {
                        container = new ShulkerBoxContainer(wrapper.user(), containerId, title, position);
                        javaMenuId = BedrockProtocol.MAPPINGS.getJavaShulkerBoxMenuId();
                    } else {
                        final boolean isDoubleChest = blockEntity != null && blockEntity.tag().get("pairx") instanceof IntTag && blockEntity.tag().get("pairz") instanceof IntTag;
                        if (isDoubleChest) {
                            container = new ChestContainer(wrapper.user(), containerId, title, position, 54);
                            javaMenuId = BedrockProtocol.MAPPINGS.getJavaDoubleChestMenuId();
                        } else {
                            container = new ChestContainer(wrapper.user(), containerId, title, position, 27);
                        }
                    }
                }
                case BREWING_STAND -> {
                    container = new BrewingStandContainer(wrapper.user(), containerId, title, position);
                }
                case ANVIL -> {
                    container = new AnvilContainer(wrapper.user(), containerId, title, position);
                }
                case WORKBENCH -> {
                    container = new CraftingTableContainer(wrapper.user(), containerId, title, position);
                }
                case FURNACE, BLAST_FURNACE, SMOKER -> {
                    container = new FurnaceContainer(wrapper.user(), containerId, type, title, position);
                }
                case NONE, CAULDRON, JUKEBOX, ARMOR, HAND, HUD, DECORATED_POT -> { // Bedrock client can't open these containers
                    wrapper.cancel();
                    return;
                }
                default -> {
                    // throw new IllegalStateException("Unhandled ContainerType: " + type);
                    wrapper.cancel();
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to open unimplemented container: " + type);
                    PacketFactory.sendBedrockContainerClose(wrapper.user(), containerId, ContainerType.NONE);
                    return;
                }
            }
            inventoryTracker.setCurrentContainer(container);

            // Use the Java window id (javaContainerId), not the raw Bedrock containerId: the crafting
            // table overrides javaContainerId() to a fixed value and all clientbound updates / serverbound
            // lookups key off javaContainerId. Sending the raw containerId here desynced the window id so
            // the table's CONTAINER_CLICK/CLOSE never matched (items not placed, container never closed).
            wrapper.write(Types.VAR_INT, (int) container.javaContainerId()); // container id (Java window id)
            wrapper.write(Types.VAR_INT, javaMenuId); // type
            wrapper.write(Types.TAG, TextUtil.textComponentToNbt(title)); // title
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_CLOSE, ClientboundPackets26_1.CONTAINER_CLOSE, new PacketHandlers() {
            @Override
            protected void register() {
                map(Types.BYTE, Types.VAR_INT); // container id
                handler(wrapper -> {
                    final ContainerType containerType = ContainerType.getByValue(wrapper.read(Types.BYTE)); // type
                    final boolean serverInitiated = wrapper.read(Types.BOOLEAN); // server initiated
                    final byte bedrockContainerId = (byte) (int) wrapper.get(Types.VAR_INT, 0);

                    final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                    final Container container;
                    if (serverInitiated) {
                        container = inventoryTracker.acceptServerClose(bedrockContainerId, containerType);
                    } else {
                        container = inventoryTracker.acceptClientCloseConfirmation(bedrockContainerId);
                        wrapper.cancel(); // Java already closed the screen which initiated this acknowledgement.
                    }
                    if (container == null) {
                        wrapper.cancel();
                        return;
                    }
                    // Java window id must match what CONTAINER_OPEN sent (javaContainerId), not the raw Bedrock containerId.
                    wrapper.set(Types.VAR_INT, 0, (int) container.javaContainerId());

                    clearClosedCraftingTable(inventoryTracker, container);
                });
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.INVENTORY_CONTENT, ClientboundPackets26_1.CONTAINER_SET_CONTENT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final int containerId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // container id
            final BedrockItem[] items = wrapper.read(itemRewriter.itemArrayType()); // items
            final FullContainerName containerName = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME); // container name
            final BedrockItem storageItem = wrapper.read(itemRewriter.itemType()); // storage item

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getContainerClientbound((byte) containerId, containerName, storageItem);
            if (container != null && container.setItems(items)) {
                PacketFactory.writeJavaContainerSetContent(wrapper, container);
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.INVENTORY_SLOT, ClientboundPackets26_1.CONTAINER_SET_SLOT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final int containerId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // container id
            final int slot = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // slot
            final FullContainerName containerName = wrapper.read(BedrockTypes.OPTIONAL_FULL_CONTAINER_NAME); // container name
            final BedrockItem storageItem = wrapper.read(itemRewriter.optionalNewItemType()); // storage item
            final BedrockItem item = wrapper.read(itemRewriter.newItemType()); // item

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getContainerClientbound((byte) containerId, containerName, storageItem);
            if (container != null && container.setItem(slot, item)) {
                if (container.type() == ContainerType.HUD && slot == 0) { // cursor item
                    wrapper.setPacketType(ClientboundPackets26_1.SET_CURSOR_ITEM);
                } else {
                    wrapper.write(Types.VAR_INT, (int) container.javaContainerId()); // container id
                    wrapper.write(Types.VAR_INT, 0); // revision
                    wrapper.write(Types.SHORT, (short) container.javaSlot(slot)); // slot
                }
                wrapper.write(VersionedTypes.V26_1.item, container.getJavaItem(slot)); // item
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_SET_DATA, ClientboundPackets26_1.CONTAINER_SET_DATA, wrapper -> {
            final int containerId = wrapper.read(Types.BYTE); // container id
            final int property = wrapper.read(BedrockTypes.VAR_INT); // property
            final int value = wrapper.read(BedrockTypes.VAR_INT); // value

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final Container container = inventoryTracker.getCurrentContainer();
            // Only one screen can be open at a time, and the server only sends CONTAINER_SET_DATA while the
            // furnace screen is open. Anything else (e.g. a brewing stand) is dropped, matching prior behavior.
            if (!(container instanceof FurnaceContainer) || (byte) containerId != container.containerId()) {
                wrapper.cancel();
                return;
            }

            // Bedrock (ContainerSetDataPacket) only sends the numerators:
            //   0 SMELT_PROGRESS      = cookTime     (0..burnInterval) -> Java cooking_time_spent (data slot 2)
            //   1 REMAINING_FUEL_TIME = burnDuration (0..burnInterval) -> Java lit_time_remaining (data slot 0)
            // Java needs the matching denominator slots too, or the flame ratio is wrong and the cooking arrow
            // is hidden entirely (getBurnProgress returns 0 when cooking_total_time == 0). burnInterval is 200
            // for a normal furnace and 100 for a blast furnace / smoker (which cook at double speed).
            final int burnInterval = container.type() == ContainerType.FURNACE ? 200 : 100;
            final int javaWindowId = container.javaContainerId();

            final int numeratorSlot;
            final int denominatorSlot;
            switch (property) {
                case 0 -> { numeratorSlot = 2; denominatorSlot = 3; } // SMELT_PROGRESS
                case 1 -> { numeratorSlot = 0; denominatorSlot = 1; } // REMAINING_FUEL_TIME
                default -> { wrapper.cancel(); return; } // MAX_FUEL_TIME / STORED_XP / FUEL_AUX: not needed by the Java GUI
            }

            // Synthesize the denominator (Bedrock never sends it) so the GUI renders a proportional bar.
            final PacketWrapper denominator = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_DATA, wrapper.user());
            denominator.write(Types.VAR_INT, javaWindowId); // container id
            denominator.write(Types.SHORT, (short) denominatorSlot); // property
            denominator.write(Types.SHORT, (short) burnInterval); // value
            denominator.send(BedrockProtocol.class);

            // Rewrite this packet as the numerator update.
            wrapper.write(Types.VAR_INT, javaWindowId); // container id
            wrapper.write(Types.SHORT, (short) numeratorSlot); // property
            wrapper.write(Types.SHORT, (short) value); // value
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MODAL_FORM_REQUEST, ClientboundPackets26_1.SHOW_DIALOG, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final int id = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // id
            final String data = wrapper.read(BedrockTypes.STRING); // data

            if (inventoryTracker.getCurrentContainer() != null || inventoryTracker.getCurrentForm() != null) {
                sendModalFormCancel(wrapper.user(), id, ModalFormCancelReason.UserBusy);
                wrapper.cancel();
                return;
            }

            final Form form;
            try {
                form = FormSerializer.deserialize(data);
            } catch (RuntimeException e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while deserializing modal form " + id, e);
                sendModalFormCancel(wrapper.user(), id, ModalFormCancelReason.UserClosed);
                wrapper.cancel();
                return;
            }
            final ResourcePackStorage resourcePackStorage = wrapper.user().get(ResourcePackStorage.class);
            form.setTranslator(resourcePackStorage.getTexts()::translate);
            try {
                wrapper.write(Types.TRUSTED_COMPOUND_TAG_HOLDER, BedrockFormDialogConverter.serialize(BedrockFormDialogConverter.convert(id, form, wrapper.user().getProtocolInfo().protocolVersion()))); // dialog data
            } catch (RuntimeException e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while converting modal form " + id + " (" + form.getClass().getSimpleName() + ")", e);
                sendModalFormCancel(wrapper.user(), id, ModalFormCancelReason.UserClosed);
                wrapper.cancel();
                return;
            }
            inventoryTracker.setCurrentForm(IntObjectPair.of(id, form));
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CLOSE_FORM, ClientboundPackets26_1.CLEAR_DIALOG, wrapper -> {
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getCurrentForm() != null) {
                inventoryTracker.closeCurrentForm();
            } else if (inventoryTracker.getCurrentNpcDialogue() != null) {
                inventoryTracker.setCurrentNpcDialogue(null);
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_HOTBAR, ClientboundPackets26_1.SET_HELD_SLOT, wrapper -> {
            final InventoryContainer inventoryContainer = wrapper.user().get(InventoryTracker.class).getInventoryContainer();
            final int slot = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // selected slot
            final byte containerId = wrapper.read(Types.BYTE); // container id
            final boolean shouldSelectSlot = wrapper.read(Types.BOOLEAN); // should select slot
            if (slot >= 0 && slot < 9 && containerId == inventoryContainer.containerId() && shouldSelectSlot) {
                wrapper.write(Types.VAR_INT, slot); // slot
            } else {
                wrapper.cancel();
                if (containerId != inventoryContainer.containerId()) { // Bedrock client doesn't render hotbar selection and held item anymore
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to set hotbar slot with wrong container id: " + containerId);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CONTAINER_REGISTRY_CLEANUP, null, wrapper -> {
            wrapper.cancel();
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            final FullContainerName[] removedContainers = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME_ARRAY); // removed containers
            for (FullContainerName containerName : removedContainers) {
                inventoryTracker.removeDynamicContainer(containerName);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_ARMOR_DAMAGE, ClientboundPackets26_1.SET_EQUIPMENT, wrapper -> {
            if (!wrapper.user().get(GameSessionStorage.class).isInventoryServerAuthoritative()) {
                wrapper.cancel();
                return;
            }
            final int size = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // size
            if (size <= 0) {
                wrapper.cancel();
                return;
            }
            final Container armorContainer = wrapper.user().get(InventoryTracker.class).getArmorContainer();

            wrapper.write(Types.VAR_INT, wrapper.user().get(EntityTracker.class).getClientPlayer().javaId()); // entity id
            for (int i = 0; i < size; i++) {
                final int rawArmorSlot = wrapper.read(BedrockTypes.VAR_INT); // armor slot
                final SharedTypes_Legacy_ArmorSlot armorSlot = SharedTypes_Legacy_ArmorSlot.getByValue(rawArmorSlot);
                if (armorSlot == null) { // Bedrock client ignores the whole packet if an unknown armor slot is sent
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown SharedTypes_Legacy_ArmorSlot: " + rawArmorSlot);
                    wrapper.cancel();
                    return;
                }
                final short damage = wrapper.read(BedrockTypes.SHORT_LE); // damage

                final BedrockItem item = armorSlot.getValue() < armorContainer.size() ? armorContainer.getItem(armorSlot.getValue()) : BedrockItem.empty();
                if (item.tag() == null) {
                    item.setTag(new CompoundTag());
                }
                item.tag().putInt("Damage", damage);

                final EquipmentSlot equipmentSlot = switch (armorSlot) {
                    case Head -> EquipmentSlot.HEAD;
                    case Torso -> EquipmentSlot.CHEST;
                    case Legs -> EquipmentSlot.LEGS;
                    case Feet -> EquipmentSlot.FEET;
                    case Body -> EquipmentSlot.BODY;
                };
                wrapper.write(Types.BYTE, (byte) (equipmentSlot.ordinal() | (i < (size - 1) ? Byte.MIN_VALUE : 0))); // slot
                wrapper.write(VersionedTypes.V26_1.item, wrapper.user().get(ItemRewriter.class).javaItem(item)); // item
            }
        });

        protocol.registerServerbound(ServerboundPackets26_1.CONTAINER_CLICK, null, wrapper -> {
            wrapper.cancel();
            final int containerId = wrapper.read(Types.VAR_INT); // container id
            final int revision = wrapper.read(Types.VAR_INT); // revision
            final short slot = wrapper.read(Types.SHORT); // slot
            final byte button = wrapper.read(Types.BYTE); // button
            final ContainerInput action = ContainerInput.values()[wrapper.read(Types.VAR_INT)]; // action

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getPendingCloseContainer() != null) {
                wrapper.cancel();
                return;
            }
            final Container container = inventoryTracker.getContainerServerbound((byte) containerId);
            if (container == null) {
                if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue()) {
                    // Bedrock client can send multiple OpenInventory requests if the server doesn't respond, so this is fine here
                    final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, wrapper.user());
                    interact.write(Types.UNSIGNED_BYTE, (short) InteractPacket_Action.OpenInventory.getValue()); // action
                    interact.write(BedrockTypes.UNSIGNED_VAR_LONG, wrapper.user().get(EntityTracker.class).getClientPlayer().runtimeId()); // target entity runtime id
                    interact.write(BedrockTypes.OPTIONAL_POSITION_3F, null); // position
                    interact.sendToServer(BedrockProtocol.class);
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }

                wrapper.cancel();
                return;
            }
            if (!container.handleClick(revision, slot, button, action)) {
                if (container.type() != ContainerType.INVENTORY) {
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                }
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), container);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.SET_CREATIVE_MODE_SLOT, null, wrapper -> {
            wrapper.cancel();
            final short slot = wrapper.read(Types.SHORT); // slot
            final Item item = wrapper.read(VersionedTypes.V26_1.lengthPrefixedItem); // item

            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
            if (inventoryTracker.getPendingCloseContainer() != null) {
                wrapper.cancel();
                return;
            }
            PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
        });
        protocol.registerServerbound(ServerboundPackets26_1.CUSTOM_CLICK_ACTION, null, wrapper -> {
            wrapper.cancel();
            final String id = wrapper.read(Types.STRING); // id
            final CompoundTag payload = (CompoundTag) wrapper.read(Types.CUSTOM_CLICK_ACTION_TAG); // payload
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            if (inventoryTracker.getCurrentForm() != null) {
                final Form form = inventoryTracker.getCurrentForm().right();
                final int formId = inventoryTracker.getCurrentForm().leftInt();
                if (!id.equals("viabedrock:form/" + formId)) {
                    return;
                }

                inventoryTracker.setCurrentForm(null);
                final PacketWrapper modalFormResponse = PacketWrapper.create(ServerboundBedrockPackets.MODAL_FORM_RESPONSE, wrapper.user());
                if (payload.contains("exit") && payload.getBoolean("exit")) {
                    modalFormResponse.write(BedrockTypes.UNSIGNED_VAR_INT, formId); // id
                    modalFormResponse.write(Types.BOOLEAN, false); // has response
                    modalFormResponse.write(Types.BOOLEAN, true); // has cancel reason
                    modalFormResponse.write(Types.BYTE, (byte) ModalFormCancelReason.UserClosed.getValue()); // cancel reason
                    modalFormResponse.sendToServer(BedrockProtocol.class);
                    return;
                }

                if (form instanceof ModalForm modalForm) {
                    modalForm.setClickedButton(payload.getInt("button_id"));
                } else if (form instanceof ActionForm actionForm) {
                    actionForm.setClickedButton(payload.getInt("button_id"));
                } else if (form instanceof CustomForm customForm) {
                    for (int elementIndex = 0; elementIndex < customForm.getElements().length; elementIndex++) {
                        final String inputKey = String.valueOf(elementIndex);
                        if (!payload.contains(inputKey)) continue;
                        final FormElement element = customForm.getElements()[elementIndex];
                        if (element instanceof CheckboxFormElement checkbox) {
                            checkbox.setChecked(payload.getBoolean(inputKey));
                        } else if (element instanceof DropdownFormElement dropdown) {
                            dropdown.setSelected(Integer.parseInt(payload.getString(inputKey)));
                        } else if (element instanceof SliderFormElement slider) {
                            slider.setCurrent(payload.getFloat(inputKey));
                        } else if (element instanceof StepSliderFormElement stepSlider) {
                            stepSlider.setSelected(Integer.parseInt(payload.getString(inputKey)));
                        } else if (element instanceof TextFieldFormElement textField) {
                            textField.setValue(payload.getString(inputKey));
                        }
                    }
                } else {
                    throw new IllegalArgumentException("Unhandled form type: " + form.getClass().getSimpleName());
                }

                modalFormResponse.write(BedrockTypes.UNSIGNED_VAR_INT, formId); // id
                modalFormResponse.write(Types.BOOLEAN, true); // has response
                modalFormResponse.write(BedrockTypes.STRING, form.serializeResponse() + '\n'); // response
                modalFormResponse.write(Types.BOOLEAN, false); // has cancel reason
                modalFormResponse.sendToServer(BedrockProtocol.class);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.CONTAINER_CLOSE, ServerboundBedrockPackets.CONTAINER_CLOSE, new PacketHandlers() {
            @Override
            protected void register() {
                map(Types.VAR_INT, Types.BYTE); // container id
                create(Types.BYTE, (byte) ContainerType.NONE.getValue()); // type
                create(Types.BOOLEAN, false); // server initiated
                handler(wrapper -> {
                    final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                    final byte containerId = wrapper.get(Types.BYTE, 0);
                    final Container container = inventoryTracker.getContainerServerbound(containerId);
                    if (container == null) {
                        // Java's player inventory is not opened by a server container packet. Bedrock uses
                        // container -1 to close that UI after its cursor transaction has completed.
                        if (containerId == 0 && !inventoryTracker.isContainerOpen()) {
                            if (!ClientAuthInventoryModule.returnCursorBeforeClose(wrapper.user())) {
                                wrapper.cancel();
                                return;
                            }
                            wrapper.set(Types.BYTE, 0, (byte) -1);
                            return;
                        }
                        wrapper.cancel();
                        return;
                    }

                    // A Bedrock client resolves its cursor with an inventory transaction before it
                    // closes the screen. Java only sends CONTAINER_CLOSE, so provide that missing step.
                    if (!ClientAuthInventoryModule.returnCursorBeforeClose(wrapper.user())
                            || !inventoryTracker.beginClientClose(container)) {
                        wrapper.cancel();
                        return;
                    }
                    wrapper.set(Types.BYTE, 0, container.containerId());
                });
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.SET_CARRIED_ITEM, ServerboundBedrockPackets.MOB_EQUIPMENT, wrapper -> {
            final short slot = wrapper.read(Types.SHORT); // slot
            wrapper.user().get(InventoryTracker.class).getInventoryContainer().setSelectedHotbarSlot((byte) slot, wrapper); // slot
        });
        protocol.registerServerbound(ServerboundPackets26_1.PICK_ITEM_FROM_BLOCK, ServerboundBedrockPackets.BLOCK_PICK_REQUEST, wrapper -> {
            wrapper.passthroughAndMap(Types.BLOCK_POSITION1_14, BedrockTypes.BLOCK_POSITION); // position
            wrapper.passthrough(Types.BOOLEAN); // include data
            wrapper.write(Types.UNSIGNED_BYTE, (short) 9); // number of empty hotbar slots (vanilla client always sends 9)
        });
        protocol.registerServerbound(ServerboundPackets26_1.PICK_ITEM_FROM_ENTITY, ServerboundBedrockPackets.ENTITY_PICK_REQUEST, wrapper -> {
            final int entityId = wrapper.read(Types.VAR_INT); // entity id
            final boolean includeData = wrapper.read(Types.BOOLEAN); // include data

            final Entity entity = wrapper.user().get(EntityTracker.class).getEntityByJid(entityId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }

            wrapper.write(BedrockTypes.LONG_LE, entity.uniqueId()); // entity unique id
            wrapper.write(Types.UNSIGNED_BYTE, (short) 9); // number of empty hotbar slots (vanilla client always sends 9)
            wrapper.write(Types.BOOLEAN, includeData); // include data
        });
    }

    private static void sendModalFormCancel(final UserConnection user, final int formId, final ModalFormCancelReason reason) {
        final PacketWrapper response = PacketWrapper.create(ServerboundBedrockPackets.MODAL_FORM_RESPONSE, user);
        response.write(BedrockTypes.UNSIGNED_VAR_INT, formId); // id
        response.write(Types.BOOLEAN, false); // has response
        response.write(Types.BOOLEAN, true); // has cancel reason
        response.write(Types.BYTE, (byte) reason.getValue()); // cancel reason
        response.sendToServer(BedrockProtocol.class);
    }

    private static void clearClosedCraftingTable(final InventoryTracker inventoryTracker, final Container container) {
        if (!(container instanceof CraftingTableContainer)) {
            return;
        }
        final Container hudContainer = inventoryTracker.getHudContainer();
        for (int slot = 32; slot <= 40; slot++) {
            hudContainer.setItemSilent(slot, BedrockItem.empty());
        }
        hudContainer.setItemSilent(50, BedrockItem.empty());
    }

}
