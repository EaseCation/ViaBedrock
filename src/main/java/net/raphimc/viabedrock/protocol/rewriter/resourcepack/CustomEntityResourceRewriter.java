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
package net.raphimc.viabedrock.protocol.rewriter.resourcepack;

import com.viaversion.viaversion.api.minecraft.item.data.ItemModel;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.definition.EntityDefinitions;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.cube.converter.converter.enums.RotationType;
import org.cube.converter.model.element.Parent;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import org.cube.converter.model.impl.java.JavaItemModel;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class CustomEntityResourceRewriter extends ItemModelResourceRewriter {

    private static final String SUB_FOLDER = "entities";

    public static ItemModel getItemModel(final String entityIdentifier) {
        return new ItemModel(Key.of("viabedrock", SUB_FOLDER + '/' + StringUtil.makeIdentifierValueSafe(entityIdentifier)));
    }

    public CustomEntityResourceRewriter() {
        super(SUB_FOLDER);
    }

    @Override
    public void apply(final ResourcePackStorage resourcePackStorage, final Content javaContent) {
        for (Map.Entry<String, EntityDefinitions.EntityDefinition> entityEntry : resourcePackStorage.getEntities().entities().entrySet()) {
            final EntityDefinitions.EntityDefinition entityDefinition = entityEntry.getValue();
            final Map<String, JsonObject> javaModelDefinitions = new HashMap<>();
            for (String bedrockPath : entityDefinition.entityData().getTextures().values()) {
                for (ResourcePack pack : resourcePackStorage.getPackStackTopToBottom()) {
                    final Content.LazyImage texture = pack.content().getShortnameImage(bedrockPath);
                    if (texture != null) {
                        javaContent.putPngImage("assets/viabedrock/textures/" + this.getJavaTexturePath(bedrockPath) + ".png", texture);
                        break;
                    }
                }
            }
            for (Map.Entry<String, String> modelEntry : entityDefinition.entityData().getGeometries().entrySet()) {
                final BedrockGeometryModel bedrockGeometry = resourcePackStorage.getModels()
                        .getEntityModel(modelEntry.getValue());
                if (bedrockGeometry != null) {
                    for (Map.Entry<String, String> textureEntry : entityDefinition.entityData().getTextures().entrySet()) {
                        final String modelKey = modelEntry.getKey() + "_" + textureEntry.getKey();
                        final JavaItemModel itemModelData = bedrockGeometry.toJavaItemModel("viabedrock:" + this.getJavaTexturePath(textureEntry.getValue()), RotationType.POST_1_21_11);
                        javaModelDefinitions.put(modelKey, itemModelData.compile());

                        for (Parent bone : bedrockGeometry.getParents()) {
                            if (bone.getCubes().isEmpty()) {
                                continue;
                            }

                            final String boneName = bone.getName().toLowerCase();
                            try {
                                final BedrockGeometryModel perBoneGeometry = new BedrockGeometryModel(
                                        bedrockGeometry.getIdentifier() + "_" + boneName,
                                        bedrockGeometry.getTextureSize());
                                final Parent clonedBone = bone.clone();
                                clonedBone.setParent(null);
                                perBoneGeometry.getParents().add(clonedBone);

                                final JavaItemModel boneModelData = perBoneGeometry.toJavaItemModel(
                                        "viabedrock:" + this.getJavaTexturePath(textureEntry.getValue()),
                                        RotationType.HACKY_POST_1_21_6);
                                final String boneKey = entityEntry.getKey() + '_' + modelKey + '_' + boneName;
                                javaModelDefinitions.put(boneKey, boneModelData.compile());
                            } catch (Throwable e) {
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to generate per-bone model for " + boneName + " in " + entityEntry.getKey() + '_' + modelKey, e);
                            }
                        }
                    }
                }
            }
            this.putItemDefinition(javaContent, entityEntry.getKey(), javaModelDefinitions, resourcePackStorage.isSupportsFreeRotation());
        }
    }

}
