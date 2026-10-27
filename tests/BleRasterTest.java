package org.nfcresearch.qpolllab;

import java.util.Arrays;
import org.nfcresearch.qpolllab.core.PanelProtocol;
import org.nfcresearch.qpolllab.image.BleRaster;

public final class BleRasterTest {
    private static void require(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        require(BleRaster.IMAGE_BYTES == 105984, "BLE image length");
        require(BleRaster.ROTATE_CW_DEG == 90, "rotate constant");

        byte[] codes = new byte[PanelProtocol.WIDTH * PanelProtocol.HEIGHT];
        codes[0] = 0; // logical top-left
        codes[PanelProtocol.WIDTH - 1] = 1; // logical top-right
        codes[(PanelProtocol.HEIGHT - 1) * PanelProtocol.WIDTH] = 2; // logical bottom-left
        codes[codes.length - 1] = 3; // logical bottom-right
        byte[] original = codes.clone();

        byte[] packed = BleRaster.fromCodes(codes);
        require(packed.length == BleRaster.IMAGE_BYTES, "packed length");
        require(Arrays.equals(codes, original), "no input mutation");

        // CW90: (x,y) -> (767-y, x) in 768×552 space, row-major stride 768.
        byte[] rotated = BleRaster.rotateCw90(codes, PanelProtocol.WIDTH, PanelProtocol.HEIGHT);
        require(rotated.length == BleRaster.BLE_WIDTH * BleRaster.BLE_HEIGHT, "rotated length");
        require(rotated[767] == 0, "TL -> top row right");
        require(rotated[551 * 768 + 767] == 1, "TR -> bottom row right");
        require(rotated[0] == 2, "BL -> top-left");
        require(rotated[551 * 768] == 3, "BR -> bottom row left");

        byte[] unpacked = BleRaster.unpackRowMajor(packed, BleRaster.BLE_WIDTH, BleRaster.BLE_HEIGHT);
        require(Arrays.equals(unpacked, rotated), "pack/unpack round trip");
        require(Arrays.equals(BleRaster.fromCodes(codes), packed), "deterministic packing");

        System.out.println("BleRaster tests passed");
    }
}
