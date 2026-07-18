/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.definition;

import net.raphimc.viabedrock.ViaBedrock;

import java.util.logging.Level;

final class DefinitionLogger {

    private DefinitionLogger() {
    }

    static void warning(final String message, final Throwable throwable) {
        if (ViaBedrock.getPlatform() == null) {
            return;
        }
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, message, throwable);
    }

    static void warning(final String message) {
        if (ViaBedrock.getPlatform() == null) {
            return;
        }
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, message);
    }

}
