package com.pm.task;

import com.pm.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 需求状态推进定时任务（全部日期驱动）
 * 每小时整点扫描：
 * READY→DEV：任一非 VOID 排期行存在格子日 ≤ 今天（不通知）；
 * DEV→TEST：全部非 QA 非 VOID 行格子日 < 今天（通知）；
 * TEST→ARCHIVING：全部非 VOID 行（含 QA）格子日 < 今天（通知）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequirementStatusTask {

    private final ScheduleService scheduleService;

    /**
     * 每小时整点执行（cron：秒 分 时 日 月 周）
     */
    @Scheduled(cron = "0 0 * * * *")
    public void advanceBySchedule() {
        int advanced = scheduleService.advanceBySchedule();
        log.info("需求状态推进定时任务执行完成：本次共推进 {} 个需求（READY→DEV / DEV→TEST / TEST→ARCHIVING）", advanced);
    }
}
