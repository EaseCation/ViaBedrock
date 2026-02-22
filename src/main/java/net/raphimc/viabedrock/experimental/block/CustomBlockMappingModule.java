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
package net.raphimc.viabedrock.experimental.block;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntOpenHashMap;
import com.viaversion.viaversion.libs.gson.Gson;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.MappingLoadPhase;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.rewriter.BlockEntityRewriter;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;

import java.io.File;
import java.io.FileReader;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Loads custom block state mappings and custom block entity type IDs exported by FabricRock.
 * Detects FabricRock client presence via channel registration.
 */
public class CustomBlockMappingModule implements FeatureModule {

    @Override
    public void onMappingsLoad(final BedrockMappingData data, final MappingLoadPhase phase) {
        if (phase == MappingLoadPhase.AFTER_BLOCK_STATES) {
            loadCustomBlockStateMappings(data);
        } else if (phase == MappingLoadPhase.AFTER_BLOCK_ENTITIES) {
            loadCustomBlockEntityTypeIds(data);
        }
    }

    @Override
    public void onChannelRegistered(final UserConnection user, final Set<String> channels) {
        if (channels.contains("fabricrock:confirm")) {
            user.get(GameSessionStorage.class).setHasFabricRock(true);
        }
    }

    private void loadCustomBlockStateMappings(final BedrockMappingData data) {
        final Int2IntMap customBlockEmitLight = new Int2IntOpenHashMap();
        final Int2IntMap customBlockFilterLight = new Int2IntOpenHashMap();
        final Int2IntMap customBlockFallbacks = new Int2IntOpenHashMap();
        ((Int2IntOpenHashMap) customBlockEmitLight).defaultReturnValue(-1);
        ((Int2IntOpenHashMap) customBlockFilterLight).defaultReturnValue(-1);
        ((Int2IntOpenHashMap) customBlockFallbacks).defaultReturnValue(1); // default: stone

        final File customMappingFile = new File(System.getProperty("user.dir"), "config/bedrock-loader/block_state_mappings.json");
        if (customMappingFile.exists()) {
            try {
                final JsonObject customMappingsRoot = new Gson().fromJson(new FileReader(customMappingFile), JsonObject.class);
                final int formatVersion = customMappingsRoot.has("format_version") ? customMappingsRoot.get("format_version").getAsInt() : 1;
                final JsonObject customMappings = customMappingsRoot.getAsJsonObject("mappings");

                int addedCount = 0;
                int skippedCount = 0;
                for (Map.Entry<String, JsonElement> entry : customMappings.entrySet()) {
                    final String bedrockStateStr = entry.getKey();
                    final JsonObject mapping = entry.getValue().getAsJsonObject();
                    final String javaStateStr = mapping.get("java_state").getAsString();
                    final int javaStateId = mapping.get("java_state_id").getAsInt();

                    // 1. Add to javaBlockStates BiMap
                    final BlockState javaBlockState = BlockState.fromString(javaStateStr);

                    boolean keyExists = data.getJavaBlockStates().containsKey(javaBlockState);
                    boolean valueExists = data.getJavaBlockStates().containsValue(javaStateId);

                    if (keyExists || valueExists) {
                        if (valueExists) {
                            BlockState existingState = data.getJavaBlockStates().inverse().get(javaStateId);
                            ViaBedrock.getPlatform().getLogger().warning(
                                "ID conflict: Custom block '" + javaStateStr + "' wants ID " + javaStateId +
                                ", but it's already used by '" + existingState + "'"
                            );
                        }
                        skippedCount++;
                    } else {
                        data.getJavaBlockStates().put(javaBlockState, javaStateId);
                    }

                    // 2. Add to bedrockToJavaBlockStates Map
                    final BlockState bedrockBlockState = BlockState.fromString(bedrockStateStr);
                    if (!data.getBedrockToJavaBlockStates().containsKey(bedrockBlockState)) {
                        data.getBedrockToJavaBlockStates().put(bedrockBlockState, javaBlockState);
                        if (!keyExists && !valueExists) {
                            addedCount++;
                        }
                    }

                    // 3. Read and store custom block light properties
                    if (mapping.has("light_emission")) {
                        final int lightEmission = mapping.get("light_emission").getAsInt();
                        if (lightEmission > 0) {
                            customBlockEmitLight.put(javaStateId, lightEmission);
                        }
                    }
                    if (mapping.has("light_filter")) {
                        final int lightFilter = mapping.get("light_filter").getAsInt();
                        if (lightFilter != 15) {
                            customBlockFilterLight.put(javaStateId, lightFilter);
                        }
                    }

                    // 4. Read fallback_block (format_version >= 2)
                    if (formatVersion >= 2 && mapping.has("fallback_block")) {
                        final String fallbackBlockStr = mapping.get("fallback_block").getAsString();
                        final BlockState fallbackBlockState = BlockState.fromString(fallbackBlockStr);
                        if (data.getJavaBlockStates().containsKey(fallbackBlockState)) {
                            customBlockFallbacks.put(javaStateId, data.getJavaBlockStates().get(fallbackBlockState).intValue());
                        } else {
                            ViaBedrock.getPlatform().getLogger().warning(
                                "Unknown fallback block '" + fallbackBlockStr + "' for custom block state ID " + javaStateId + ", using stone"
                            );
                        }
                    }
                }

                ViaBedrock.getPlatform().getLogger().info("Loaded " + addedCount + " custom block state mappings from FabricRock (format v" + formatVersion + ")" +
                    (skippedCount > 0 ? " (skipped " + skippedCount + " due to ID conflicts)" : "") +
                    ", fallbacks: " + customBlockFallbacks.size());
            } catch (Exception e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to load custom block state mappings", e);
            }
        }

        data.setCustomBlockEmitLight(customBlockEmitLight);
        data.setCustomBlockFilterLight(customBlockFilterLight);
        data.setCustomBlockFallbacks(customBlockFallbacks);
    }

    private void loadCustomBlockEntityTypeIds(final BedrockMappingData data) {
        final File customBlockEntityTypeIdsFile = new File(System.getProperty("user.dir"), "config/bedrock-loader/custom_block_entity_type_ids.json");
        if (customBlockEntityTypeIdsFile.exists()) {
            try {
                final JsonObject customBlockEntityRoot = new Gson().fromJson(new FileReader(customBlockEntityTypeIdsFile), JsonObject.class);
                final JsonObject customBlockEntityMappings = customBlockEntityRoot.getAsJsonObject("mappings");
                final ModBlockBlockEntityRewriter modBlockRewriter = new ModBlockBlockEntityRewriter();

                int loadedCount = 0;
                for (Map.Entry<String, JsonElement> entry : customBlockEntityMappings.entrySet()) {
                    final String blockIdentifier = Key.namespaced(entry.getKey());
                    final JsonObject mapping = entry.getValue().getAsJsonObject();
                    final int javaTypeId = mapping.get("java_type_id").getAsInt();
                    final String customTag = "mod_block:" + blockIdentifier;

                    // Store the mapping
                    data.getCustomBlockEntityTypeIds().put(blockIdentifier, javaTypeId);

                    // Register to javaBlockEntities: tag -> java type id (BiMap requires unique keys and values)
                    if (!data.getJavaBlockEntities().containsKey(customTag) && !data.getJavaBlockEntities().containsValue(javaTypeId)) {
                        data.getJavaBlockEntities().put(customTag, javaTypeId);
                    } else {
                        ViaBedrock.getPlatform().getLogger().warning(
                                "Block entity type conflict for " + blockIdentifier +
                                        " (tag=" + customTag + ", typeId=" + javaTypeId + "), skipping");
                        continue;
                    }

                    // Register to bedrockCustomBlockTags: block identifier -> tag
                    // (bypasses block_tags.json validation since custom blocks are not in bedrockBlockStatesByIdentifier)
                    if (!data.getBedrockCustomBlockTags().containsKey(blockIdentifier)) {
                        data.getBedrockCustomBlockTags().put(blockIdentifier, customTag);
                    }

                    // Register block entity rewriter
                    BlockEntityRewriter.registerRewriter(customTag, modBlockRewriter);

                    loadedCount++;
                }

                ViaBedrock.getPlatform().getLogger().info("Loaded " + loadedCount + " custom block entity type ID mappings from FabricRock");
            } catch (Exception e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to load custom block entity type ID mappings", e);
            }
        }
    }

}
