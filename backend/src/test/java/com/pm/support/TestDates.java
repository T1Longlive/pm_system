package com.pm.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 测试日期工具：基于「周末规则」推导工作日。
 *
 * <p>测试库的 calendar_day 由 CalendarServiceImpl 启动时按周末规则预生成，
 * 因此周一至周五即工作日；节假日/调休场景需要显式写 calendar_day。
 */
public final class TestDates {

    private TestDates() {
    }

    /** 当月全部工作日（周一至周五） */
    public static List<LocalDate> workdays(YearMonth month) {
        return month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1))
                .filter(TestDates::isWorkday)
                .toList();
    }

    public static LocalDate firstWorkday(YearMonth month) {
        return workdays(month).get(0);
    }

    public static LocalDate firstDayOfWeek(YearMonth month, DayOfWeek dayOfWeek) {
        return month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1))
                .filter(d -> d.getDayOfWeek() == dayOfWeek)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        month + " 不存在 " + dayOfWeek));
    }

    public static boolean isWorkday(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY
                && day.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
