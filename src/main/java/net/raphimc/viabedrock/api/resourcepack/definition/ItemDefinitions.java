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

    static final int DEFAULT_USE_DURATION_TICKS = 32;
    static final int MAX_USE_DURATION_TICKS = 72_000;

    private final Map<String, ItemDefinition> items = new HashMap<>();
    private final Set<String> malformedArmorWarnings = new HashSet<>();
    private final Set<String> malformedItemUseWarnings = new HashSet<>();
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
        if (components.has("minecraft:use_animation") && components.get("minecraft:use_animation").isJsonPrimitive()) {
            itemDefinition.useAnimation = UseAnimation.byName(components.get("minecraft:use_animation").getAsString());
        }
        this.items.put(identifier, itemDefinition);
    }

    public void addFromNetworkTag(final String identifier, final CompoundTag tag) {
        final ItemDefinition itemDefinition = new ItemDefinition(identifier, true);
        final ItemDefinition existingDefinition = this.items.get(identifier);
        if (existingDefinition != null) {
            itemDefinition.copyPresentationFrom(existingDefinition);
        }
        if (tag.get("components") instanceof CompoundTag components) {
            if (components.get("item_properties") instanceof CompoundTag itemProperties) {
                if (itemProperties.get("minecraft:icon") instanceof CompoundTag icon) {
                    if (icon.get("textures") instanceof CompoundTag texture) {
                        if (texture.get("default") instanceof StringTag defaultTexture) {
                            itemDefinition.iconComponent = defaultTexture.getValue();
                        }
                    }
                }
                if (itemProperties.get("use_animation") instanceof NumberTag animationTag) {
                    final UseAnimation animation = UseAnimation.byBedrockId(animationTag.asInt());
                    if (animation != null) {
                        itemDefinition.useAnimation = animation;
                    }
                }
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

            final Tag foodComponent = components.get("minecraft:food");
            if (foodComponent instanceof CompoundTag) {
                itemDefinition.consumable = true;
                final Tag durationTag = components.get("minecraft:use_duration") != null
                        ? components.get("minecraft:use_duration")
                        : components.get("item_properties") instanceof CompoundTag itemProperties ? itemProperties.get("use_duration") : null;
                if (durationTag != null) {
                    try {
                        itemDefinition.useDurationTicks = parseUseDurationTicks(durationTag);
                    } catch (IllegalArgumentException e) {
                        this.warnInvalidItemUse(identifier, e.getMessage());
                    }
                }
            } else if (foodComponent != null) {
                this.warnInvalidItemUse(identifier, "minecraft:food component is not a compound tag");
            }
        }
        this.items.put(identifier, itemDefinition);
    }

    private void warnInvalidItemUse(final String identifier, final String reason) {
        if (this.malformedItemUseWarnings.add(identifier)) {
            this.warningLogger.accept("Invalid consumable components for item " + identifier + ": " + reason);
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

    static int parseUseDurationTicks(final Tag useDurationTag) {
        if (!(useDurationTag instanceof NumberTag numberTag)) {
            throw new IllegalArgumentException("use duration is not numeric");
        }

        final double useDuration = numberTag.asDouble();
        if (!Double.isFinite(useDuration) || useDuration <= 0D || useDuration > MAX_USE_DURATION_TICKS || useDuration != Math.rint(useDuration)) {
            throw new IllegalArgumentException("use duration is not an integer between 1 and " + MAX_USE_DURATION_TICKS + " ticks");
        }
        return (int) useDuration;
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
        private boolean consumable;
        private int useDurationTicks = DEFAULT_USE_DURATION_TICKS;
        private UseAnimation useAnimation;

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

        public ItemUseDefinition itemUseDefinition() {
            if (!this.consumable) {
                return null;
            }
            return new ItemUseDefinition(this.useDurationTicks, this.useAnimation != null ? this.useAnimation : UseAnimation.EAT);
        }

        private void copyPresentationFrom(final ItemDefinition definition) {
            this.iconComponent = definition.iconComponent;
            this.displayNameComponent = definition.displayNameComponent;
            this.useAnimation = definition.useAnimation;
        }

    }

    public record ItemUseDefinition(int useDurationTicks, UseAnimation animation) {
    }

    public enum UseAnimation {
        EAT(1, "minecraft:entity.generic.eat", true),
        DRINK(2, "minecraft:entity.generic.drink", false);

        private final int javaId;
        private final String soundIdentifier;
        private final boolean consumeParticles;

        UseAnimation(final int javaId, final String soundIdentifier, final boolean consumeParticles) {
            this.javaId = javaId;
            this.soundIdentifier = soundIdentifier;
            this.consumeParticles = consumeParticles;
        }

        public int javaId() {
            return this.javaId;
        }

        public String soundIdentifier() {
            return this.soundIdentifier;
        }

        public boolean consumeParticles() {
            return this.consumeParticles;
        }

        static UseAnimation byName(final String name) {
            for (UseAnimation animation : values()) {
                if (animation.name().equalsIgnoreCase(name)) {
                    return animation;
                }
            }
            return null;
        }

        static UseAnimation byBedrockId(final int id) {
            for (UseAnimation animation : values()) {
                if (animation.javaId == id) {
                    return animation;
                }
            }
            return null;
        }
    }

}
