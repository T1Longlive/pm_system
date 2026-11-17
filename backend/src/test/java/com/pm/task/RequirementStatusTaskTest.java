package com.pm.task;

import com.pm.service.RequirementStageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 状态对账定时任务单测：整点任务与启动对账都委托给 RequirementStageService.syncAllStages。
 */
@ExtendWith(MockitoExtension.class)
class RequirementStatusTaskTest {

    @Mock
    private RequirementStageService stageSyncService;

    private RequirementStatusTask task;

    @BeforeEach
    void setUp() {
        task = new RequirementStatusTask(stageSyncService);
    }

    @Test
    @DisplayName("整点任务：有变更与无变更都不抛出")
    void syncStages() {
        when(stageSyncService.syncAllStages()).thenReturn(3, 0);

        task.syncStages();
        task.syncStages();

        verify(stageSyncService, times(2)).syncAllStages();
    }

    @Test
    @DisplayName("启动对账：应用启动完成后立即执行一次")
    void syncOnStartup() {
        when(stageSyncService.syncAllStages()).thenReturn(0);

        task.syncOnStartup();

        verify(stageSyncService).syncAllStages();
    }
}
