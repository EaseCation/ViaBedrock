/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.cache;

import java.util.Objects;

/**
 * One ordered stack mount, binding a declared alias to exact content and a subpack selection.
 */
public record PackMount(PackAlias alias, ContentDigest contentDigest, String subpack) {

    public PackMount {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(contentDigest, "contentDigest");
        Objects.requireNonNull(subpack, "subpack");
        DigestSupport.strictUtf8(subpack, "subpack");
    }

    public PackMount(final PackAlias alias, final ContentDigest contentDigest) {
        this(alias, contentDigest, "");
    }

}
