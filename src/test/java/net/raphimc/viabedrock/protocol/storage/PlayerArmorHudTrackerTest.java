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
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.api.model.container.player.ArmorContainer;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerArmorHudTrackerTest {

    @Test
    void keepsDirtyBeforeLoginThenDeduplicatesAndForceResends() {
        final AtomicBoolean ready = new AtomicBoolean();
        final AtomicInteger value = new AtomicInteger(5);
        final List<Integer> sent = new ArrayList<>();
        final PlayerArmorHudTracker tracker = new PlayerArmorHudTracker(value::get, ready::get, sent::add);

        assertFalse(tracker.markDirty());
        assertTrue(tracker.dirty());
        ready.set(true);
        assertTrue(tracker.syncIfReady());
        assertEquals(List.of(5), sent);
        assertFalse(tracker.markDirty());
        assertEquals(List.of(5), sent);
        assertTrue(tracker.forceSync());
        assertEquals(List.of(5, 5), sent);
    }

    @Test
    void clampsValuesAndRetainsDirtyStateWhenSendingFails() {
        final AtomicInteger attempts = new AtomicInteger();
        final PlayerArmorHudTracker tracker = new PlayerArmorHudTracker(() -> 99, () -> true, value -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("send failed");
        });

        assertFalse(tracker.syncIfReady());

        assertEquals(1, attempts.get());
        assertEquals(20, tracker.currentValue());
        assertEquals(-1, tracker.lastSentValue());
        assertTrue(tracker.dirty());
    }

    @Test
    void armorContainerBatchesFullContentAndNotifiesSlotAndClearUpdates() {
        final UserConnection user = createStoredObjectUser();
        final AtomicInteger resolvedValue = new AtomicInteger();
        final List<Integer> sent = new ArrayList<>();
        final PlayerArmorHudTracker tracker = new PlayerArmorHudTracker(resolvedValue::incrementAndGet, () -> true, sent::add);
        user.put(tracker);
        final ArmorContainer armor = new ArmorContainer(user);

        armor.setItems(new BedrockItem[]{new BedrockItem(1), new BedrockItem(2), new BedrockItem(3), new BedrockItem(4)});
        assertEquals(List.of(1), sent);

        armor.setItem(0, new BedrockItem(5));
        assertEquals(List.of(1, 2), sent);

        armor.clearItems();
        assertEquals(List.of(1, 2, 3), sent);
    }

    private static UserConnection createStoredObjectUser() {
        final Map<Class<?>, StorableObject> storedObjects = new HashMap<>();
        return (UserConnection) Proxy.newProxyInstance(UserConnection.class.getClassLoader(), new Class<?>[]{UserConnection.class}, (proxy, method, args) -> switch (method.getName()) {
            case "put" -> {
                final StorableObject object = (StorableObject) args[0];
                storedObjects.put(object.getClass(), object);
                yield null;
            }
            case "get" -> storedObjects.get((Class<?>) args[0]);
            case "has" -> storedObjects.containsKey((Class<?>) args[0]);
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "StoredObjectUser";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

}
