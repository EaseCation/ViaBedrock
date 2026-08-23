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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StorableObject;
import net.raphimc.viabedrock.api.util.JavaClientDevice;

public record HandshakeStorage(int protocolVersion, String hostname, int port, JavaClientDevice device) implements StorableObject {

    public HandshakeStorage(final int protocolVersion, final String hostname, final int port) {
        this(protocolVersion, hostname, port, JavaClientDevice.JAVA_EDITION);
    }

    public static HandshakeStorage fromHandshake(final int protocolVersion, final String rawHostname, final int port) {
        return new HandshakeStorage(
                protocolVersion,
                JavaClientDevice.stripHandshakeSuffix(rawHostname),
                port,
                JavaClientDevice.parseFromHandshake(rawHostname)
        );
    }

    public JavaClientDevice device() {
        return this.device != null ? this.device : JavaClientDevice.JAVA_EDITION;
    }

}
