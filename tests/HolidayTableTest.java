package org.nfcresearch.qpolllab;

import java.time.LocalDate;
import org.nfcresearch.qpolllab.calendar.HolidayTable;

/** The bundled statutory holiday table must match the State Council notices. */
public final class HolidayTableTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static int kind(String date) {
        LocalDate day = LocalDate.parse(date);
        return HolidayTable.kindOf(day.getYear(), day.getMonthValue() - 1, day.getDayOfMonth());
    }

    public static void main(String[] args) {
        // 2026: 国办发明电〔2025〕7号
        check(kind("2026-01-01") == HolidayTable.REST, "元旦首日放假");
        check(kind("2026-01-03") == HolidayTable.REST, "元旦末日放假");
        check(kind("2026-01-04") == HolidayTable.WORK, "元旦调休上班");
        check(kind("2026-01-05") == HolidayTable.NONE, "普通工作日不标记");

        check(kind("2026-02-14") == HolidayTable.WORK, "春节调休上班");
        check(kind("2026-02-15") == HolidayTable.REST, "春节首日放假");
        check(kind("2026-02-23") == HolidayTable.REST, "春节末日放假");
        check(kind("2026-02-24") == HolidayTable.NONE, "春节后恢复工作");
        check(kind("2026-02-28") == HolidayTable.WORK, "春节调休上班");

        check(kind("2026-04-04") == HolidayTable.REST, "清明放假");
        check(kind("2026-04-06") == HolidayTable.REST, "清明末日");
        check(kind("2026-04-07") == HolidayTable.NONE, "清明后恢复");

        check(kind("2026-05-01") == HolidayTable.REST, "劳动节首日");
        check(kind("2026-05-05") == HolidayTable.REST, "劳动节末日");
        check(kind("2026-05-09") == HolidayTable.WORK, "劳动节调休上班");

        check(kind("2026-06-19") == HolidayTable.REST, "端午首日");
        check(kind("2026-06-21") == HolidayTable.REST, "端午末日");
        check(kind("2026-06-22") == HolidayTable.NONE, "端午后恢复");

        check(kind("2026-09-20") == HolidayTable.WORK, "国庆调休上班");
        check(kind("2026-09-25") == HolidayTable.REST, "中秋首日");
        check(kind("2026-09-27") == HolidayTable.REST, "中秋末日");
        check(kind("2026-10-01") == HolidayTable.REST, "国庆首日");
        check(kind("2026-10-07") == HolidayTable.REST, "国庆末日");
        check(kind("2026-10-08") == HolidayTable.NONE, "国庆后恢复");
        check(kind("2026-10-10") == HolidayTable.WORK, "国庆调休上班");

        // 2025
        check(kind("2025-01-28") == HolidayTable.REST, "2025 春节首日");
        check(kind("2025-02-04") == HolidayTable.REST, "2025 春节末日");
        check(kind("2025-01-26") == HolidayTable.WORK, "2025 春节调休");
        check(kind("2025-10-08") == HolidayTable.REST, "2025 国庆中秋末日");
        check(kind("2025-10-11") == HolidayTable.WORK, "2025 国庆调休");

        check(kind("2024-01-01") == HolidayTable.NONE, "未内置年份返回无标记");
        check(kind("2026-12-31") == HolidayTable.NONE, "未公布日期返回无标记");
        check(HolidayTable.hasData(2026), "2026 数据存在");
        check(!HolidayTable.hasData(2030), "2030 数据不存在");
        check(!HolidayTable.entries().isEmpty(), "解析条目非空");

        System.out.println("HolidayTableTest: PASS (2026 official arrangement, 2025 fallback, unknown years)");
    }
}
