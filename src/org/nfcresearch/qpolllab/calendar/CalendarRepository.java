package org.nfcresearch.qpolllab.calendar;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read-only access to the device calendar. Never writes to the provider. */
public final class CalendarRepository {
    private CalendarRepository() {
    }

    public static final class CalendarInfo {
        public final long id;
        public final String name;
        public final boolean visible;

        CalendarInfo(long id, String name, boolean visible) {
            this.id = id;
            this.name = name == null || name.isEmpty() ? "未命名日历" : name;
            this.visible = visible;
        }
    }

    public static boolean hasPermission(Context context) {
        try {
            return context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<CalendarInfo> calendars(Context context) {
        List<CalendarInfo> out = new ArrayList<>();
        if (!hasPermission(context)) return out;
        String[] projection = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.VISIBLE
        };
        try (Cursor cursor = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC")) {
            if (cursor == null) return out;
            while (cursor.moveToNext()) {
                out.add(new CalendarInfo(cursor.getLong(0), cursor.getString(1), cursor.getInt(2) != 0));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static Set<Long> visibleIds(Context context) {
        Set<Long> ids = new LinkedHashSet<>();
        for (CalendarInfo info : calendars(context)) if (info.visible) ids.add(info.id);
        return ids;
    }

    public static List<CalendarEvent> events(Context context, int year, int month, Set<Long> allowedIds) {
        List<CalendarEvent> out = new ArrayList<>();
        if (!hasPermission(context)) return out;
        Calendar start = Calendar.getInstance();
        start.clear();
        start.set(year, month, 1, 0, 0, 0);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.MONTH, 1);

        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, start.getTimeInMillis());
        ContentUris.appendId(builder, end.getTimeInMillis());
        String[] projection = {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        };
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(builder.build(), projection, null, null,
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (cursor == null) return out;
            while (cursor.moveToNext()) {
                long calendarId = cursor.getLong(5);
                if (allowedIds != null && !allowedIds.isEmpty() && !allowedIds.contains(calendarId)) continue;
                out.add(new CalendarEvent(
                        cursor.getLong(0),
                        calendarId,
                        cursor.getString(6),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getLong(3),
                        cursor.getInt(4) != 0));
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
