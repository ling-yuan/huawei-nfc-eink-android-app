package org.nfcresearch.qpolllab.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Draws a borrowed raster; never recycles an image owned by a prepared job. */
public final class PixelPreview extends View {
    private Bitmap image;
    private final Paint paint = new Paint();

    public PixelPreview(Context context) {
        super(context);
        init();
    }

    /** Required by LayoutInflater because this view appears in XML. */
    public PixelPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setFilterBitmap(false);
        paint.setDither(false);
    }

    public void setImage(Bitmap image) {
        this.image = image;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xffe3e9e5);
        if (image == null || getWidth() == 0 || getHeight() == 0) return;
        float scale = Math.min(getWidth() / (float) image.getWidth(), getHeight() / (float) image.getHeight());
        float w = image.getWidth() * scale, h = image.getHeight() * scale;
        canvas.drawBitmap(image, null, new RectF((getWidth() - w) / 2, (getHeight() - h) / 2,
                (getWidth() + w) / 2, (getHeight() + h) / 2), paint);
    }
}
