package org.nfcresearch.qpolllab.image;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.InputStream;
import org.nfcresearch.qpolllab.core.PanelProtocol;
import org.nfcresearch.qpolllab.core.PanelType;

/** Offline preview and 2-bit factory-layout image encoding. */
public final class PanelImage {
    public static final int WIDTH = PanelProtocol.WIDTH;
    public static final int HEIGHT = PanelProtocol.HEIGHT;
    public static final int[] PALETTE = {
            ImageRaster.color(0), ImageRaster.color(1), ImageRaster.color(2), ImageRaster.color(3)
    };

    private PanelImage() {
    }

    public static final class Prepared {
        public final Bitmap preview;
        public final byte[] codes;
        public final byte[] packed;
        public final String settings;
        public final String panelType;

        Prepared(Bitmap preview, byte[] codes, byte[] packed, String settings, String panelType) {
            this.preview = preview;
            this.codes = codes;
            this.packed = packed;
            this.settings = settings;
            this.panelType = panelType;
        }
    }

    public static Bitmap load(ContentResolver resolver, Uri uri) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, options);
        }
        if (options.outWidth < 1 || options.outHeight < 1) throw new IllegalArgumentException("无法识别图片");
        int sample = 1;
        while (Math.max(options.outWidth, options.outHeight) / sample > 2048) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap decoded;
        try (InputStream in = resolver.openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) throw new IllegalArgumentException("图片解码失败");
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = resolver.openInputStream(uri)) {
            orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
        } catch (Exception ignored) {
        }
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(270); m.postScale(-1, 1); break;
            case 8: m.setRotate(270); break;
            default: break;
        }
        return m.isIdentity() ? decoded : Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
    }

    public static Bitmap testPattern() {
        Bitmap b = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(44);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.drawText("墨水屏刷新测试", 44, 85, p);
        p.setTextSize(27);
        c.drawText("墨水屏 · 552×768", 44, 132, p);
        for (int i = 0; i < 4; i++) {
            p.setColor(PALETTE[i]);
            c.drawRect(i * 138, 180, (i + 1) * 138, 570, p);
        }
        p.setColor(Color.BLACK);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawRect(3, 3, WIDTH - 3, HEIGHT - 3, p);
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(28);
        c.drawText("顶部 ↑    黑 白 黄 红", 44, 640, p);
        c.drawText("图片页 / 日程页 / 配置页", 44, 690, p);
        return b;
    }

    public static Bitmap rotated(Bitmap source, int rotation) {
        if (rotation == 0) return source;
        Matrix rotate = new Matrix();
        rotate.setRotate(rotation);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), rotate, true);
    }

    public static Prepared prepare(Bitmap source, int rotation, boolean cover, boolean dither, int block,
                                   boolean binary, int threshold, boolean cropped, String panelType) {
        if (block > 1) dither = false;
        Bitmap input = rotated(source, rotation);
        Bitmap fitted = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(fitted);
        canvas.drawColor(Color.WHITE);
        float sx = WIDTH / (float) input.getWidth(), sy = HEIGHT / (float) input.getHeight();
        float scale = cover ? Math.max(sx, sy) : Math.min(sx, sy);
        float w = input.getWidth() * scale, h = input.getHeight() * scale;
        canvas.drawBitmap(input, null, new RectF((WIDTH - w) / 2, (HEIGHT - h) / 2, (WIDTH + w) / 2, (HEIGHT + h) / 2),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        String settings = "屏幕：" + (panelType == null || panelType.isEmpty() ? "尚未选择" : panelType)
                + " · " + (binary ? "黑白二值化 · 阈值 " + threshold : "黑白黄红四色")
                + " · 简化：" + (block == 1 ? "原始细节" : block + "×" + block + " 色块")
                + " · 抖动：" + (dither ? "开启" : "关闭")
                + " · " + (cropped ? "已手动裁切 · " : "")
                + "旋转 " + rotation + "° · " + (cover ? "填满" : "留白");
        int[] pixels = new int[WIDTH * HEIGHT];
        fitted.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        byte[] codes = ImageRaster.quantize(pixels, WIDTH, HEIGHT, binary, threshold, dither, block);
        return pack(codes, settings, panelType);
    }

    /** Encodes an already panel-sized image, e.g. one produced by the calendar renderer. */
    public static Prepared encodePanel(Bitmap panel, String settings, String panelType) {
        if (panel.getWidth() != WIDTH || panel.getHeight() != HEIGHT)
            throw new IllegalArgumentException("页面尺寸必须为 552×768");
        int[] pixels = new int[WIDTH * HEIGHT];
        panel.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        byte[] codes = ImageRaster.quantize(pixels, WIDTH, HEIGHT, false, 128, false, 1);
        return pack(codes, settings, panelType);
    }

    private static Prepared pack(byte[] codes, String settings, String panelType) {
        String chosen = panelType == null ? "" : panelType;
        PanelType type = PanelType.isValid(chosen) ? PanelType.parse(chosen) : PanelType.A1;
        byte[] packed = type.pack(codes);
        Bitmap preview = Bitmap.createBitmap(type.preview(packed), WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        return new Prepared(preview, codes, packed, settings, chosen);
    }
}
