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
package net.raphimc.viabedrock.api.util;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.model.Position3f;

import java.util.Locale;
import java.util.logging.Level;

/**
 * Opt-in movement/dimension-switch diagnostic logging. Enable with {@code -Dviabedrock.debug.movement=true}.
 * <p>
 * Used to locate why player movement is dropped (server doesn't move) after a world/server switch. This class
 * only reads state and writes log lines; it never changes packet handling. When the switch is off, the
 * {@code static final} {@link #ENABLED} flag lets the JIT eliminate all guarded call sites.
 */
public final class MovementDebug {

    public static final boolean ENABLED = Boolean.getBoolean("viabedrock.debug.movement");

    private MovementDebug() {
    }

    public static void log(final String playerName, final String message) {
        ViaBedrock.getPlatform().getLogger().log(Level.INFO, "[VB-MOVE] " + playerName + " | " + message);
    }

    public static String fmt(final Position3f position) {
        if (position == null) {
            return "<none>";
        }
        return String.format(Locale.ROOT, "(%.1f,%.1f,%.1f)", position.x(), position.y(), position.z());
    }

}
