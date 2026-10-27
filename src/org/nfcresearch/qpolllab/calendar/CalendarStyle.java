package org.nfcresearch.qpolllab.calendar;

import java.util.Calendar;
import org.nfcresearch.qpolllab.settings.AppSettings;

/** Rendering options for one calendar page. Immutable so a preview snapshot never changes mid-draw. */
public final class CalendarStyle {
    public static final int MARKER_DOT = 0;
    public static final int MARKER_SQUARE = 1;
    public static final int MARKER_BAR = 2;
    public static final int MARKER_COUNT = 3;

    public static final int ORIENT_0 = 0;
    public static final int ORIENT_90 = 1;
    public static final int ORIENT_180 = 2;
    public static final int ORIENT_270 = 3;

    /** Multipliers for the five font levels: 很小 / 小 / 标准 / 大 / 很大. */
    private static final float[] FONT = {0.78f, 0.88f, 1.00f, 1.14f, 1.30f};

    public final int weekStart;
    public final int todayStyle;
    public final int weekendStyle;
    public final int maxAgenda;
    public final boolean showTime;
    public final boolean showMarks;
    public final int markerStyle;
    public final boolean showAdjust;
    public final boolean showBorder;
    public final int marginTop, marginBottom, marginLeft, marginRight;
    public final int orientation;
    public final float titleScale, weekScale, dateScale, agendaScale;

    public static final String[] TODAY_LABELS = {"红底白字", "红字加粗", "不标注", "黑底白字", "黄底黑字", "红框"};
    public static final String[] WEEKEND_LABELS = {"红色", "黄色标记", "不标注"};
    public static final String[] MARKER_LABELS = {"圆点", "方块", "横条", "数字"};

    public CalendarStyle(int weekStart, int todayStyle, int weekendStyle, int maxAgenda,
                         boolean showTime, boolean showMarks, int markerStyle, boolean showAdjust,
                         boolean showBorder,
                         int marginTop, int marginBottom, int marginLeft, int marginRight,
                         int orientation, int fontTitle, int fontWeek, int fontDate, int fontAgenda) {
        this.weekStart = weekStart;
        this.todayStyle = Math.max(0, Math.min(TODAY_LABELS.length - 1, todayStyle));
        this.weekendStyle = weekendStyle;
        this.maxAgenda = Math.max(0, maxAgenda);
        this.showTime = showTime;
        this.showMarks = showMarks;
        this.markerStyle = Math.max(0, Math.min(3, markerStyle));
        this.showAdjust = showAdjust;
        this.showBorder = showBorder;
        this.marginTop = Math.max(0, marginTop);
        this.marginBottom = Math.max(0, marginBottom);
        this.marginLeft = Math.max(0, marginLeft);
        this.marginRight = Math.max(0, marginRight);
        this.orientation = Math.floorMod(orientation, 4);
        this.titleScale = FONT[level(fontTitle)];
        this.weekScale = FONT[level(fontWeek)];
        this.dateScale = FONT[level(fontDate)];
        this.agendaScale = FONT[level(fontAgenda)];
    }

    private static int level(int value) {
        return Math.max(0, Math.min(FONT.length - 1, value));
    }

    public boolean landscape() {
        return orientation == ORIENT_90 || orientation == ORIENT_270;
    }

    public static CalendarStyle from(AppSettings settings) {
        return new CalendarStyle(settings.weekStart(), settings.todayStyle(), settings.weekendStyle(),
                settings.maxAgenda(), settings.showTime(), settings.showMarks(),
                settings.markerStyle(), settings.showAdjust(), settings.showBorder(),
                settings.marginTop(), settings.marginBottom(), settings.marginLeft(), settings.marginRight(),
                settings.orientation(), settings.fontTitle(), settings.fontWeek(),
                settings.fontDate(), settings.fontAgenda());
    }

    public String summary() {
        return "月历 + 日程列表 · 周起始：" + (weekStart == Calendar.MONDAY ? "周一" : "周日")
                + " · 当日：" + TODAY_LABELS[todayStyle]
                + " · 周末：" + WEEKEND_LABELS[clamp(weekendStyle, 2)]
                + " · " + (showTime ? "显示时间" : "隐藏时间")
                + " · 标记：" + MARKER_LABELS[markerStyle]
                + (showAdjust ? " · 显示节假日" : "")
                + (showBorder ? " · 显示边框" : "")
                + " · 最多 " + maxAgenda + " 条"
                + "\n留白 " + marginTop + "/" + marginBottom + "/" + marginLeft + "/" + marginRight
                + " px · 方向 " + (orientation * 90) + "°"
                + " · 字号 " + levelLabel(titleScale) + "/" + levelLabel(weekScale) + "/"
                + levelLabel(dateScale) + "/" + levelLabel(agendaScale);
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private static String levelLabel(float scale) {
        if (scale <= 0.8f) return "很小";
        if (scale <= 0.9f) return "小";
        if (scale <= 1.01f) return "标准";
        if (scale <= 1.2f) return "大";
        return "很大";
    }
}
