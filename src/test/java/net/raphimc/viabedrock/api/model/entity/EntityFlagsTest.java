/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.model.entity;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityFlagsTest {

    @Test
    void combinesFlagWordsWithoutSignExtension() {
        final long flags = Long.MIN_VALUE;
        final long flags2 = (1L << 24) | Long.MIN_VALUE;

        final BigInteger expected = BigInteger.ZERO
                .setBit(63)
                .setBit(88)
                .setBit(127);
        assertEquals(expected, Entity.combineEntityFlags(flags, flags2));
    }

    @Test
    void combinesEmptyAndPositiveFlagWords() {
        assertEquals(BigInteger.ZERO, Entity.combineEntityFlags(0L, 0L));
        assertEquals(BigInteger.ZERO.setBit(25).setBit(42), Entity.combineEntityFlags((1L << 25) | (1L << 42), 0L));
        assertEquals(BigInteger.ZERO.setBit(88), Entity.combineEntityFlags(0L, 1L << 24));
    }
}
