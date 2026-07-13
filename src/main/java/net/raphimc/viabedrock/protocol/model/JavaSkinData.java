/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.model;

import java.awt.image.BufferedImage;
import java.util.Objects;

public record JavaSkinData(BufferedImage skin, BufferedImage cape, boolean slim, String sourceId) {

    public JavaSkinData {
        Objects.requireNonNull(skin, "skin");
        Objects.requireNonNull(sourceId, "sourceId");
    }

}
