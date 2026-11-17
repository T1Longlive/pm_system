package com.pm.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 可拨动的测试时钟。
 *
 * <p>用途：业务代码通过注入的 {@link Clock} 取「今天/此刻」，测试里替换成本类即可
 * 在不 sleep、不依赖真实日期的情况下验证「时间流逝导致 DEV→TEST→ARCHIVING」、
 * 跨月顺延、乐观锁版本推进等时间敏感逻辑。
 *
 * <p>线程安全：测试串行使用，不追求并发安全。
 */
public final class TestClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    private TestClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** 固定到某一天的 00:00（默认时区） */
    public static TestClock fixedAt(LocalDate date) {
        return fixedAt(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /** 固定到某个时刻（默认时区） */
    public static TestClock fixedAt(LocalDateTime dateTime) {
        return fixedAt(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }

    public static TestClock fixedAt(Instant instant) {
        return new TestClock(instant, ZoneId.systemDefault());
    }

    /** 拨到指定日期（保留当前时分秒语义：统一取当天 00:00） */
    public void setDate(LocalDate date) {
        this.instant = date.atStartOfDay(zone).toInstant();
    }

    /** 前进/后退天数 */
    public void plusDays(long days) {
        this.instant = instant.plus(days, ChronoUnit.DAYS);
    }

    /** 前进/后退小时数（验证 nextVersionTime 的秒级推进） */
    public void plusHours(long hours) {
        this.instant = instant.plus(hours, ChronoUnit.HOURS);
    }

    /** 前进/后退月数（验证跨月/跨年顺延） */
    public void plusMonths(long months) {
        LocalDateTime local = LocalDateTime.ofInstant(instant, zone);
        this.instant = local.plusMonths(months).atZone(zone).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
