package org.nfcresearch.qpolllab.calendar;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.List;
import org.nfcresearch.qpolllab.core.PanelProtocol;
import org.nfcresearch.qpolllab.image.ImageRaster;
import org.nfcresearch.qpolllab.settings.AppSettings;

/**
 * Renders a month page directly at panel resolution with antialiasing disabled, so every
 * pixel already belongs to the panel palette and quantisation cannot create speckle.
 */
public final class CalendarRenderer {
    private static final int BLACK = ImageRaster.color(0);
    private static final int WHITE = ImageRaster.color(1);
    private static final int YELLOW = ImageRaster.color(2);
    private static final int RED = ImageRaster.color(3);

    private static final Typeface NORMAL = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    private CalendarRenderer() {
    }

    public static Bitmap render(CalendarMonth month, CalendarStyle style) {
        boolean landscape = style.landscape();
        int width = landscape ? PanelProtocol.HEIGHT : PanelProtocol.WIDTH;
        int height = landscape ? PanelProtocol.WIDTH : PanelProtocol.HEIGHT;
        Bitmap page = draw(month, style, width, height);
        int degrees = style.orientation * 90;
        if (degrees == 0) return page;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(page, 0, 0, width, height, matrix, false);
    }

    private static Bitmap draw(CalendarMonth month, CalendarStyle style, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(WHITE);

        Paint paint = new Paint();
        paint.setAntiAlias(false);
        paint.setDither(false);
        paint.setSubpixelText(false);
        paint.setFilterBitmap(false);

        // Larger, bolder type than the previous revision: e-ink loses thin strokes.
        float titleSize = 31f * style.titleScale;
        float weekSize = 17f * style.weekScale;
        float dateSize = 20f * style.dateScale;
        float agendaSize = 15f * style.agendaScale;

        float left = style.marginLeft;
        float right = Math.max(left + 80, width - style.marginRight);
        float top = style.marginTop;
        float bottom = Math.max(top + 160, height - style.marginBottom);
        float cellWidth = (right - left) / 7f;

        float titleBase = top + titleSize;
        float ruleY = titleBase + titleSize * 0.28f;
        float weekBase = ruleY + weekSize * 1.55f;
        float gridTop = weekBase + weekSize * 0.85f;

        int rows = Math.max(4, Math.min(6, (month.leading + month.days + 6) / 7));
        boolean adjust = style.showAdjust && month.hasAdjustMarks();
        float lineHeight = agendaSize * 1.55f;
        float reservedBottom = adjust ? agendaSize * 2.0f : 0f;
        float reserveForAgenda = agendaSize * 2.1f + agendaSize * 1.9f + lineHeight * 1.6f;

        float cellHeight = (bottom - reservedBottom - gridTop - reserveForAgenda) / rows;
        cellHeight = Math.max(dateSize * 2.15f, Math.min(dateSize * 3.6f, cellHeight));
        float gridHeight = cellHeight * rows;
        float agendaTitleBase = gridTop + gridHeight + agendaSize * 2.1f;
        float agendaFirstBase = agendaTitleBase + agendaSize * 1.9f;

        if (style.showBorder) drawBorder(canvas, paint, left, right, gridTop, cellWidth, cellHeight, rows);
        drawHeader(canvas, paint, month, left, right, titleBase, ruleY, titleSize);
        drawWeekHeader(canvas, paint, month, style, left, cellWidth, weekBase, weekSize);
        drawGrid(canvas, paint, month, style, left, gridTop, cellWidth, cellHeight, dateSize);
        drawAgenda(canvas, paint, month, style, left, right, agendaTitleBase, agendaFirstBase,
                agendaSize, lineHeight, bottom, reservedBottom);
        if (adjust) drawLegend(canvas, paint, left, bottom, agendaSize);

        return bitmap;
    }

    private static void drawBorder(Canvas canvas, Paint paint, float left, float right, float gridTop,
                                   float cellWidth, float cellHeight, int rows) {
        paint.setColor(BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        float gridBottom = gridTop + cellHeight * rows;
        canvas.drawRect(left, gridTop, right, gridBottom, paint);
        for (int row = 1; row < rows; row++)
            canvas.drawLine(left, gridTop + row * cellHeight, right, gridTop + row * cellHeight, paint);
        for (int column = 1; column < 7; column++)
            canvas.drawLine(left + column * cellWidth, gridTop, left + column * cellWidth, gridBottom, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(1);
    }

    private static void drawHeader(Canvas canvas, Paint paint, CalendarMonth month,
                                   float left, float right, float titleBase, float ruleY, float titleSize) {
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(BOLD);
        paint.setTextSize(titleSize);
        paint.setColor(BLACK);
        canvas.drawText(month.title(), left, titleBase, paint);

        paint.setTextSize(titleSize * 0.50f);
        paint.setColor(RED);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(month.events.size() + " 项", right, titleBase, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        paint.setColor(BLACK);
        paint.setStrokeWidth(2);
        canvas.drawLine(left, ruleY, right, ruleY, paint);
        paint.setStrokeWidth(1);
    }

    private static void drawWeekHeader(Canvas canvas, Paint paint, CalendarMonth month, CalendarStyle style,
                                       float left, float cellWidth, float baseline, float weekSize) {
        paint.setTypeface(BOLD);
        paint.setTextSize(weekSize);
        paint.setTextAlign(Paint.Align.CENTER);
        for (int column = 0; column < 7; column++) {
            boolean weekend = month.isWeekendColumn(column);
            paint.setColor(weekend && style.weekendStyle == AppSettings.WEEKEND_RED ? RED : BLACK);
            canvas.drawText(month.weekdayHeader(column), left + cellWidth * (column + 0.5f), baseline, paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private static void drawGrid(Canvas canvas, Paint paint, CalendarMonth month, CalendarStyle style,
                                 float left, float gridTop, float cellWidth, float cellHeight, float dateSize) {
        float numberBase = dateSize * 1.28f;
        float markerBase = dateSize * 1.98f;
        float triangle = Math.min(19f, cellWidth * 0.27f);

        paint.setTypeface(BOLD);
        paint.setTextSize(dateSize);
        paint.setTextAlign(Paint.Align.CENTER);

        for (int index = 0; index < 42; index++) {
            int day = index - month.leading + 1;
            if (day < 1 || day > month.days) continue;
            float x = left + (index % 7) * cellWidth;
            float y = gridTop + (index / 7) * cellHeight;
            float centerX = x + cellWidth / 2;
            boolean today = day == month.todayDay;
            boolean weekend = month.isWeekendColumn(index % 7);
            int adjustKind = style.showAdjust ? HolidayTable.kindOf(month.year, month.month, day) : HolidayTable.NONE;

            int numberColor = BLACK;
            if (today) {
                switch (style.todayStyle) {
                    case AppSettings.TODAY_FILLED:
                    case AppSettings.TODAY_BLACK:
                    case AppSettings.TODAY_YELLOW: {
                        int fill = style.todayStyle == AppSettings.TODAY_FILLED ? RED
                                : style.todayStyle == AppSettings.TODAY_BLACK ? BLACK : YELLOW;
                        paint.setColor(fill);
                        canvas.drawRoundRect(pill(centerX, y, dateSize), 6, 6, paint);
                        numberColor = style.todayStyle == AppSettings.TODAY_YELLOW ? BLACK : WHITE;
                        break;
                    }
                    case AppSettings.TODAY_OUTLINE: {
                        paint.setColor(RED);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(2);
                        canvas.drawRoundRect(pill(centerX, y, dateSize), 6, 6, paint);
                        paint.setStyle(Paint.Style.FILL);
                        paint.setStrokeWidth(1);
                        numberColor = RED;
                        break;
                    }
                    case AppSettings.TODAY_TEXT:
                        numberColor = RED;
                        break;
                    default:
                        break;
                }
            } else if (weekend && style.weekendStyle == AppSettings.WEEKEND_RED) {
                numberColor = RED;
            }

            paint.setColor(numberColor);
            canvas.drawText(Integer.toString(day), centerX, y + numberBase, paint);

            if (weekend && style.weekendStyle == AppSettings.WEEKEND_YELLOW_BAR && !today) {
                paint.setColor(YELLOW);
                float half = dateSize * 0.55f;
                canvas.drawRect(centerX - half, y + dateSize * 1.62f,
                        centerX + half, y + dateSize * 1.62f + 2.5f, paint);
            }

            List<CalendarEvent> events = month.onDay(day);
            if (style.showMarks && !events.isEmpty()) {
                paint.setColor(BLACK);
                drawMarkers(canvas, paint, style.markerStyle, centerX, y + markerBase,
                        dateSize, events.size());
            }

            if (adjustKind != HolidayTable.NONE) {
                paint.setColor(adjustKind == HolidayTable.REST ? RED : BLACK);
                float bx = x + cellWidth - triangle - 2;
                float by = y + cellHeight - triangle - 2;
                canvas.drawPath(trianglePath(bx, by, triangle), paint);
            }
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    /** Compact rounded highlight that wraps the date number only. */
    private static RectF pill(float centerX, float cellTop, float dateSize) {
        float halfWidth = dateSize * 1.12f;
        return new RectF(centerX - halfWidth, cellTop + dateSize * 0.08f,
                centerX + halfWidth, cellTop + dateSize * 1.52f);
    }

    private static Path trianglePath(float left, float top, float size) {
        Path path = new Path();
        path.moveTo(left + size, top + size);
        path.lineTo(left + size, top);
        path.lineTo(left, top + size);
        path.close();
        return path;
    }

    /** Markers sit centred directly beneath the date number. */
    private static void drawMarkers(Canvas canvas, Paint paint, int style, float centerX, float baseY,
                                    float dateSize, int count) {
        switch (style) {
            case CalendarStyle.MARKER_SQUARE: {
                int marks = Math.min(3, count);
                float size = dateSize * 0.26f, gap = dateSize * 0.20f;
                float total = marks * size + (marks - 1) * gap;
                float startX = centerX - total / 2;
                for (int i = 0; i < marks; i++) {
                    float left = startX + i * (size + gap);
                    canvas.drawRect(left, baseY - size, left + size, baseY, paint);
                }
                break;
            }
            case CalendarStyle.MARKER_BAR: {
                float width = Math.min(dateSize * 1.7f, dateSize * 0.45f * count);
                canvas.drawRect(centerX - width / 2, baseY - dateSize * 0.18f,
                        centerX + width / 2, baseY, paint);
                break;
            }
            case CalendarStyle.MARKER_COUNT:
                paint.setTextSize(dateSize * 0.72f);
                canvas.drawText(Integer.toString(count), centerX, baseY, paint);
                paint.setTextSize(dateSize);
                break;
            case CalendarStyle.MARKER_DOT:
            default: {
                int marks = Math.min(3, count);
                float radius = dateSize * 0.16f, gap = dateSize * 0.38f;
                float startX = centerX - (marks - 1) * gap / 2f;
                for (int i = 0; i < marks; i++)
                    canvas.drawCircle(startX + i * gap, baseY - radius, radius, paint);
                break;
            }
        }
    }

    private static void drawAgenda(Canvas canvas, Paint paint, CalendarMonth month, CalendarStyle style,
                                   float left, float right, float titleBase, float firstBase,
                                   float agendaSize, float lineHeight, float bottom, float reservedBottom) {
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(BOLD);
        paint.setTextSize(agendaSize * 1.15f);
        paint.setColor(BLACK);
        canvas.drawText("本月日程", left, titleBase, paint);

        paint.setTextSize(agendaSize);
        if (month.events.isEmpty()) {
            canvas.drawText("本月暂无日程", left, firstBase, paint);
            return;
        }

        boolean twoColumns = style.landscape();
        float usableBottom = bottom - reservedBottom;
        int rows = Math.max(0, (int) ((usableBottom - firstBase) / lineHeight) + 1);
        int capacity = twoColumns ? rows * 2 : rows;
        int shown = Math.min(Math.min(style.maxAgenda, capacity), month.events.size());
        float mid = left + (right - left) * 0.5f;
        float gap = agendaSize * 0.8f;

        for (int i = 0; i < shown; i++) {
            CalendarEvent event = month.events.get(i);
            int row = twoColumns ? i / 2 : i;
            int col = twoColumns ? i % 2 : 0;
            float colLeft = twoColumns ? (col == 0 ? left : mid + gap * 0.5f) : left;
            float colRight = twoColumns ? (col == 0 ? mid - gap * 0.5f : right) : right;
            float baseline = firstBase + row * lineHeight;
            boolean today = month.todayDay != 0 && event.dayOfMonth == month.todayDay;

            paint.setColor(today ? RED : BLACK);
            String datePart = event.dateLabel() + " " + event.weekdayLabel();
            canvas.drawText(datePart, colLeft, baseline, paint);

            String text = (style.showTime ? event.timeLabel() + "  " : "") + event.title;
            float startX = colLeft + paint.measureText(datePart) + agendaSize * 0.6f;
            startX = Math.min(startX, colLeft + (colRight - colLeft) * 0.42f);
            canvas.drawText(fit(paint, text, colRight - startX), startX, baseline, paint);
        }

        if (month.events.size() > shown) {
            paint.setTextSize(agendaSize * 0.92f);
            paint.setColor(RED);
            int usedRows = twoColumns ? (shown + 1) / 2 : shown;
            canvas.drawText("还有 " + (month.events.size() - shown) + " 条未显示",
                    left, firstBase + usedRows * lineHeight, paint);
        }
    }

    private static void drawLegend(Canvas canvas, Paint paint, float left, float bottom, float agendaSize) {
        float size = agendaSize * 0.95f;
        paint.setTypeface(BOLD);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.LEFT);
        float y = bottom;
        float x = left;

        paint.setColor(RED);
        canvas.drawRect(x, y - size * 0.72f, x + size * 0.72f, y - size * 0.08f, paint);
        paint.setColor(BLACK);
        canvas.drawText("放假", x + size * 0.95f, y, paint);
        x += paint.measureText("放假") + size * 2.4f;

        paint.setColor(BLACK);
        canvas.drawRect(x, y - size * 0.72f, x + size * 0.72f, y - size * 0.08f, paint);
        canvas.drawText("调休上班", x + size * 0.95f, y, paint);
    }

    private static String fit(Paint paint, String text, float maxWidth) {
        if (paint.measureText(text) <= maxWidth) return text;
        String ellipsis = "…";
        int length = text.length();
        while (length > 0 && paint.measureText(text.substring(0, length) + ellipsis) > maxWidth) length--;
        return length <= 0 ? ellipsis : text.substring(0, length) + ellipsis;
    }
}
