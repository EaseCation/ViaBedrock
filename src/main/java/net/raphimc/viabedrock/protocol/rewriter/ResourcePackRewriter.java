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
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.viaversion.libs.gson.JsonObject;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.resourcepack.EntityDefinitions;
import net.raphimc.viabedrock.api.model.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomAttachableResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomEntityResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomItemTextureResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.GlyphSheetResourceRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.UITextureResourceRewriter;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.ResourcePacksStorage;
import org.cube.converter.converter.enums.RotationType;
import org.cube.converter.model.element.Parent;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import org.cube.converter.model.impl.java.JavaItemModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class ResourcePackRewriter {

    private static final List<Rewriter> REWRITERS = new ArrayList<>();

    static {
        REWRITERS.add(new GlyphSheetResourceRewriter());
        REWRITERS.add(new CustomItemTextureResourceRewriter());
        REWRITERS.add(new CustomAttachableResourceRewriter());
        REWRITERS.add(new CustomEntityResourceRewriter());
        REWRITERS.add(new UITextureResourceRewriter());
    }

    public static ResourcePack.Content bedrockToJava(final ResourcePacksStorage resourcePacksStorage) {
        final ResourcePack.Content javaContent = new ResourcePack.Content();

        for (Rewriter rewriter : REWRITERS) {
            rewriter.apply(resourcePacksStorage, javaContent);
        }

        javaContent.putJson("pack.mcmeta", createPackManifest());

        final ChannelStorage channelStorage = resourcePacksStorage.user().get(ChannelStorage.class);
        if (channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
            for (ResourcePack pack : resourcePacksStorage.getPacks()) {
                try {
                    javaContent.put("bedrock/" + pack.packId() + ".mcpack", pack.content().toZip());
                } catch (IOException e) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to put bedrock pack " + pack.packId() + " into java resource pack", e);
                }
            }
        }

        return javaContent;
    }

    private static JsonObject createPackManifest() {
        final JsonObject pack = new JsonObject();
        pack.addProperty("description", "ViaBedrock Resource Pack");
        pack.addProperty("min_format", ProtocolConstants.JAVA_PACK_VERSION);
        pack.addProperty("max_format", ProtocolConstants.JAVA_PACK_VERSION);
        final JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    /**
     * Initialize runtime data needed for custom entity rendering.
     * This must be called after setPackStack() to ensure data is available regardless of
     * whether the Java client downloads or caches the resource pack.
     */
    public static void initRuntimeData(final ResourcePacksStorage resourcePacksStorage) {
        if (!ViaBedrock.getConfig().shouldEnableServerEntityAnimation()) {
            return; // Server-side animation disabled, skip BedrockMotion initialization
        }
        initBedrockMotionPackManager(resourcePacksStorage);
        initCustomEntityBoneData(resourcePacksStorage);
    }

    /**
     * Populate per-bone metadata (bone names and scales) for all custom entities.
     * This data is needed by CustomEntity.spawn() to create per-bone Display Entities.
     */
    private static void initCustomEntityBoneData(final ResourcePacksStorage resourcePacksStorage) {
        if (resourcePacksStorage.getEntities() == null || resourcePacksStorage.getModels() == null) return;

        for (Map.Entry<String, EntityDefinitions.EntityDefinition> entityEntry : resourcePacksStorage.getEntities().entities().entrySet()) {
            final EntityDefinitions.EntityDefinition entityDefinition = entityEntry.getValue();
            for (Map.Entry<String, String> modelEntry : entityDefinition.entityData().getGeometries().entrySet()) {
                final BedrockGeometryModel bedrockGeometry = resourcePacksStorage.getModels().entityModels().get(modelEntry.getValue());
                if (bedrockGeometry == null) continue;

                for (Map.Entry<String, String> textureEntry : entityDefinition.entityData().getTextures().entrySet()) {
                    final String baseKey = entityEntry.getKey() + "_" + modelEntry.getKey() + "_" + textureEntry.getKey();

                    final List<String> boneNames = new ArrayList<>();
                    for (Parent bone : bedrockGeometry.getParents()) {
                        if (bone.getCubes().isEmpty()) continue;

                        final String boneName = bone.getName().toLowerCase();
                        try {
                            final BedrockGeometryModel perBoneGeometry = new BedrockGeometryModel(
                                    bedrockGeometry.getIdentifier() + "_" + boneName,
                                    bedrockGeometry.getTextureSize());
                            final Parent clonedBone = bone.clone();
                            clonedBone.setParent(null);
                            perBoneGeometry.getParents().add(clonedBone);

                            final String javaTexturePath = "viabedrock:item/entity/" +
                                    net.raphimc.viabedrock.api.util.StringUtil.makeIdentifierValueSafe(
                                            textureEntry.getValue().replace("textures/", ""));
                            final JavaItemModel itemModel = perBoneGeometry.toJavaItemModel(
                                    javaTexturePath, RotationType.HACKY_POST_1_21_6);

                            final String boneKey = baseKey + "_" + boneName;
                            final float safeScale = Float.isFinite(itemModel.getScale()) ? itemModel.getScale() : 1.0f;
                            resourcePacksStorage.getConverterData().put("ce_" + boneKey + "_scale", safeScale);
                            boneNames.add(boneName);
                        } catch (Throwable e) {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                                    "Failed to compute per-bone metadata for " + boneName + " in " + baseKey, e);
                        }
                    }

                    resourcePacksStorage.getConverterData().put("ce_" + baseKey + "_bones", boneNames);
                }
            }
        }
    }

    /**
     * Create a BedrockMotion PackManager from ViaBedrock's resource packs.
     * This PackManager provides animation/controller definitions for server-side entity animation.
     * Stored in converterData for access by CustomEntity's ServerEntityTicker.
     */
    private static void initBedrockMotionPackManager(final ResourcePacksStorage resourcePacksStorage) {
        try {
            final List<Content> contents = new ArrayList<>();
            for (ResourcePack pack : resourcePacksStorage.getPackStackBottomToTop()) {
                try {
                    contents.add(new Content(pack.content().toZip()));
                } catch (IOException e) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to convert pack for BedrockMotion", e);
                }
            }

            if (!contents.isEmpty()) {
                final PackManager packManager = new PackManager(contents);
                resourcePacksStorage.getConverterData().put("bedrockmotion_pack_manager", packManager);
                ViaBedrock.getPlatform().getLogger().info("Initialized BedrockMotion PackManager with " + contents.size() + " pack(s)");
            }
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to initialize BedrockMotion PackManager", e);
        }
    }

    public interface Rewriter {

        void apply(final ResourcePacksStorage resourcePacksStorage, final ResourcePack.Content javaContent);

    }

}
