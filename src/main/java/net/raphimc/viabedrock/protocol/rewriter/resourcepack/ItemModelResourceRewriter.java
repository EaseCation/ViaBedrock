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

import com.viaversion.viaversion.api.minecraft.item.data.CustomModelData1_21_4;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;

import java.util.Map;

public abstract class ItemModelResourceRewriter implements ResourcePackRewriter.Rewriter {

    public static CustomModelData1_21_4 getCustomModelData(final String key) {
        return new CustomModelData1_21_4(new float[0], new boolean[0], new String[]{key}, new int[0]);
    }

    private final String subFolder;

    public ItemModelResourceRewriter(final String subFolder) {
        this.subFolder = subFolder;
    }

    protected void putItemDefinition(final Content javaContent, final String name, final Map<String, JsonObject> modelDefinitions, final boolean supportsFreeRotation) {
        final String itemPath = this.subFolder + '/' + StringUtil.makeIdentifierValueSafe(name);
        final JsonArray modelCases = new JsonArray();
        for (Map.Entry<String, JsonObject> modelDefinition : modelDefinitions.entrySet()) {
            final String modelPath = itemPath + '/' + StringUtil.makeIdentifierValueSafe(modelDefinition.getKey());
            sanitizeRotations(modelDefinition.getValue(), supportsFreeRotation);
            ensureParticleTexture(modelDefinition.getValue());
            javaContent.putJson("assets/viabedrock/models/" + modelPath + ".json", modelDefinition.getValue());

            final JsonObject model = new JsonObject();
            model.addProperty("type", "minecraft:model");
            model.addProperty("model", "viabedrock:" + modelPath);

            final JsonObject caseObj = new JsonObject();
            caseObj.addProperty("when", modelDefinition.getKey());
            caseObj.add("model", model);
            modelCases.add(caseObj);
        }

        final JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:select");
        model.addProperty("property", "minecraft:custom_model_data");
        model.add("cases", modelCases);
        final JsonObject itemDefinitionObj = new JsonObject();
        itemDefinitionObj.add("model", model);
        javaContent.putJson("assets/viabedrock/items/" + itemPath + ".json", itemDefinitionObj);
    }

    /**
     * Vanilla requires every geometry model (one with "elements" and no parent) to declare a
     * "particle" texture. CubeConverter only emits the numbered texture slots, so the vanilla
     * loader logs "Missing texture references ... particle" for every generated entity/attachable
     * model (thousands), synchronously stalling the resource reload. Point particle at the first
     * existing texture slot so the model is complete. Version-independent: particle is required on
     * all client versions.
     */
    private static void ensureParticleTexture(final JsonObject model) {
        if (!model.has("elements")) {
            return; // item/generated-style models (no elements) don't require a particle texture
        }
        final JsonElement texturesElement = model.get("textures");
        if (texturesElement == null || !texturesElement.isJsonObject()) {
            return;
        }
        final JsonObject textures = texturesElement.getAsJsonObject();
        if (textures.has("particle") || textures.size() == 0) {
            return;
        }
        final String firstKey = textures.keySet().iterator().next();
        textures.add("particle", textures.get(firstKey)); // reuse the same texture
    }

    protected String getJavaTexturePath(final String bedrockPath) {
        return "item/" + this.subFolder + '/' + StringUtil.makeIdentifierValueSafe(bedrockPath.replace("textures/", ""));
    }

    /**
     * CubeConverter emits element rotation in the free-rotation object form {x,y,z,origin} (legal only
     * on MC >= 1.21.11). On older clients the vanilla model deserializer rejects it ("Missing axis"),
     * which makes the whole model fail to load - and with thousands of generated entity/attachable
     * models that previously stalled the resource reload for minutes (synchronous error logging) and
     * timed the Bedrock connection out.
     * <p>
     * For clients that do not support free rotation, drop the rotation entirely (fall back to no
     * rotation == 0) for every element using the free-rotation form. The legacy single-axis form
     * {angle,axis,origin} is already valid on those clients and is left untouched. These models are
     * only used for the vanilla-client fallback rendering of Bedrock entities; clients with the
     * companion mod render natively and do not depend on the rotation here.
     */
    private static void sanitizeRotations(final JsonObject model, final boolean supportsFreeRotation) {
        if (supportsFreeRotation) {
            return; // MC >= 1.21.11: free rotation is legal, keep as-is
        }
        final JsonElement elementsElement = model.get("elements");
        if (elementsElement == null || !elementsElement.isJsonArray()) {
            return;
        }
        for (final JsonElement elementEntry : elementsElement.getAsJsonArray()) {
            if (!elementEntry.isJsonObject()) {
                continue;
            }
            final JsonObject element = elementEntry.getAsJsonObject();
            final JsonElement rotation = element.get("rotation");
            if (rotation != null && rotation.isJsonObject()) {
                final JsonObject rotationObj = rotation.getAsJsonObject();
                // Free-rotation form is identified by x/y/z; the legacy {angle,axis} form (valid here) is kept.
                if (rotationObj.has("x") || rotationObj.has("y") || rotationObj.has("z")) {
                    element.remove("rotation");
                }
            }
        }
    }

}
