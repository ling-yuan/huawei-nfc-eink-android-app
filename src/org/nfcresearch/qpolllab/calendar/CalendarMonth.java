package org.nfcresearch.qpolllab.calendar;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** Pure calendar arithmetic: weekday offset, day grouping and today detection. */
public final class CalendarMonth {
    public static final String[] WEEKDAY_NAMES = {"日", "一", "二", "三", "四", "五", "六"};

    public final int year;
    public final int month; // 0-based, matching java.util.Calendar
    public final int days;
    public final int leading;
    public final int todayDay; // 0 when today is outside this month
    public final int weekStart;
    public final List<CalendarEvent> events;

    private CalendarMonth(int year, int month, int days, int leading, int todayDay, int weekStart,
                          List<CalendarEvent> events) {
        this.year = year;
        this.month = month;
        this.days = days;
        this.leading = leading;
        this.todayDay = todayDay;
        this.weekStart = weekStart;
        this.events = Collections.unmodifiableList(events);
    }

    public static CalendarMonth build(int year, int month, List<CalendarEvent> events, int weekStart) {
        return buildAt(year, month, events, weekStart, System.currentTimeMillis());
    }

    public static CalendarMonth buildAt(int year, int month, List<CalendarEvent> events, int weekStart, long now) {
        Calendar first = Calendar.getInstance();
        first.clear();
        first.set(year, month, 1, 0, 0, 0);
        int days = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int weekday = first.get(Calendar.DAY_OF_WEEK); // 1=Sunday .. 7=Saturday
        int leading = ((weekday - weekStart) % 7 + 7) % 7;

        Calendar current = Calendar.getInstance();
        current.setTimeInMillis(now);
        int todayDay = current.get(Calendar.YEAR) == year && current.get(Calendar.MONTH) == month
                ? current.get(Calendar.DAY_OF_MONTH) : 0;

        List<CalendarEvent> inMonth = new ArrayList<>();
        for (CalendarEvent event : events) {
            if (event.dayOfMonth >= 1 && event.dayOfMonth <= days) inMonth.add(event);
        }
        Collections.sort(inMonth, (a, b) -> {
            if (a.allDay != b.allDay) return a.allDay ? -1 : 1;
            return Long.compare(a.begin, b.begin);
        });
        return new CalendarMonth(year, month, days, leading, todayDay, weekStart, inMonth);
    }

    public String title() {
        return year + "年" + (month + 1) + "月";
    }

    public List<CalendarEvent> onDay(int day) {
        List<CalendarEvent> out = new ArrayList<>();
        for (CalendarEvent event : events) if (event.dayOfMonth == day) out.add(event);
        return out;
    }

    /** Weekday name for a day-of-month, honouring the configured first day of week. */
    public String weekdayName(int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, 0, 0, 0);
        return WEEKDAY_NAMES[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    public boolean isWeekendColumn(int column) {
        int index = ((weekStart == Calendar.MONDAY ? 1 : 0) + column) % 7;
        return index == 0 || index == 6;
    }

    public String weekdayHeader(int column) {
        int index = ((weekStart == Calendar.MONDAY ? 1 : 0) + column) % 7;
        return WEEKDAY_NAMES[index];
    }

    public int cellCount() {
        return leading + days;
    }

    /** True when this month contains a statutory holiday or a make-up workday. */
    public boolean hasAdjustMarks() {
        for (int day = 1; day <= days; day++)
            if (HolidayTable.kindOf(year, month, day) != HolidayTable.NONE) return true;
        return false;
    }
}
