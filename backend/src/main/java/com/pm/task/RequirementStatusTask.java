package com.pm.task;

import com.pm.service.RequirementStageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 需求状态对账定时任务（状态派生化）：
 * status 由「排期数据 + 日期」stage 纯函数派生，写路径触发时已实时对账；
 * 本任务只负责捕捉纯时间流逝导致的变化（如全部格子日过了今天 → TEST/ARCHIVING），
 * 每小时整点对全部非终态需求全量对账（派生值 vs 库中值，不一致则更新并通知）；
 * 另在应用启动完成后立即执行一次，收敛历史遗留状态（如派生化前的 READY 存量）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequirementStatusTask {

    private final RequirementStageService stageSyncService;

    /**
     * 每小时整点执行（cron：秒 分 时 日 月 周）
     */
    @Scheduled(cron = "0 0 * * * *")
    public void syncStages() {
        int changed = stageSyncService.syncAllStages();
        if (changed > 0) {
            log.info("需求状态对账定时任务执行完成：本次共对账变更 {} 个需求", changed);
        }
    }

    /**
     * 应用启动完成后立即对账一次：收敛历史遗留状态（历史 READY 存量等），保证派生口径从启动起生效
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        int changed = stageSyncService.syncAllStages();
        log.info("需求状态启动对账完成：本次共对账变更 {} 个需求", changed);
    }
}
