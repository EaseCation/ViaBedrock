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
package net.raphimc.viabedrock.api.model.container;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;

/**
 * MOT {@code TradeInventory} is three slots (buyA, buyB, unused result).
 * Villagers are opened with {@code UPDATE_TRADE} rather than {@code CONTAINER_OPEN},
 * so this container has no block position and is not distance-closed by a block tag.
 */
public class TradeContainer extends Container {

    public static final byte MOT_CLOSE_CONTAINER_ID = -1;
    public static final int JAVA_WINDOW_ID = 500;

    public TradeContainer(final UserConnection user, final byte containerId, final TextComponent title) {
        super(user, unsignedWindowId(containerId), ContainerType.TRADE, title, null, 3);
    }

    static byte unsignedWindowId(final byte windowId) {
        // MOT UPDATE_TRADE/INVENTORY_CONTENT use unsigned 500. The packet BYTE is -12.
        return windowId == 0 ? (byte) JAVA_WINDOW_ID : windowId;
    }

    @Override
    public int javaContainerId() {
        // MOT assigns window 500. Java MERCHANT_OFFERS/CONTAINER_CLICK are VAR_INT,
        // so keep the untruncated id instead of the signed-byte 244/-12 alias.
        return JAVA_WINDOW_ID;
    }

    @Override
    public byte bedrockCloseContainerId() {
        // MOT looks up window 500, but CONTAINER_CLOSE only carries a signed byte.
        // The 860 villager path therefore treats client close -1 as "close trade".
        return MOT_CLOSE_CONTAINER_ID;
    }
}
