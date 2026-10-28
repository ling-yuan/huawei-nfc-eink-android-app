package org.nfcresearch.qpolllab.image;

import org.nfcresearch.qpolllab.core.PanelProtocol;

/**
 * Packs a 552×768 logical page into the InkSight BLE wire format:
 * 768×552, row-major, 4 pixels per byte, MSB first, row 0 at the top.
 * Columns stay left to right. The panel must not mirror this buffer.
 */
public final class BleRaster {
    /** Clockwise degrees applied before packing. Change only this if the screen is upside-down. */
    public static final int ROTATE_CW_DEG = 90;

    public static final int BLE_WIDTH = 768;
    public static final int BLE_HEIGHT = 552;
    public static final int IMAGE_BYTES = BLE_WIDTH * BLE_HEIGHT / 4;

    private BleRaster() {
    }

    /** codes length must be 552×768; values 0..3. */
    public static byte[] fromCodes(byte[] codes) {
        if (codes.length != PanelProtocol.WIDTH * PanelProtocol.HEIGHT)
            throw new IllegalArgumentException("逻辑像素尺寸错误");
        byte[] rotated = rotateCw90(codes, PanelProtocol.WIDTH, PanelProtocol.HEIGHT);
        return packRowMajor(rotated, BLE_WIDTH, BLE_HEIGHT);
    }

    public static byte[] rotateCw90(byte[] codes, int width, int height) {
        if (ROTATE_CW_DEG != 90) throw new IllegalStateException("仅支持顺时针 90°");
        byte[] out = new byte[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                out[x * height + (height - 1 - y)] = codes[y * width + x];
        return out;
    }

    public static byte[] packRowMajor(byte[] codes, int width, int height) {
        if (width <= 0 || height <= 0 || width % 4 != 0 || codes.length != width * height)
            throw new IllegalArgumentException("蓝牙像素尺寸错误");
        byte[] packed = new byte[width * height / 4];
        int out = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x += 4) {
                int value = 0;
                for (int n = 0; n < 4; n++) {
                    int code = codes[y * width + x + n] & 255;
                    if (code > 3) throw new IllegalArgumentException("颜色码错误");
                    value |= code << (6 - 2 * n);
                }
                packed[out++] = (byte) value;
            }
        return packed;
    }

    /** Decode one packed BLE byte stream back to 768×552 codes (for tests). */
    public static byte[] unpackRowMajor(byte[] packed, int width, int height) {
        if (packed.length != width * height / 4) throw new IllegalArgumentException("长度错误");
        byte[] codes = new byte[width * height];
        int in = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x += 4) {
                int b = packed[in++] & 255;
                for (int n = 0; n < 4; n++)
                    codes[y * width + x + n] = (byte) ((b >> (6 - 2 * n)) & 3);
            }
        return codes;
    }
}
