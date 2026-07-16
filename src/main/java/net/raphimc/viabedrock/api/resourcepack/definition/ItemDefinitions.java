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
package net.raphimc.viabedrock.api.resourcepack.definition;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.NumberTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

// https://wiki.bedrock.dev/items/item-components.html
public class ItemDefinitions {

    static final int MAX_STACK_SIZE = 99;

    private final Map<String, ItemDefinition> items = new HashMap<>();
    private final Set<String> malformedArmorWarnings = new HashSet<>();
    private final Set<String> malformedStackSizeWarnings = new HashSet<>();
    private final Consumer<String> warningLogger;

    public ItemDefinitions(final ResourcePackStorage resourcePackStorage) {
        this(message -> ViaBedrock.getPlatform().getLogger().log(Level.WARNING, message));
        for (ResourcePack pack : resourcePackStorage.getPackStackBottomToTop()) {
            for (String itemPath : pack.content().getFilesDeep("items/", ".json")) {
                try {
                    final JsonObject item = pack.content().getJson(itemPath).getAsJsonObject("minecraft:item");
                    final String identifier = Key.namespaced(item.getAsJsonObject("description").get("identifier").getAsString());
                    this.addFromResourceComponents(identifier, item.has("components") ? item.getAsJsonObject("components") : new JsonObject());
                } catch (Throwable e) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to parse item definition " + itemPath + " in pack " + pack.key(), e);
                }
            }
        }
    }

    ItemDefinitions(final Consumer<String> warningLogger) {
        this.warningLogger = warningLogger;
    }

    void addFromResourceComponents(final String identifier, final JsonObject components) {
        final ItemDefinition itemDefinition = new ItemDefinition(identifier, false);
        if (components.has("minecraft:icon")) {
            itemDefinition.iconComponent = components.get("minecraft:icon").getAsString();
        }
        if (components.has("minecraft:display_name")) {
            itemDefinition.displayNameComponent = components.get("minecraft:display_name").getAsString();
        }
        if (components.has("minecraft:max_stack_size")) {
            try {
                itemDefinition.maxStackSize = parseMaxStackSize(components.get("minecraft:max_stack_size"));
            } catch (IllegalArgumentException e) {
                this.warnInvalidStackSize(identifier, e.getMessage());
            }
        }
        this.items.put(identifier, itemDefinition);
    }

    public void addFromNetworkTag(final String identifier, final CompoundTag tag) {
        final ItemDefinition itemDefinition = new ItemDefinition(identifier, true);
        final ItemDefinition resourceDefinition = this.items.get(identifier);
        if (resourceDefinition != null) {
            itemDefinition.maxStackSize = resourceDefinition.maxStackSize;
        }
        if (tag.get("components") instanceof CompoundTag components) {
            Tag maxStackSize = null;
            if (components.get("item_properties") instanceof CompoundTag itemProperties) {
                if (itemProperties.get("minecraft:icon") instanceof CompoundTag icon) {
                    if (icon.get("textures") instanceof CompoundTag texture) {
                        if (texture.get("default") instanceof StringTag defaultTexture) {
                            itemDefinition.iconComponent = defaultTexture.getValue();
                        }
                    }
                }
                maxStackSize = itemProperties.get("max_stack_size");
            }
            if (components.get("minecraft:display_name") instanceof CompoundTag displayName) {
                if (displayName.get("value") instanceof StringTag value) {
                    itemDefinition.displayNameComponent = value.getValue();
                }
            }
            final Tag armorComponent = components.get("minecraft:armor");
            if (armorComponent != null) {
                try {
                    itemDefinition.armorProtection = parseArmorProtection(armorComponent);
                } catch (IllegalArgumentException e) {
                    itemDefinition.armorProtection = 0;
                    if (this.malformedArmorWarnings.add(identifier)) {
                        this.warningLogger.accept("Invalid minecraft:armor component for item " + identifier + ": " + e.getMessage());
                    }
                }
            }

            if (components.get("minecraft:max_stack_size") != null) {
                maxStackSize = components.get("minecraft:max_stack_size");
            }
            if (maxStackSize != null) {
                try {
                    itemDefinition.maxStackSize = parseMaxStackSize(maxStackSize);
                } catch (IllegalArgumentException e) {
                    itemDefinition.maxStackSize = null;
                    this.warnInvalidStackSize(identifier, e.getMessage());
                }
            }
        }
        this.items.put(identifier, itemDefinition);
    }

    private void warnInvalidStackSize(final String identifier, final String reason) {
        if (this.malformedStackSizeWarnings.add(identifier)) {
            this.warningLogger.accept("Invalid max stack size component for item " + identifier + ": " + reason);
        }
    }

    static int parseArmorProtection(final Tag armorComponent) {
        if (!(armorComponent instanceof CompoundTag armor)) {
            throw new IllegalArgumentException("component is not a compound tag");
        }
        if (!(armor.get("protection") instanceof NumberTag protectionTag)) {
            throw new IllegalArgumentException("protection is not numeric");
        }

        final double protection = protectionTag.asDouble();
        if (!Double.isFinite(protection) || protection < 0D || protection > Integer.MAX_VALUE || protection != Math.rint(protection)) {
            throw new IllegalArgumentException("protection is not a non-negative integer");
        }
        return (int) protection;
    }

    static int parseMaxStackSize(final Tag stackSizeTag) {
        final Tag value = stackSizeTag instanceof CompoundTag compound ? compound.get("value") : stackSizeTag;
        if (!(value instanceof NumberTag numberTag)) {
            throw new IllegalArgumentException("max stack size is not numeric");
        }
        return validatedMaxStackSize(numberTag.asDouble());
    }

    static int parseMaxStackSize(final JsonElement stackSizeElement) {
        final JsonElement value = stackSizeElement != null && stackSizeElement.isJsonObject()
                ? stackSizeElement.getAsJsonObject().get("value") : stackSizeElement;
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("max stack size is not numeric");
        }
        return validatedMaxStackSize(value.getAsDouble());
    }

    private static int validatedMaxStackSize(final double maxStackSize) {
        if (!Double.isFinite(maxStackSize) || maxStackSize < 1D || maxStackSize > MAX_STACK_SIZE || maxStackSize != Math.rint(maxStackSize)) {
            throw new IllegalArgumentException("max stack size is not an integer between 1 and " + MAX_STACK_SIZE);
        }
        return (int) maxStackSize;
    }

    public ItemDefinition get(final String identifier) {
        return this.items.get(identifier);
    }

    public void remove(final String identifier) {
        this.items.remove(identifier);
    }

    public static class ItemDefinition {

        private final String identifier;
        private final boolean networkDefinition;
        private String iconComponent;
        private String displayNameComponent;
        private Integer armorProtection;
        private Integer maxStackSize;

        public ItemDefinition(final String identifier) {
            this(identifier, false);
        }

        public ItemDefinition(final String identifier, final boolean networkDefinition) {
            this.identifier = identifier;
            this.networkDefinition = networkDefinition;
        }

        public String identifier() {
            return this.identifier;
        }

        public boolean networkDefinition() {
            return this.networkDefinition;
        }

        public String iconComponent() {
            return this.iconComponent;
        }

        public String displayNameComponent() {
            return this.displayNameComponent;
        }

        public Integer armorProtection() {
            return this.armorProtection;
        }

        public Integer maxStackSize() {
            return this.maxStackSize;
        }

    }

}
