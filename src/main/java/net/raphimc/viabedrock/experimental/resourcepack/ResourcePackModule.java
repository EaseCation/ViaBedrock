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
package net.raphimc.viabedrock.experimental.resourcepack;

import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePacksStorage;

/**
 * Registers fork-specific resource pack rewriters and handles post-pack-stack initialization.
 */
public class ResourcePackModule implements FeatureModule {

    public ResourcePackModule() {
        ResourcePackRewriter.registerRewriter(new UITextureResourceRewriter());
    }

    @Override
    public void onResourcePackStackSet(final UserConnection user) {
        final ResourcePacksStorage resourcePacksStorage = user.get(ResourcePacksStorage.class);
        ResourcePackRewriter.initRuntimeData(resourcePacksStorage);
    }

}
