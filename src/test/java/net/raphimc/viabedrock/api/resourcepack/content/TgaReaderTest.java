/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.content;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TgaReaderTest {

    @Test
    void shortnameLookupDecodesUncompressedTgaAndConvertsItToPng() {
        final InMemoryContent content = new InMemoryContent();
        content.put("textures/entity/balloon.tga", uncompressedTrueColorTga());

        final Content.LazyImage image = content.getShortnameImage("textures/entity/balloon");

        assertNotNull(image);
        final BufferedImage decoded = image.getImage();
        assertEquals(2, decoded.getWidth());
        assertEquals(2, decoded.getHeight());
        assertEquals(0xFFFF0000, decoded.getRGB(0, 0));
        assertEquals(0xFF00FF00, decoded.getRGB(1, 0));
        assertEquals(0xFF0000FF, decoded.getRGB(0, 1));
        assertEquals(0xFFFFFFFF, decoded.getRGB(1, 1));
        assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47},
                java.util.Arrays.copyOf(image.getPngBytes(), 4));
    }

    @Test
    void decodesRleTgaWithAlpha() {
        final BufferedImage image = TgaReaderTest.decode(rleTrueColorTga());

        assertEquals(2, image.getWidth());
        assertEquals(2, image.getHeight());
        assertEquals(0x280A141E, image.getRGB(0, 0));
        assertEquals(0x280A141E, image.getRGB(1, 1));
    }

    @Test
    void rejectsNonImageData() {
        final InMemoryContent content = new InMemoryContent();
        content.put("textures/broken.tga", new byte[18]);

        assertNull(content.getShortnameImage("textures/broken"));
    }

    private static BufferedImage decode(final byte[] data) {
        try {
            return TgaReader.read(data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] uncompressedTrueColorTga() {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(header(2, 2, 2, 24, 0x20, 3));
        output.writeBytes(new byte[]{1, 2, 3});
        output.writeBytes(new byte[]{0, 0, (byte) 0xFF});
        output.writeBytes(new byte[]{0, (byte) 0xFF, 0});
        output.writeBytes(new byte[]{(byte) 0xFF, 0, 0});
        output.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        return output.toByteArray();
    }

    private static byte[] rleTrueColorTga() {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(header(10, 2, 2, 32, 0x28, 0));
        output.write(0x83);
        output.writeBytes(new byte[]{30, 20, 10, 40});
        return output.toByteArray();
    }

    private static byte[] header(final int type, final int width, final int height, final int depth,
                                 final int descriptor, final int idLength) {
        final byte[] header = new byte[18];
        header[0] = (byte) idLength;
        header[2] = (byte) type;
        header[12] = (byte) width;
        header[13] = (byte) (width >>> 8);
        header[14] = (byte) height;
        header[15] = (byte) (height >>> 8);
        header[16] = (byte) depth;
        header[17] = (byte) descriptor;
        return header;
    }
}
