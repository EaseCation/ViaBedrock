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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.experimental.inventory.ItemUseHandContext;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.Set;
import java.util.logging.Level;

public final class JavaBlockUseTrace extends StoredObject {

    private static final String PROPERTY = "easecation.debugJavaBlockUse";
    private static final int MAX_EVENTS = 160;
    private static final long RECENT_USE_NANOS = 2_000_000_000L;

    private int emitted;
    private long lastBlockUseNanos;

    public JavaBlockUseTrace(final UserConnection user) {
        super(user);
    }

    public void traceUse(final String phase, final ItemUseHandContext context, final ItemRewriter itemRewriter,
                         final boolean continuous, final int sequence, final BlockPosition position) {
        if (!this.enabled()) {
            return;
        }
        if (context.item().blockRuntimeId() != 0) {
            this.lastBlockUseNanos = System.nanoTime();
        }
        this.log("phase={0} hand={1} item={2} count={3} blockRuntimeId={4} continuous={5} sequence={6} position={7}",
                phase, context.hand(), itemRewriter.bedrockIdentifier(context.item()), context.item().amount(),
                context.item().blockRuntimeId(), continuous, sequence, position);
    }

    public void traceJavaItem(final String identifier, final Item item) {
        if (!this.enabled() || !"minecraft:white_wool".equals(identifier)) {
            return;
        }
        this.log("phase=java-item identifier={0} javaId={1} amount={2} consumable={3} food={4} cooldown={5} maxStack={6}",
                identifier, item.identifier(), item.amount(),
                item.dataContainer().get(StructuredDataKey.CONSUMABLE1_21_2),
                item.dataContainer().get(StructuredDataKey.FOOD1_21_2),
                item.dataContainer().get(StructuredDataKey.USE_COOLDOWN),
                item.dataContainer().get(StructuredDataKey.MAX_STACK_SIZE));
    }

    public void traceAck(final String phase, final BlockPosition position, final int sequence) {
        if (!this.isRecentBlockUse()) {
            return;
        }
        this.log("phase={0} sequence={1} position={2}", phase, sequence, position);
    }

    public void traceActorFlags(final Set<ActorFlags> flags, final ClientPlayerEntity clientPlayer) {
        if (!this.isRecentBlockUse()) {
            return;
        }
        this.log("phase=actor-flags usingItem={0} blocking={1} trackedUsing={2} trackedHand={3}",
                flags.contains(ActorFlags.USINGITEM), flags.contains(ActorFlags.BLOCKING),
                clientPlayer.isUsingItem(), clientPlayer.usingItemHand());
    }

    private boolean isRecentBlockUse() {
        return this.enabled() && System.nanoTime() - this.lastBlockUseNanos <= RECENT_USE_NANOS;
    }

    private boolean enabled() {
        return Boolean.getBoolean(PROPERTY) && this.emitted < MAX_EVENTS;
    }

    private void log(final String message, final Object... arguments) {
        if (this.emitted >= MAX_EVENTS) {
            return;
        }
        this.emitted++;
        ViaBedrock.getPlatform().getLogger().log(Level.INFO,
                "[JavaBlockUseTrace] player=" + this.user().getProtocolInfo().getUsername() + " " + message,
                arguments);
    }
}
