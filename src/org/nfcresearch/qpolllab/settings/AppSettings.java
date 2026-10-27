package org.nfcresearch.qpolllab.settings;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.nfcresearch.qpolllab.core.PanelProtocol;

/** Typed access to every persisted option. UI code never touches raw keys. */
public final class AppSettings {
    public static final int TODAY_FILLED = 0;
    public static final int TODAY_TEXT = 1;
    public static final int TODAY_NONE = 2;
    public static final int TODAY_BLACK = 3;
    public static final int TODAY_YELLOW = 4;
    public static final int TODAY_OUTLINE = 5;

    public static final int WEEKEND_RED = 0;
    public static final int WEEKEND_YELLOW_BAR = 1;
    public static final int WEEKEND_NONE = 2;

    public static final int FONT_SMALL = 0;
    public static final int FONT_MEDIUM = 1;
    public static final int FONT_LARGE = 2;

    public static final int LINK_NFC = 0;
    public static final int LINK_BLE = 1;

    public static final int IDLE_NEVER = 0;

    private static final int[] BLOCK_SIZES = {1, 2, 4, 8, 16};
    private static final int[] POWER_TIMES = {500, 1000, 2000, 3000, 4000, 5000};
    private static final int[] POLL_TIMES = {0, 10, 20, 50, 100, 200, 300, 400, 500};
    private static final int[] SCAN_SECONDS = {5, 10, 30, 60};
    private static final int[] AGENDA_LIMITS = {4, 6, 8, 10, 12};
    private static final int[] MARGINS = {0, 6, 12, 18, 24, 30, 36, 42, 48, 54, 60};
    private static final int[] IDLE_MINUTES = {1, 5, 10, 30, 60, IDLE_NEVER};
    private static final int[] CAROUSEL_UNITS = {1, 60, 1440, 10080}; // min, hour, day, week in minutes

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getSharedPreferences("eink-settings", Context.MODE_PRIVATE);
    }

    public static int[] blockSizes() {
        return BLOCK_SIZES.clone();
    }

    public static int[] powerTimes() {
        return POWER_TIMES.clone();
    }

    public static int[] pollTimes() {
        return POLL_TIMES.clone();
    }

    public static int[] scanSecondOptions() {
        return SCAN_SECONDS.clone();
    }

    public static int[] agendaLimits() {
        return AGENDA_LIMITS.clone();
    }

    public static int[] marginOptions() {
        return MARGINS.clone();
    }

    public static int[] idleMinuteOptions() {
        return IDLE_MINUTES.clone();
    }

    public static int[] carouselUnitMinutes() {
        return CAROUSEL_UNITS.clone();
    }

    private int get(String key, int fallback) {
        return prefs.getInt(key, fallback);
    }

    private void put(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    // ---- image page -------------------------------------------------------

    public int rotation() {
        return get("rotation", 0);
    }

    public void setRotation(int value) {
        put("rotation", ((value % 360) + 360) % 360);
    }

    public boolean cover() {
        return prefs.getBoolean("fitCover", false);
    }

    public void setCover(boolean value) {
        prefs.edit().putBoolean("fitCover", value).apply();
    }

    public boolean dither() {
        return prefs.getBoolean("dither", false);
    }

    public void setDither(boolean value) {
        prefs.edit().putBoolean("dither", value).apply();
    }

    public int blockSize() {
        return get("blockSize", 1);
    }

    public void setBlockSize(int value) {
        put("blockSize", value);
    }

    public boolean binary() {
        return prefs.getBoolean("binary", false);
    }

    public void setBinary(boolean value) {
        prefs.edit().putBoolean("binary", value).apply();
    }

    public int threshold() {
        return Math.max(0, Math.min(255, get("threshold", 128)));
    }

    public void setThreshold(int value) {
        put("threshold", Math.max(0, Math.min(255, value)));
    }

    // ---- calendar page ----------------------------------------------------

    public int monthOffset() {
        return get("monthOffset", 0);
    }

    public void setMonthOffset(int value) {
        put("monthOffset", value);
    }

    public int weekStart() {
        int value = get("weekStart", Calendar.MONDAY);
        return value == Calendar.SUNDAY ? Calendar.SUNDAY : Calendar.MONDAY;
    }

    public void setWeekStart(int value) {
        put("weekStart", value);
    }

    public int todayStyle() {
        return clamp(get("todayStyle", TODAY_FILLED), 5);
    }

    public void setTodayStyle(int value) {
        put("todayStyle", clamp(value, 5));
    }

    public int weekendStyle() {
        return get("weekendStyle", WEEKEND_RED);
    }

    public void setWeekendStyle(int value) {
        put("weekendStyle", value);
    }

    public boolean showTime() {
        return prefs.getBoolean("showTime", true);
    }

    public void setShowTime(boolean value) {
        prefs.edit().putBoolean("showTime", value).apply();
    }

    public boolean showMarks() {
        return prefs.getBoolean("showMarks", true);
    }

    public void setShowMarks(boolean value) {
        prefs.edit().putBoolean("showMarks", value).apply();
    }

    public int maxAgenda() {
        return get("maxAgenda", 8);
    }

    public void setMaxAgenda(int value) {
        put("maxAgenda", value);
    }

    public int fontScale() {
        return Math.max(0, Math.min(2, get("fontScale", FONT_MEDIUM)));
    }

    public void setFontScale(int value) {
        put("fontScale", value);
    }

    // Per-element font levels: 0 很小, 1 小, 2 标准, 3 大, 4 很大.

    public int fontTitle() {
        return clamp(get("calFontTitle", 2), 4);
    }

    public void setFontTitle(int value) {
        put("calFontTitle", clamp(value, 4));
    }

    public int fontWeek() {
        return clamp(get("calFontWeek", 2), 4);
    }

    public void setFontWeek(int value) {
        put("calFontWeek", clamp(value, 4));
    }

    public int fontDate() {
        return clamp(get("calFontDate", 2), 4);
    }

    public void setFontDate(int value) {
        put("calFontDate", clamp(value, 4));
    }

    public int fontAgenda() {
        return clamp(get("calFontAgenda", 2), 4);
    }

    public void setFontAgenda(int value) {
        put("calFontAgenda", clamp(value, 4));
    }

    // Page margins in panel pixels.

    public int marginTop() {
        return clamp(get("calMarginTop", 18), 60);
    }

    public void setMarginTop(int value) {
        put("calMarginTop", clamp(value, 60));
    }

    public int marginBottom() {
        return clamp(get("calMarginBottom", 18), 60);
    }

    public void setMarginBottom(int value) {
        put("calMarginBottom", clamp(value, 60));
    }

    public int marginLeft() {
        return clamp(get("calMarginLeft", 18), 60);
    }

    public void setMarginLeft(int value) {
        put("calMarginLeft", clamp(value, 60));
    }

    public int marginRight() {
        return clamp(get("calMarginRight", 18), 60);
    }

    public void setMarginRight(int value) {
        put("calMarginRight", clamp(value, 60));
    }

    public int orientation() {
        return Math.floorMod(get("calOrientation", 0), 4);
    }

    public void setOrientation(int value) {
        put("calOrientation", Math.floorMod(value, 4));
    }

    public int markerStyle() {
        return clamp(get("calMarkerStyle", 0), 3);
    }

    public void setMarkerStyle(int value) {
        put("calMarkerStyle", clamp(value, 3));
    }

    public boolean showAdjust() {
        return prefs.getBoolean("calShowAdjust", true);
    }

    public void setShowAdjust(boolean value) {
        prefs.edit().putBoolean("calShowAdjust", value).apply();
    }

    public boolean showBorder() {
        return prefs.getBoolean("calShowBorder", false);
    }

    public void setShowBorder(boolean value) {
        prefs.edit().putBoolean("calShowBorder", value).apply();
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    /** Empty means "every visible calendar". */
    public Set<Long> calendarIds() {
        String raw = prefs.getString("calendarIds", "");
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    public void setCalendarIds(Set<Long> ids) {
        StringBuilder out = new StringBuilder();
        for (Long id : ids) {
            if (out.length() > 0) out.append(',');
            out.append(id);
        }
        prefs.edit().putString("calendarIds", out.toString()).apply();
    }

    // ---- connection -------------------------------------------------------

    public int linkMode() {
        return clamp(get("linkMode", LINK_NFC), 1);
    }

    public void setLinkMode(int value) {
        put("linkMode", clamp(value, 1));
    }

    public boolean bleMode() {
        return linkMode() == LINK_BLE;
    }

    public String panelType() {
        String value = prefs.getString("panelType", "");
        return value == null ? "" : value;
    }

    public void setPanelType(String value) {
        prefs.edit().putString("panelType", value == null ? "" : value).apply();
    }

    public int idleMinutes() {
        int value = get("idleMinutes", 5);
        if (value == IDLE_NEVER) return IDLE_NEVER;
        return Math.max(1, Math.min(240, value));
    }

    public void setIdleMinutes(int value) {
        if (value == IDLE_NEVER) put("idleMinutes", IDLE_NEVER);
        else put("idleMinutes", Math.max(1, Math.min(240, value)));
    }

    public String bleAddress() {
        String value = prefs.getString("bleAddress", "");
        return value == null ? "" : value;
    }

    public void setBleAddress(String value) {
        prefs.edit().putString("bleAddress", value == null ? "" : value).apply();
    }

    public String bleName() {
        String value = prefs.getString("bleName", "");
        return value == null ? "" : value;
    }

    public void setBleName(String value) {
        prefs.edit().putString("bleName", value == null ? "" : value).apply();
    }

    public boolean carouselEnabled() {
        return prefs.getBoolean("carouselEnabled", false);
    }

    public void setCarouselEnabled(boolean value) {
        prefs.edit().putBoolean("carouselEnabled", value).apply();
    }

    public int carouselInterval() {
        return Math.max(1, get("carouselInterval", 5));
    }

    public void setCarouselInterval(int value) {
        put("carouselInterval", Math.max(1, value));
    }

    public int carouselUnitIndex() {
        return clamp(get("carouselUnit", 0), 3);
    }

    public void setCarouselUnitIndex(int value) {
        put("carouselUnit", clamp(value, 3));
    }

    public long carouselIntervalMs() {
        return carouselInterval() * (long) CAROUSEL_UNITS[carouselUnitIndex()] * 60_000L;
    }

    public boolean calendarAutoPush() {
        return prefs.getBoolean("calendarAutoPush", true);
    }

    public void setCalendarAutoPush(boolean value) {
        prefs.edit().putBoolean("calendarAutoPush", value).apply();
    }

    public int imageIndex() {
        return Math.max(0, get("imageIndex", 0));
    }

    public void setImageIndex(int value) {
        put("imageIndex", Math.max(0, value));
    }

    // ---- transport --------------------------------------------------------

    public boolean useRoot() {
        return prefs.getBoolean("useRoot", false);
    }

    public void setUseRoot(boolean value) {
        prefs.edit().putBoolean("useRoot", value).apply();
    }

    public int powerMs() {
        return get("powerMs", 2000);
    }

    public void setPowerMs(int value) {
        put("powerMs", value);
    }

    public int pollMs() {
        return get("pollMs", PanelProtocol.DEFAULT_POLL_MS);
    }

    public void setPollMs(int value) {
        put("pollMs", value);
    }

    public int scanSeconds() {
        return get("scanSeconds", 10);
    }

    public void setScanSeconds(int value) {
        put("scanSeconds", value);
    }

    public void restoreTransportDefaults() {
        prefs.edit().putInt("powerMs", 2000).putInt("pollMs", PanelProtocol.DEFAULT_POLL_MS)
                .putInt("scanSeconds", 10).apply();
    }

    // ---- shell ------------------------------------------------------------

    public int page() {
        return Math.max(0, Math.min(2, get("page", 0)));
    }

    public void setPage(int value) {
        put("page", Math.max(0, Math.min(2, value)));
    }
}
