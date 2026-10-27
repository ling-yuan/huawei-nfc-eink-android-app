package org.nfcresearch.qpolllab;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.nfcresearch.qpolllab.calendar.CalendarEvent;
import org.nfcresearch.qpolllab.calendar.CalendarMonth;

/** Calendar arithmetic is Android-free, so the month layout is testable off-device. */
public final class CalendarMonthTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static long local(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static long utc(int year, int month, int day) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(year, month, day, 0, 0, 0);
        return c.getTimeInMillis();
    }

    public static void main(String[] args) {
        long now = local(2026, Calendar.OCTOBER, 19, 12, 0);
        List<CalendarEvent> events = new ArrayList<>();
        events.add(new CalendarEvent(1, 1, "工作", "月会", local(2026, Calendar.OCTOBER, 21, 9, 30),
                local(2026, Calendar.OCTOBER, 21, 10, 30), false));
        events.add(new CalendarEvent(2, 1, "工作", "全天", utc(2026, Calendar.OCTOBER, 1),
                utc(2026, Calendar.OCTOBER, 2), true));

        CalendarMonth monday = CalendarMonth.buildAt(2026, Calendar.OCTOBER, events, Calendar.MONDAY, now);
        check(monday.days == 31, "October must have 31 days");
        check(monday.leading == 3, "2026-10-01 is Thursday, so a Monday-start grid has 3 leading cells");
        check(monday.todayDay == 19, "today must be marked");
        check(monday.events.size() == 2, "both events must stay in the month");
        check(monday.onDay(21).size() == 1, "day grouping");
        check(monday.onDay(1).get(0).allDay, "all-day events sort first");
        check("一".equals(monday.weekdayHeader(0)), "Monday-start header");
        check(monday.isWeekendColumn(5) && monday.isWeekendColumn(6) && !monday.isWeekendColumn(4),
                "weekend columns for a Monday-start grid");
        check("周三".equals(monday.onDay(21).get(0).weekdayLabel()), "weekday label of the event");
        check("09:30".equals(monday.onDay(21).get(0).timeLabel()), "time label");
        check("10/21".equals(monday.onDay(21).get(0).dateLabel()), "date label");

        CalendarMonth sunday = CalendarMonth.buildAt(2026, Calendar.OCTOBER, events, Calendar.SUNDAY, now);
        check(sunday.leading == 4, "Sunday-start grid has 4 leading cells");
        check("日".equals(sunday.weekdayHeader(0)), "Sunday-start header");
        check(sunday.isWeekendColumn(0) && sunday.isWeekendColumn(6), "weekend columns for a Sunday-start grid");

        CalendarMonth february = CalendarMonth.buildAt(2026, Calendar.FEBRUARY, new ArrayList<>(),
                Calendar.MONDAY, now);
        check(february.days == 28, "2026 is not a leap year");
        check(february.todayDay == 0, "today outside the displayed month must not be marked");
        check(february.events.isEmpty(), "empty month");

        CalendarMonth december = CalendarMonth.buildAt(2026, Calendar.DECEMBER,
                new ArrayList<>(), Calendar.MONDAY, now);
        check(december.leading + december.days <= 6 * 7, "grid must fit in six rows");

        System.out.println("CalendarMonthTest: PASS (leading offsets, headers, weekend columns, today, grouping, labels)");
    }
}
