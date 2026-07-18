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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.libs.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemDefinitionsOverlayTest {

    @Test
    void networkDefinitionsStayInsideTheirSessionOverlay() {
        final ItemDefinitions base = new ItemDefinitions(message -> fail(message));
        final JsonObject components = new JsonObject();
        components.addProperty("minecraft:icon", "base_icon");
        base.addFromResourceComponents("test:item", components);

        final ItemDefinitions firstSession = ItemDefinitions.sessionOverlay(base);
        final ItemDefinitions secondSession = ItemDefinitions.sessionOverlay(base);
        firstSession.addFromNetworkTag("test:item", new CompoundTag());

        assertTrue(firstSession.get("test:item").networkDefinition());
        assertFalse(secondSession.get("test:item").networkDefinition());
        assertFalse(base.get("test:item").networkDefinition());
        assertEquals("base_icon", firstSession.get("test:item").iconComponent());
    }

}
