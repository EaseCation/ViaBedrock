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

import java.awt.image.BufferedImage;
import java.io.IOException;

final class TgaReader {

    private static final int HEADER_SIZE = 18;
    private static final int MAX_PIXELS = 64 * 1024 * 1024;

    private TgaReader() {
    }

    static boolean isTga(final byte[] data) {
        try {
            readHeader(data);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    static BufferedImage read(final byte[] data) throws IOException {
        final Header header = readHeader(data);
        final Cursor cursor = new Cursor(data, HEADER_SIZE + header.idLength);
        final int[] colorMap = readColorMap(cursor, header);
        final int[] pixels = new int[header.pixelCount];

        if (header.rle) {
            readRlePixels(cursor, header, colorMap, pixels);
        } else {
            for (int i = 0; i < header.pixelCount; i++) {
                pixels[toOutputIndex(i, header)] = readPixel(cursor, header, colorMap);
            }
        }

        final BufferedImage image = new BufferedImage(header.width, header.height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, header.width, header.height, pixels, 0, header.width);
        return image;
    }

    private static Header readHeader(final byte[] data) throws IOException {
        if (data == null || data.length < HEADER_SIZE) {
            throw new IOException("TGA header is truncated");
        }

        final int idLength = unsigned(data[0]);
        final int colorMapType = unsigned(data[1]);
        final int imageType = unsigned(data[2]);
        final int colorMapOrigin = littleEndian16(data, 3);
        final int colorMapLength = littleEndian16(data, 5);
        final int colorMapDepth = unsigned(data[7]);
        final int width = littleEndian16(data, 12);
        final int height = littleEndian16(data, 14);
        final int pixelDepth = unsigned(data[16]);
        final int descriptor = unsigned(data[17]);

        if (colorMapType != 0 && colorMapType != 1) {
            throw new IOException("Unsupported TGA color map type: " + colorMapType);
        }
        if (imageType != 1 && imageType != 2 && imageType != 3
                && imageType != 9 && imageType != 10 && imageType != 11) {
            throw new IOException("Unsupported TGA image type: " + imageType);
        }
        final int baseType = imageType >= 8 ? imageType - 8 : imageType;
        if ((baseType == 1) != (colorMapType == 1)) {
            throw new IOException("TGA color map header does not match image type");
        }
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw new IOException("Invalid TGA dimensions: " + width + "x" + height);
        }
        if ((descriptor & 0xC0) != 0) {
            throw new IOException("Interleaved TGA images are unsupported");
        }

        if (baseType == 1) {
            if ((pixelDepth != 8 && pixelDepth != 16) || colorMapLength == 0
                    || !isSupportedColorDepth(colorMapDepth)) {
                throw new IOException("Unsupported TGA color map format");
            }
        } else if (baseType == 2 && !isSupportedColorDepth(pixelDepth)) {
            throw new IOException("Unsupported TGA true-color depth: " + pixelDepth);
        } else if (baseType == 3 && pixelDepth != 8 && pixelDepth != 16) {
            throw new IOException("Unsupported TGA grayscale depth: " + pixelDepth);
        }

        final int colorMapBytes = colorMapType == 1
                ? Math.multiplyExact(colorMapLength, bytesPerPixel(colorMapDepth)) : 0;
        final long pixelOffset = (long) HEADER_SIZE + idLength + colorMapBytes;
        if (pixelOffset > data.length) {
            throw new IOException("TGA header data is truncated");
        }

        return new Header(idLength, imageType, baseType, colorMapOrigin, colorMapLength,
                colorMapDepth, width, height, pixelDepth, descriptor, width * height, imageType >= 8);
    }

    private static boolean isSupportedColorDepth(final int depth) {
        return depth == 15 || depth == 16 || depth == 24 || depth == 32;
    }

    private static int[] readColorMap(final Cursor cursor, final Header header) throws IOException {
        if (header.baseType != 1) {
            return null;
        }
        final int[] colorMap = new int[header.colorMapLength];
        for (int i = 0; i < colorMap.length; i++) {
            colorMap[i] = readColor(cursor, header.colorMapDepth, header.descriptor);
        }
        return colorMap;
    }

    private static void readRlePixels(final Cursor cursor, final Header header, final int[] colorMap,
                                      final int[] pixels) throws IOException {
        int decoded = 0;
        while (decoded < header.pixelCount) {
            final int packet = cursor.readUnsigned();
            final int count = (packet & 0x7F) + 1;
            if (decoded + count > header.pixelCount) {
                throw new IOException("TGA RLE packet exceeds image dimensions");
            }

            if ((packet & 0x80) != 0) {
                final int pixel = readPixel(cursor, header, colorMap);
                for (int i = 0; i < count; i++) {
                    pixels[toOutputIndex(decoded++, header)] = pixel;
                }
            } else {
                for (int i = 0; i < count; i++) {
                    pixels[toOutputIndex(decoded++, header)] = readPixel(cursor, header, colorMap);
                }
            }
        }
    }

    private static int readPixel(final Cursor cursor, final Header header, final int[] colorMap) throws IOException {
        if (header.baseType == 1) {
            final int index = readValue(cursor, header.pixelDepth) - header.colorMapOrigin;
            if (index < 0 || index >= colorMap.length) {
                throw new IOException("TGA color map index is out of range");
            }
            return colorMap[index];
        }
        if (header.baseType == 2) {
            return readColor(cursor, header.pixelDepth, header.descriptor);
        }

        final int gray = cursor.readUnsigned();
        final int alpha = header.pixelDepth == 16 ? cursor.readUnsigned() : 0xFF;
        return alpha << 24 | gray << 16 | gray << 8 | gray;
    }

    private static int readColor(final Cursor cursor, final int depth, final int descriptor) throws IOException {
        if (depth == 15 || depth == 16) {
            final int value = cursor.readUnsigned() | cursor.readUnsigned() << 8;
            final int blue = expand5Bit(value);
            final int green = expand5Bit(value >> 5);
            final int red = expand5Bit(value >> 10);
            final int alpha = depth == 16 && (descriptor & 0x0F) != 0
                    ? ((value & 0x8000) == 0 ? 0 : 0xFF) : 0xFF;
            return alpha << 24 | red << 16 | green << 8 | blue;
        }

        final int blue = cursor.readUnsigned();
        final int green = cursor.readUnsigned();
        final int red = cursor.readUnsigned();
        final int alpha = depth == 32 ? cursor.readUnsigned() : 0xFF;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int readValue(final Cursor cursor, final int depth) throws IOException {
        final int low = cursor.readUnsigned();
        return depth == 16 ? low | cursor.readUnsigned() << 8 : low;
    }

    private static int toOutputIndex(final int sourceIndex, final Header header) {
        final int sourceX = sourceIndex % header.width;
        final int sourceY = sourceIndex / header.width;
        final int x = (header.descriptor & 0x10) == 0 ? sourceX : header.width - sourceX - 1;
        final int y = (header.descriptor & 0x20) != 0 ? sourceY : header.height - sourceY - 1;
        return y * header.width + x;
    }

    private static int expand5Bit(final int value) {
        final int component = value & 0x1F;
        return component << 3 | component >> 2;
    }

    private static int bytesPerPixel(final int depth) {
        return (depth + 7) / 8;
    }

    private static int unsigned(final byte value) {
        return value & 0xFF;
    }

    private static int littleEndian16(final byte[] data, final int offset) {
        return unsigned(data[offset]) | unsigned(data[offset + 1]) << 8;
    }

    private record Header(int idLength, int imageType, int baseType, int colorMapOrigin, int colorMapLength,
                          int colorMapDepth, int width, int height, int pixelDepth, int descriptor,
                          int pixelCount, boolean rle) {
    }

    private static final class Cursor {
        private final byte[] data;
        private int offset;

        private Cursor(final byte[] data, final int offset) {
            this.data = data;
            this.offset = offset;
        }

        private int readUnsigned() throws IOException {
            if (this.offset >= this.data.length) {
                throw new IOException("TGA pixel data is truncated");
            }
            return unsigned(this.data[this.offset++]);
        }
    }
}
