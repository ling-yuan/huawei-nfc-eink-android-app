package org.nfcresearch.qpolllab.calendar;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** One occurrence produced by the system calendar provider. */
public final class CalendarEvent {
    private static final String[] WEEKDAYS = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    public final long id;
    public final long calendarId;
    public final String calendarName;
    public final String title;
    public final long begin;
    public final long end;
    public final boolean allDay;
    public final int dayOfMonth;
    public final int hour;
    public final int minute;
    public final int weekday;

    public CalendarEvent(long id, long calendarId, String calendarName, String title,
                         long begin, long end, boolean allDay) {
        this.id = id;
        this.calendarId = calendarId;
        this.calendarName = calendarName == null ? "" : calendarName;
        this.title = title == null || title.trim().isEmpty() ? "（无标题）" : title.trim();
        this.begin = begin;
        this.end = end;
        this.allDay = allDay;
        // All-day events are stored at UTC midnight; timed events use the device time zone.
        TimeZone zone = allDay ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault();
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(begin);
        this.dayOfMonth = c.get(Calendar.DAY_OF_MONTH);
        this.hour = c.get(Calendar.HOUR_OF_DAY);
        this.minute = c.get(Calendar.MINUTE);
        this.weekday = c.get(Calendar.DAY_OF_WEEK);
    }

    public String timeLabel() {
        return allDay ? "全天" : String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    public String weekdayLabel() {
        return WEEKDAYS[weekday - 1];
    }

    public String dateLabel() {
        return String.format(Locale.ROOT, "%d/%d", month(begin), dayOfMonth);
    }

    private int month(long millis) {
        TimeZone zone = allDay ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault();
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(millis);
        return c.get(Calendar.MONTH) + 1;
    }
}
