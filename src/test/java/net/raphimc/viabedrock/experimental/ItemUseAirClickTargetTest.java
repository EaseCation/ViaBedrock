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
package net.raphimc.viabedrock.experimental;

import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.model.Position3f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseAirClickTargetTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int WATER = 2;
    private static final int POWDER_SNOW = 3;
    private static final int FLOWING_WATER = 4;

    @Test
    void emptyBucketPicksUpWaterSourceAndPowderSnow() {
        final Map<BlockPosition, Integer> layer0 = new HashMap<>();
        layer0.put(new BlockPosition(0, 65, 2), WATER);
        final ItemUseAirClickTarget.WorldView world = world(layer0, Map.of());
        final ItemUseAirClickTarget.Hit water = ItemUseAirClickTarget.raytraceFluidSource(
                world, eye(0.5F, 65.62F, 0.5F), 0F, 0F, 5.0D, ItemUseAirClickTarget.pickupFluids("minecraft:bucket"));
        assertNotNull(water);
        assertEquals(new BlockPosition(0, 65, 2), water.pos());
        assertTrue(water.insideBlock());

        layer0.clear();
        layer0.put(new BlockPosition(0, 65, 2), POWDER_SNOW);
        final ItemUseAirClickTarget.Hit snow = ItemUseAirClickTarget.raytraceFluidSource(
                world, eye(0.5F, 65.62F, 0.5F), 0F, 0F, 5.0D, ItemUseAirClickTarget.pickupFluids("minecraft:bucket"));
        assertNotNull(snow);
        assertEquals(new BlockPosition(0, 65, 2), snow.pos());
    }

    @Test
    void glassBottleOnlyAcceptsWater() {
        final Map<BlockPosition, Integer> layer0 = new HashMap<>();
        layer0.put(new BlockPosition(0, 65, 2), POWDER_SNOW);
        final ItemUseAirClickTarget.WorldView world = world(layer0, Map.of());
        assertNull(ItemUseAirClickTarget.raytraceFluidSource(
                world, eye(0.5F, 65.62F, 0.5F), 0F, 0F, 5.0D, ItemUseAirClickTarget.pickupFluids("minecraft:glass_bottle")));
    }

    @Test
    void filledBucketPlacesAgainstLookedAtSolid() {
        final Map<BlockPosition, Integer> layer0 = new HashMap<>();
        layer0.put(new BlockPosition(0, 65, 2), STONE);
        final ItemUseAirClickTarget.WorldView world = world(layer0, Map.of());
        final ItemUseAirClickTarget.Hit hit = ItemUseAirClickTarget.raytracePlaceClick(
                world, eye(0.5F, 65.62F, 0.5F), 0F, 0F, 5.0D, "minecraft:water_bucket", null);
        assertNotNull(hit);
        assertEquals(new BlockPosition(0, 65, 2), hit.pos());
        assertEquals(BlockFace.NORTH, hit.face());
        assertEquals(false, hit.insideBlock());
    }

    @Test
    void boatAndLilyPadClickTheWaterSurfaceFromAbove() {
        final Map<BlockPosition, Integer> layer0 = new HashMap<>();
        layer0.put(new BlockPosition(0, 64, 0), WATER);
        final ItemUseAirClickTarget.WorldView world = world(layer0, Map.of());
        final ItemUseAirClickTarget.Hit boat = ItemUseAirClickTarget.raytracePlaceClick(
                world, eye(0.5F, 65.62F, 0.5F), 0F, 90F, 5.0D, "minecraft:oak_boat", Set.of("minecraft:boat"));
        assertNotNull(boat);
        assertEquals(new BlockPosition(0, 64, 0), boat.pos());
        assertEquals(BlockFace.TOP, boat.face());

        final ItemUseAirClickTarget.Hit lily = ItemUseAirClickTarget.raytracePlaceClick(
                world, eye(0.5F, 65.62F, 0.5F), 0F, 90F, 5.0D, "minecraft:waterlily", null);
        assertNotNull(lily);
        assertEquals(new BlockPosition(0, 64, 0), lily.pos());
        assertEquals(BlockFace.TOP, lily.face());
    }

    @Test
    void waterloggedLayer1StillCountsAsASource() {
        final Map<BlockPosition, Integer> layer1 = new HashMap<>();
        layer1.put(new BlockPosition(0, 65, 2), FLOWING_WATER);
        final ItemUseAirClickTarget.WorldView world = world(Map.of(), layer1);
        final ItemUseAirClickTarget.Hit hit = ItemUseAirClickTarget.raytraceFluidSource(
                world, eye(0.5F, 65.62F, 0.5F), 0F, 0F, 5.0D, Set.of(ItemUseAirClickTarget.Fluid.WATER));
        assertNotNull(hit);
        assertEquals(new BlockPosition(0, 65, 2), hit.pos());
    }

    private static Position3f eye(final float x, final float y, final float z) {
        return new Position3f(x, y, z);
    }

    private static ItemUseAirClickTarget.WorldView world(final Map<BlockPosition, Integer> layer0, final Map<BlockPosition, Integer> layer1) {
        return ItemUseAirClickTarget.from(
                pos -> layer0.getOrDefault(pos, AIR),
                pos -> layer1.getOrDefault(pos, AIR),
                ItemUseAirClickTargetTest::state,
                AIR
        );
    }

    private static BlockState state(final int id) {
        return switch (id) {
            case STONE -> new BlockState("stone", Map.of());
            case WATER -> new BlockState("water", Map.of("liquid_depth", "0"));
            case FLOWING_WATER -> new BlockState("flowing_water", Map.of("liquid_depth", "0"));
            case POWDER_SNOW -> new BlockState("powder_snow", Map.of());
            default -> new BlockState("air", Map.of());
        };
    }
}
