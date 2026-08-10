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
package net.raphimc.viabedrock.api.model.entity;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.AttributeModifierOperation;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.AttributeOperands;
import net.raphimc.viabedrock.protocol.model.EntityAttribute;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivingEntityMovementAttributeTest {

    private static final EntityAttribute.Modifier SPRINTING = new EntityAttribute.Modifier(
            "d208fc00-42aa-4aad-9276-d5446530de43",
            "Sprinting speed boost",
            0.3F,
            AttributeModifierOperation.OPERATION_MULTIPLY_TOTAL,
            AttributeOperands.OPERAND_CURRENT,
            false);

    @Test
    void separatesSprintingModifierFromJavaMovementBase() {
        final EntityAttribute attribute = movementAttribute(0.13F, SPRINTING);

        final LivingEntity.JavaMovementAttribute translation = LivingEntity.javaMovementAttribute(attribute);

        assertEquals(0.1F, translation.baseValue(), 1E-6F);
        assertTrue(translation.sprinting());
    }

    @Test
    void recognizesSprintingModifierUuidCaseInsensitively() {
        final EntityAttribute.Modifier uppercase = new EntityAttribute.Modifier(
                SPRINTING.id().toUpperCase(),
                SPRINTING.name(),
                SPRINTING.amount(),
                SPRINTING.operation(),
                SPRINTING.operand(),
                SPRINTING.isSerializable());

        final LivingEntity.JavaMovementAttribute translation = LivingEntity.javaMovementAttribute(
                movementAttribute(0.13F, uppercase));

        assertEquals(0.1F, translation.baseValue(), 1E-6F);
        assertTrue(translation.sprinting());
    }

    @Test
    void keepsNonSprintingModifiersFoldedIntoJavaMovementBase() {
        final EntityAttribute.Modifier speed = new EntityAttribute.Modifier(
                "91aeaa56-376b-4498-935b-2f7f68070635",
                "MovementSpeed",
                0.2F,
                AttributeModifierOperation.OPERATION_MULTIPLY_TOTAL,
                AttributeOperands.OPERAND_CURRENT,
                false);

        final LivingEntity.JavaMovementAttribute translation = LivingEntity.javaMovementAttribute(
                movementAttribute(0.156F, speed, SPRINTING));

        assertEquals(0.12F, translation.baseValue(), 1E-6F);
        assertTrue(translation.sprinting());
    }

    @Test
    void leavesWalkingMovementWithoutJavaSprintModifier() {
        final LivingEntity.JavaMovementAttribute translation = LivingEntity.javaMovementAttribute(
                movementAttribute(0.1F));

        assertEquals(0.1F, translation.baseValue(), 1E-6F);
        assertFalse(translation.sprinting());
    }

    private static EntityAttribute movementAttribute(final float currentValue,
                                                     final EntityAttribute.Modifier... modifiers) {
        return new EntityAttribute("minecraft:movement", currentValue, 0F, Float.MAX_VALUE,
                0.1F, 0F, Float.MAX_VALUE, modifiers);
    }
}
