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
import net.raphimc.viabedrock.api.resourcepack.definition.BlockDefinitions;
import net.raphimc.viabedrock.api.resourcepack.definition.TextureDefinitions;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

public class CustomBlockTextureResourceRewriter extends ItemModelResourceRewriter {

    public static final String CONVERTED_BLOCK_TEXTURES_KEY = "converted_block_textures";
    private static final String SUB_FOLDER = "block_textures";
    private static final String[] FACES = {"up", "down", "north", "south", "west", "east"};

    public static ItemModel getItemModel(final String blockIdentifier) {
        return new ItemModel(Key.of("viabedrock", SUB_FOLDER + "/" + StringUtil.makeIdentifierValueSafe(blockIdentifier)));
    }

    public CustomBlockTextureResourceRewriter() {
        super(SUB_FOLDER);
    }

    @Override
    public void apply(final ResourcePackStorage resourcePackStorage, final Content javaContent) {
        this.convert(resourcePackStorage, javaContent);
    }

    @Override
    public void initRuntimeData(final ResourcePackStorage resourcePackStorage) {
        resourcePackStorage.putRuntimeData(CONVERTED_BLOCK_TEXTURES_KEY, this.convert(resourcePackStorage, null));
    }

    @Override
    public ResourcePackRewriter.RuntimeDataScope runtimeDataScope() {
        return ResourcePackRewriter.RuntimeDataScope.SHARED;
    }

    @Override
    public String artifactFingerprint() {
        return "2";
    }

    public static boolean hasConvertedTexture(final ResourcePackStorage resourcePackStorage, final String blockIdentifier) {
        if (resourcePackStorage == null || blockIdentifier == null) {
            return false;
        }
        final Object converted = resourcePackStorage.getRuntimeData().get(CONVERTED_BLOCK_TEXTURES_KEY);
        return converted instanceof Map<?, ?> map && map.containsKey(blockIdentifier);
    }

    private Map<String, String> convert(final ResourcePackStorage resourcePackStorage, final Content javaContent) {
        final Map<String, String> converted = new LinkedHashMap<>();
        final TextureDefinitions textures = resourcePackStorage.getTextures();
        for (Map.Entry<String, BlockDefinitions.BlockDefinition> blockEntry : resourcePackStorage.getBlocks().blocks().entrySet()) {
            final BlockDefinitions.BlockDefinition block = blockEntry.getValue();
            if (block == null || !isCustomBlock(block.identifier()) || !block.hasTextures()) {
                continue;
            }
            final Map<String, String> facePaths = new LinkedHashMap<>();
            for (String face : FACES) {
                final String textureName = block.textures().face(face);
                final String texturePath = textures.firstTerrainPath(textureName);
                if (texturePath == null) {
                    break;
                }
                facePaths.put(face, texturePath);
                if (javaContent != null) {
                    copyTexture(resourcePackStorage, javaContent, texturePath);
                }
            }
            if (facePaths.size() != FACES.length) {
                continue;
            }
            if (javaContent != null) {
                final JsonObject cubeModel = cubeModel(facePaths);
                this.putItemDefinition(javaContent, block.identifier(), Map.of("0", cubeModel), resourcePackStorage.isSupportsFreeRotation());
            }
            converted.put(block.identifier(), facePaths.get("up"));
        }
        if (javaContent != null && !converted.isEmpty() && ViaBedrock.getPlatform() != null) {
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Converted " + converted.size() + " custom block texture(s) to Java models");
        }
        return Map.copyOf(converted);
    }

    private static boolean isCustomBlock(final String identifier) {
        return identifier != null && !identifier.startsWith("minecraft:");
    }

    private void copyTexture(final ResourcePackStorage resourcePackStorage, final Content javaContent, final String texturePath) {
        final String javaPath = "assets/viabedrock/textures/" + this.getJavaTexturePath(texturePath) + ".png";
        if (javaContent.contains(javaPath)) {
            return;
        }
        for (ResourcePack pack : resourcePackStorage.getPackStackTopToBottom()) {
            final Content.LazyImage texture = pack.content().getShortnameImage(texturePath);
            if (texture != null) {
                javaContent.putPngImage(javaPath, texture);
                return;
            }
        }
    }

    private JsonObject cubeModel(final Map<String, String> facePaths) {
        final JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:block/cube");
        final JsonObject texturesJson = new JsonObject();
        for (Map.Entry<String, String> face : facePaths.entrySet()) {
            texturesJson.addProperty(face.getKey(), "viabedrock:" + this.getJavaTexturePath(face.getValue()));
        }
        texturesJson.addProperty("particle", texturesJson.get("up").getAsString());
        model.add("textures", texturesJson);
        return model;
    }

}
