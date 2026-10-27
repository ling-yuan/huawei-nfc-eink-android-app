package org.nfcresearch.qpolllab.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mainland China statutory holidays and make-up workdays.
 *
 * Sources: 国务院办公厅关于2026年部分节假日安排的通知（国办发明电〔2025〕7号）and the
 * equivalent 2025 notice. Years without published data simply report {@link #NONE}.
 */
public final class HolidayTable {
    public static final int NONE = 0;
    public static final int REST = 1;
    public static final int WORK = 2;

    private static final String DATA = String.join("\n",
            // 2025
            "rest 2025-01-01 2025-01-01",
            "work 2025-01-26 2025-01-26",
            "rest 2025-01-28 2025-02-04",
            "work 2025-02-08 2025-02-08",
            "rest 2025-04-04 2025-04-06",
            "work 2025-04-27 2025-04-27",
            "rest 2025-05-01 2025-05-05",
            "rest 2025-05-31 2025-06-02",
            "work 2025-09-28 2025-09-28",
            "rest 2025-10-01 2025-10-08",
            "work 2025-10-11 2025-10-11",
            // 2026
            "rest 2026-01-01 2026-01-03",
            "work 2026-01-04 2026-01-04",
            "work 2026-02-14 2026-02-14",
            "rest 2026-02-15 2026-02-23",
            "work 2026-02-28 2026-02-28",
            "rest 2026-04-04 2026-04-06",
            "rest 2026-05-01 2026-05-05",
            "work 2026-05-09 2026-05-09",
            "rest 2026-06-19 2026-06-21",
            "work 2026-09-20 2026-09-20",
            "rest 2026-09-25 2026-09-27",
            "rest 2026-10-01 2026-10-07",
            "work 2026-10-10 2026-10-10");

    private static final Map<LocalDate, Integer> DAYS = new HashMap<>();

    static {
        load(DATA);
    }

    private HolidayTable() {
    }

    private static void load(String data) {
        for (String raw : data.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\s+");
            if (fields.length < 3) continue;
            int kind = fields[0].equalsIgnoreCase("rest") ? REST : WORK;
            try {
                LocalDate from = LocalDate.parse(fields[1]);
                LocalDate to = LocalDate.parse(fields[2]);
                for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) DAYS.put(day, kind);
            } catch (RuntimeException ignored) {
                // A malformed row must not disable the remaining table.
            }
        }
    }

    /** @param month zero based, matching java.util.Calendar */
    public static int kindOf(int year, int month, int day) {
        try {
            Integer kind = DAYS.get(LocalDate.of(year, month + 1, day));
            return kind == null ? NONE : kind;
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    public static boolean hasData(int year) {
        for (LocalDate day : DAYS.keySet()) if (day.getYear() == year) return true;
        return false;
    }

    /** Parses the bundled table for tests and for future extension. */
    public static List<int[]> entries() {
        List<int[]> out = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> entry : DAYS.entrySet()) {
            LocalDate day = entry.getKey();
            out.add(new int[]{day.getYear(), day.getMonthValue() - 1, day.getDayOfMonth(), entry.getValue()});
        }
        return out;
    }
}
