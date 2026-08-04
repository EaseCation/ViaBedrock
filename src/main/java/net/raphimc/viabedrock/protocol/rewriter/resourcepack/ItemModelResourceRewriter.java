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
import com.viaversion.viaversion.libs.gson.JsonPrimitive;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import org.cube.converter.converter.enums.RotationType;
import org.cube.converter.model.element.Cube;
import org.cube.converter.model.element.Parent;
import org.cube.converter.model.impl.java.JavaItemModel;

import java.util.Map;
import java.util.Set;

public abstract class ItemModelResourceRewriter implements ResourcePackRewriter.Rewriter {

    private static final float LEGACY_MODEL_CENTER = 8F;
    private static final float LEGACY_MODEL_RADIUS = 24F;
    private static final float LEGACY_MODEL_MIN = -16F;
    private static final float LEGACY_MODEL_MAX = 32F;
    private static final Set<String> LEGACY_ROTATION_AXES = Set.of("x", "y", "z");

    public static CustomModelData1_21_4 getCustomModelData(final String key) {
        return new CustomModelData1_21_4(new float[0], new boolean[0], new String[]{key}, new int[0]);
    }

    private final String subFolder;

    public ItemModelResourceRewriter(final String subFolder) {
        this.subFolder = subFolder;
    }

    protected void putItemDefinition(final Content javaContent, final String name, final Map<String, JsonObject> modelDefinitions, final boolean supportsFreeRotation) {
        if (modelDefinitions.isEmpty()) {
            return;
        }
        final String itemPath = this.subFolder + '/' + StringUtil.makeIdentifierValueSafe(name);
        final JsonArray modelCases = new JsonArray();
        for (Map.Entry<String, JsonObject> modelDefinition : modelDefinitions.entrySet()) {
            final String modelPath = itemPath + '/' + StringUtil.makeIdentifierValueSafe(modelDefinition.getKey());
            sanitizeLegacyElements(modelDefinition.getValue(), supportsFreeRotation);
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

    protected static RotationType rotationTypeFor(final boolean supportsFreeRotation) {
        return supportsFreeRotation ? RotationType.POST_1_21_11 : RotationType.HACKY_POST_1_21_6;
    }

    /**
     * Keeps pre-1.21.11 element coordinates inside the vanilla [-16, 32] cube. The model's
     * compensating display scale preserves its original world size.
     */
    public static JavaItemModel prepareModelForClient(final JavaItemModel model, final boolean supportsFreeRotation) {
        if (supportsFreeRotation) {
            return model;
        }

        float maxDistance = 0F;
        for (Parent parent : model.getParents()) {
            for (Cube cube : parent.getCubes().values()) {
                final var from = cube.getPosition().asJavaPosition(cube.getSize());
                final var to = from.add(cube.getSize());
                maxDistance = Math.max(maxDistance, maxDistanceFromLegacyCenter(from.getX(), from.getY(), from.getZ()));
                maxDistance = Math.max(maxDistance, maxDistanceFromLegacyCenter(to.getX(), to.getY(), to.getZ()));
            }
        }
        if (!Float.isFinite(maxDistance) || maxDistance <= LEGACY_MODEL_RADIUS) {
            return model;
        }

        final float geometryScale = Math.nextDown(LEGACY_MODEL_RADIUS) / maxDistance;
        for (Parent parent : model.getParents()) {
            parent.getPivot().scale(geometryScale);
            for (Cube cube : parent.getCubes().values()) {
                cube.getPosition().scale(geometryScale);
                cube.getSize().scale(geometryScale);
                cube.getPivot().scale(geometryScale);
            }
        }
        final float currentScale = Float.isFinite(model.getScale()) && model.getScale() > 0F
                ? model.getScale() : 1F;
        model.setScale(currentScale / geometryScale);
        return model;
    }

    private static float maxDistanceFromLegacyCenter(final float x, final float y, final float z) {
        return Math.max(Math.abs(x - LEGACY_MODEL_CENTER),
                Math.max(Math.abs(y - LEGACY_MODEL_CENTER), Math.abs(z - LEGACY_MODEL_CENTER)));
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
     * For clients that do not support free rotation, drop unsupported free rotations and malformed
     * or out-of-range legacy rotations. The converter normally emits a legacy-compatible model;
     * this final pass prevents one unexpected cube from invalidating the entire generated model.
     * It also clamps element bounds to the legacy codec's [-16, 32] interval as a final safeguard.
     */
    private static void sanitizeLegacyElements(final JsonObject model, final boolean supportsFreeRotation) {
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
            if (rotation != null && (!rotation.isJsonObject()
                    || isInvalidLegacyRotation(rotation.getAsJsonObject()))) {
                element.remove("rotation");
            }
            clampLegacyVector(element, "from");
            clampLegacyVector(element, "to");
        }
    }

    private static boolean isInvalidLegacyRotation(final JsonObject rotation) {
        if (rotation.has("x") || rotation.has("y") || rotation.has("z")) {
            return true;
        }
        final JsonElement angleElement = rotation.get("angle");
        final JsonElement axisElement = rotation.get("axis");
        if (angleElement == null || !angleElement.isJsonPrimitive()
                || !angleElement.getAsJsonPrimitive().isNumber()
                || axisElement == null || !axisElement.isJsonPrimitive()
                || !axisElement.getAsJsonPrimitive().isString()) {
            return true;
        }
        final double angle = angleElement.getAsDouble();
        return !Double.isFinite(angle) || Math.abs(angle) > 45D
                || !LEGACY_ROTATION_AXES.contains(axisElement.getAsString());
    }

    private static void clampLegacyVector(final JsonObject element, final String key) {
        final JsonElement vectorElement = element.get(key);
        if (vectorElement == null || !vectorElement.isJsonArray()
                || vectorElement.getAsJsonArray().size() != 3) {
            return;
        }
        final JsonArray vector = vectorElement.getAsJsonArray();
        for (int i = 0; i < vector.size(); i++) {
            final JsonElement coordinate = vector.get(i);
            if (!coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber()) {
                continue;
            }
            final double value = coordinate.getAsDouble();
            final double finiteValue = Double.isFinite(value) ? value : LEGACY_MODEL_CENTER;
            vector.set(i, new JsonPrimitive(Math.max(LEGACY_MODEL_MIN, Math.min(LEGACY_MODEL_MAX, finiteValue))));
        }
    }

    @Override
    public String artifactFingerprint() {
        return "2";
    }

}
