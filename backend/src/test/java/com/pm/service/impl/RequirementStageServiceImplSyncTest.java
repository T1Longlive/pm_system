package com.pm.service.impl;

import com.pm.common.NotifyTypes;
import com.pm.entity.BizOperationLog;
import com.pm.entity.Requirement;
import com.pm.entity.RequirementParticipant;
import com.pm.mapper.BizOperationLogMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.ScheduleRowMapper;
import com.pm.service.NotificationService;
import com.pm.support.MybatisPlusTestSupport;
import com.pm.support.TestClock;
import com.pm.vo.RequirementGroupAggVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RequirementStageServiceImpl} 写路径（syncStage）的单元测试。
 *
 * <p>与 {@link RequirementStageServiceImplTest} 的分工：
 * 前者测纯函数 deriveStage（算什么），本类测对账落库（怎么落）——
 * 条件更新、终态跳过、通知去重、日志只记用户触发。
 *
 * <p>不启动 Spring、不连数据库：Mapper 与通知服务全部 mock。
 */
@ExtendWith(MockitoExtension.class)
class RequirementStageServiceImplSyncTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    private static final Long REQ_ID = 1L;
    private static final Long OPERATOR_ID = 99L;

    @Mock
    private RequirementMapper requirementMapper;
    @Mock
    private ScheduleRowMapper scheduleRowMapper;
    @Mock
    private RequirementParticipantMapper participantMapper;
    @Mock
    private BizOperationLogMapper operationLogMapper;
    @Mock
    private NotificationService notificationService;

    private RequirementStageServiceImpl service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // LambdaUpdateWrapper 需要实体的 TableInfo 缓存，纯单测不会自动建立
        MybatisPlusTestSupport.initLambdaCache(Requirement.class);
    }

    @BeforeEach
    void setUp() {
        service = new RequirementStageServiceImpl(requirementMapper, scheduleRowMapper,
                participantMapper, operationLogMapper, notificationService, TestClock.fixedAt(TODAY));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("需求不存在 → 直接返回 false，不查排期、不写库")
    void missingRequirementReturnsFalse() {
        when(requirementMapper.selectById(REQ_ID)).thenReturn(null);

        assertThat(service.syncStage(REQ_ID, OPERATOR_ID)).isFalse();

        verifyNoInteractions(scheduleRowMapper, notificationService, operationLogMapper);
    }

    @Test
    @DisplayName("已归档需求不参与对账：不查排期、不更新、不通知")
    void archivedRequirementSkipped() {
        when(requirementMapper.selectById(REQ_ID)).thenReturn(requirement("ARCHIVED"));

        assertThat(service.syncStage(REQ_ID, OPERATOR_ID)).isFalse();

        verifyNoInteractions(scheduleRowMapper, notificationService, operationLogMapper);
    }

    @Test
    @DisplayName("派生值变化 → 条件更新成功、写 STAGE_SYNC 日志、按人去重通知参与人")
    void changedStageAppliesUpdateAndNotifies() {
        stubPendingRequirement();
        // FE 组已排满但最大格子日 == 今天 → 开发窗口未结束 → DEV
        when(scheduleRowMapper.selectGroupAggBatch(List.of(REQ_ID)))
                .thenReturn(List.of(group("FE", "3", "3", 1, 1, TODAY)));
        when(requirementMapper.update(isNull(), any())).thenReturn(1);
        // 同一用户出现两次，通知必须去重；另一用户各一条
        when(participantMapper.selectList(any()))
                .thenReturn(List.of(participant(7L), participant(7L), participant(8L)));

        assertThat(service.syncStage(REQ_ID, OPERATOR_ID)).isTrue();

        // 条件更新：WHERE id = ? AND status = 旧值
        verify(requirementMapper).update(isNull(), any());

        // 日志：用户触发才写，动作固定 STAGE_SYNC
        ArgumentCaptor<BizOperationLog> logCaptor = ArgumentCaptor.forClass(BizOperationLog.class);
        verify(operationLogMapper).insert(logCaptor.capture());
        BizOperationLog log = logCaptor.getValue();
        assertThat(log.getAction()).isEqualTo("STAGE_SYNC");
        assertThat(log.getBizType()).isEqualTo("REQUIREMENT");
        assertThat(log.getBizId()).isEqualTo(REQ_ID);
        assertThat(log.getOperatorId()).isEqualTo(OPERATOR_ID);
        assertThat(log.getRemark()).contains("PENDING", "DEV");

        // 通知：两个不同用户各一条，内容包含目标阶段
        verify(notificationService, times(2)).send(anyLong(),
                eq(NotifyTypes.REQ_STATUS_CHANGED), anyString(),
                contains("进入开发中"), eq("REQUIREMENT"), eq(REQ_ID));
    }

    @Test
    @DisplayName("派生值未变化 → 不写库、不通知、不记日志")
    void unchangedStageDoesNothing() {
        when(requirementMapper.selectById(REQ_ID)).thenReturn(requirement("DEV"));
        when(scheduleRowMapper.selectGroupAggBatch(List.of(REQ_ID)))
                .thenReturn(List.of(group("FE", "3", "3", 1, 1, TODAY)));

        assertThat(service.syncStage(REQ_ID, OPERATOR_ID)).isFalse();

        verify(requirementMapper, never()).update(any(), any());
        verifyNoInteractions(notificationService, operationLogMapper);
    }

    @Test
    @DisplayName("条件更新影响 0 行（期间被取消/归档/他人改写）→ 放弃本次对账，不通知不记日志")
    void lostConditionalUpdateDoesNothing() {
        stubPendingRequirement();
        when(scheduleRowMapper.selectGroupAggBatch(List.of(REQ_ID)))
                .thenReturn(List.of(group("FE", "3", "3", 1, 1, TODAY)));
        when(requirementMapper.update(isNull(), any())).thenReturn(0);

        assertThat(service.syncStage(REQ_ID, OPERATOR_ID)).isFalse();

        verifyNoInteractions(notificationService, operationLogMapper);
    }

    @Test
    @DisplayName("定时对账（operatorId=null）即使状态变化也不写日志，但通知照发")
    void systemSyncDoesNotWriteLog() {
        stubPendingRequirement();
        when(scheduleRowMapper.selectGroupAggBatch(List.of(REQ_ID)))
                .thenReturn(List.of(group("FE", "3", "3", 1, 1, TODAY)));
        when(requirementMapper.update(isNull(), any())).thenReturn(1);
        when(participantMapper.selectList(any())).thenReturn(List.of(participant(7L)));

        assertThat(service.syncStage(REQ_ID, null)).isTrue();

        verifyNoInteractions(operationLogMapper);
        verify(notificationService, times(1)).send(anyLong(), eq(NotifyTypes.REQ_STATUS_CHANGED),
                anyString(), contains("进入开发中"), eq("REQUIREMENT"), eq(REQ_ID));
    }

    // ==================== 辅助 ====================

    private void stubPendingRequirement() {
        when(requirementMapper.selectById(REQ_ID)).thenReturn(requirement("PENDING"));
    }

    private static Requirement requirement(String status) {
        Requirement requirement = new Requirement();
        requirement.setId(REQ_ID);
        requirement.setProjectId(10L);
        requirement.setTitle("测试需求");
        requirement.setStatus(status);
        return requirement;
    }

    private static RequirementParticipant participant(Long userId) {
        RequirementParticipant participant = new RequirementParticipant();
        participant.setRequirementId(REQ_ID);
        participant.setUserId(userId);
        participant.setCraft("FE");
        return participant;
    }

    private static RequirementGroupAggVO group(String craft, String plannedEffort, String total,
                                               long rowCount, long rowsWithCells, LocalDate maxDay) {
        RequirementGroupAggVO group = new RequirementGroupAggVO();
        group.setRequirementId(REQ_ID);
        group.setCraft(craft);
        group.setPlannedEffort(new BigDecimal(plannedEffort));
        group.setTotal(new BigDecimal(total));
        group.setRowCount(rowCount);
        group.setRowsWithCells(rowsWithCells);
        group.setMaxDay(maxDay);
        return group;
    }
}
