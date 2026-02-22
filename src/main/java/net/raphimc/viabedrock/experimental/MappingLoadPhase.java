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
package net.raphimc.viabedrock.experimental;

/**
 * Phases during BedrockMappingData.load() where feature modules can inject custom mappings.
 * The order matches the initialization sequence in the load method.
 */
public enum MappingLoadPhase {

    /**
     * After vanilla block states are loaded into javaBlockStates BiMap.
     * Custom block state mappings should be added here.
     */
    AFTER_BLOCK_STATES,

    /**
     * After bedrockToJavaEntities map is populated.
     * Custom entity type fallback mappings should be loaded here.
     */
    AFTER_ENTITY_MAPPINGS,

    /**
     * After javaBlockEntities map is populated.
     * Custom block entity type mappings should be loaded here.
     */
    AFTER_BLOCK_ENTITIES,

    /**
     * After all mappings are fully loaded.
     * Additional data files (e.g., block_light_data.nbt) should be loaded here.
     */
    COMPLETE

}
