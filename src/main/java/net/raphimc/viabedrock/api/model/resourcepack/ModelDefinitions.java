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
package net.raphimc.viabedrock.api.model.resourcepack;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.storage.ResourcePacksStorage;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import org.cube.converter.parser.bedrock.geometry.BedrockGeometryParser;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class ModelDefinitions {

    private final Map<String, BedrockGeometryModel> entityModels = new HashMap<>();

    public ModelDefinitions(final ResourcePacksStorage resourcePacksStorage) {
        // Load vanilla skin pack geometries first (e.g., geometry.humanoid.custom)
        // These are not in the regular pack stack but are needed by entities referencing vanilla geometries
        if (BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks() != null) {
            final ResourcePack skinPack = BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks().get("vanilla_skin_pack");
            if (skinPack != null) {
                final String geometryJson = skinPack.content().getString("geometry.json");
                if (geometryJson != null) {
                    try {
                        for (BedrockGeometryModel bedrockGeometry : BedrockGeometryParser.parse(geometryJson)) {
                            this.entityModels.put(bedrockGeometry.getIdentifier(), bedrockGeometry);
                        }
                    } catch (Throwable e) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to parse vanilla skin pack geometry", e);
                    }
                }
            }
        }

        // Load from pack stack (custom packs override vanilla)
        for (ResourcePack pack : resourcePacksStorage.getPackStackBottomToTop()) {
            for (String modelPath : pack.content().getFilesDeep("models/", ".json")) {
                try {
                    for (BedrockGeometryModel bedrockGeometry : BedrockGeometryParser.parse(pack.content().getString(modelPath))) {
                        if (modelPath.startsWith("models/entity/")) {
                            this.entityModels.put(bedrockGeometry.getIdentifier(), bedrockGeometry);
                        }
                    }
                } catch (Throwable e) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to parse model definition " + modelPath + " in pack " + pack.packId(), e);
                }
            }
        }
    }

    public BedrockGeometryModel getEntityModel(final String name) {
        return this.entityModels.get(name);
    }

    public Map<String, BedrockGeometryModel> entityModels() {
        return Collections.unmodifiableMap(this.entityModels);
    }

}
