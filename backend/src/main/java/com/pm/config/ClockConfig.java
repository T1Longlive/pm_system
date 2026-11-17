package com.pm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间来源统一入口：业务代码通过注入 Clock 获取「今天/此刻」，不直接调 LocalDate.now()。
 * 生产环境使用系统默认时区；测试可替换为可拨动的 MutableClock，
 * 从而在不 sleep、不依赖真实日期的情况下验证状态派生与跨月顺延等时间驱动逻辑。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
