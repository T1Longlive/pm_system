package com.pm.service.impl;

import com.pm.entity.Requirement;
import com.pm.mapper.BizOperationLogMapper;
import com.pm.mapper.RequirementMapper;
import com.pm.mapper.RequirementParticipantMapper;
import com.pm.mapper.ScheduleRowMapper;
import com.pm.service.NotificationService;
import com.pm.vo.RequirementGroupAggVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link RequirementStageServiceImpl#deriveStage} 的纯单元测试。
 *
 * <p>⚠️ 这里**不启动 Spring 上下文、不连数据库** —— 这正是它能跑在云端构建机上的原因
 * （原先唯一的 {@code PmApplicationTests} 是 {@code @SpringBootTest}，要真实 MySQL，云端跑不了，
 * 于是不得不在打包时加 {@code -DskipTests}，等于关掉了整个测试门禁）。
 *
 * <p>{@code deriveStage} 是无副作用的纯函数：只读入参（需求实体 + 排期组聚合 + today），
 * 五个注入依赖在它里面完全用不到（只有 {@code syncStage}/{@code syncAllStages} 会用到），
 * 所以构造时给 mock 即可，不做任何 stub。
 *
 * <p>覆盖的是业务规则本身：<b>排期数据 + 日期 = 唯一真相源，需求 status 是它的实时投影</b>。
 */
class RequirementStageServiceImplTest {

    /** 固定"今天"，让日期驱动的窗口判定可复现 —— 不依赖运行时的当前日期 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);

    private final RequirementStageServiceImpl service = new RequirementStageServiceImpl(
            mock(RequirementMapper.class),
            mock(ScheduleRowMapper.class),
            mock(RequirementParticipantMapper.class),
            mock(BizOperationLogMapper.class),
            mock(NotificationService.class));

    // ==================== 构造入参的小工具 ====================

    private static Requirement requirement(String status) {
        Requirement r = new Requirement();
        r.setId(1L);
        r.setTitle("测试需求");
        r.setStatus(status);
        return r;
    }

    /** 自由构造一个排期组；plannedEffort / total 传 null 表示"没填工期 / 没有格子" */
    private static RequirementGroupAggVO group(String craft, String plannedEffort, String total,
                                               long rowCount, long rowsWithCells, LocalDate maxDay) {
        RequirementGroupAggVO g = new RequirementGroupAggVO();
        g.setRequirementId(1L);
        g.setCraft(craft);
        g.setPlannedEffort(plannedEffort == null ? null : new BigDecimal(plannedEffort));
        g.setTotal(total == null ? null : new BigDecimal(total));
        g.setRowCount(rowCount);
        g.setRowsWithCells(rowsWithCells);
        g.setMaxDay(maxDay);
        return g;
    }

    /** 已排满、且窗口已经过去（最大格子日 < today）—— 会"放行"到下一阶段 */
    private static RequirementGroupAggVO passedGroup(String craft) {
        return group(craft, "3", "3", 1, 1, TODAY.minusDays(1));
    }

    /** 已排满、但窗口还没过去（最大格子日 == today）—— 会"卡住"当前阶段 */
    private static RequirementGroupAggVO ongoingGroup(String craft) {
        return group(craft, "3", "3", 1, 1, TODAY);
    }

    private String derive(String status, List<RequirementGroupAggVO> groups) {
        return service.deriveStage(requirement(status), groups, TODAY);
    }

    // ==================== 用例 ====================

    @Nested
    @DisplayName("人工终态：归档 / 取消是仅有的两个人工决定，永不被派生改写")
    class ManualFinalStates {

        @Test
        @DisplayName("已取消 → CANCELLED，即便排期数据显示已进入开发")
        void cancelledIsFinal() {
            assertThat(derive("CANCELLED", List.of(ongoingGroup("DEV")))).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("已归档 → ARCHIVED，即便排期数据显示还在开发中")
        void archivedIsFinal() {
            assertThat(derive("ARCHIVED", List.of(ongoingGroup("DEV")))).isEqualTo("ARCHIVED");
        }
    }

    @Nested
    @DisplayName("PENDING 待排期：没有排期行，或任一排期组没排满")
    class Pending {

        @Test
        @DisplayName("没有任何非 VOID 排期行 → PENDING")
        void noGroups() {
            assertThat(derive("DEV", List.of())).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("组内没填工期（plannedEffort 为 null）→ PENDING")
        void plannedEffortNull() {
            assertThat(derive("DEV", List.of(group("DEV", null, "3", 1, 1, TODAY.minusDays(1)))))
                    .isEqualTo("PENDING");
        }

        @Test
        @DisplayName("工期为 0 → PENDING（工期必须为正才有意义）")
        void plannedEffortZero() {
            assertThat(derive("DEV", List.of(group("DEV", "0", "0", 1, 1, TODAY.minusDays(1)))))
                    .isEqualTo("PENDING");
        }

        @Test
        @DisplayName("工期为负 → PENDING")
        void plannedEffortNegative() {
            assertThat(derive("DEV", List.of(group("DEV", "-1", "0", 1, 1, TODAY.minusDays(1)))))
                    .isEqualTo("PENDING");
        }

        @Test
        @DisplayName("格子合计 < 工期 → PENDING（排期没排够）")
        void totalLessThanPlanned() {
            assertThat(derive("DEV", List.of(group("DEV", "5", "4", 1, 1, TODAY.minusDays(1)))))
                    .isEqualTo("PENDING");
        }

        @Test
        @DisplayName("边界：格子合计 == 工期 就算排满，不再算 PENDING（判据是 < 而不是 ≤）")
        void totalEqualsPlannedIsFull() {
            assertThat(derive("DEV", List.of(group("DEV", "5", "5", 1, 1, TODAY.minusDays(1)))))
                    .isEqualTo("ARCHIVING");
        }

        @Test
        @DisplayName("多个组里只要有一个没排满 → PENDING")
        void oneGroupNotFullMakesPending() {
            List<RequirementGroupAggVO> groups = List.of(
                    passedGroup("DEV"),
                    group("QA", "5", "1", 1, 1, TODAY.minusDays(1)));
            assertThat(derive("DEV", groups)).isEqualTo("PENDING");
        }
    }

    @Nested
    @DisplayName("DEV 开发中：存在非 QA 行，且开发窗口还没结束")
    class Dev {

        @Test
        @DisplayName("非 QA 行的最大格子日 == 今天（窗口未结束）→ DEV")
        void devWindowStillOpen() {
            assertThat(derive("PENDING", List.of(ongoingGroup("DEV")))).isEqualTo("DEV");
        }

        @Test
        @DisplayName("存在空行（有格子的行数 < 总行数）→ DEV（空行阻塞推进）")
        void emptyRowBlocks() {
            assertThat(derive("PENDING", List.of(group("DEV", "3", "3", 2, 1, TODAY.minusDays(1)))))
                    .isEqualTo("DEV");
        }

        @Test
        @DisplayName("组内一个格子都没有（maxDay 为 null）→ DEV")
        void noCellAtAll() {
            assertThat(derive("PENDING", List.of(group("DEV", "3", "3", 1, 1, null))))
                    .isEqualTo("DEV");
        }

        @Test
        @DisplayName("只有 QA 组时不走开发窗口判定 —— QA 不阻塞开发阶段结束")
        void qaOnlyGroupSkipsDevCheck() {
            assertThat(derive("PENDING", List.of(ongoingGroup("QA")))).isEqualTo("TEST");
        }
    }

    @Nested
    @DisplayName("TEST 测试中 / ARCHIVING 待归档：开发窗口已结束后的判定")
    class TestAndArchiving {

        @Test
        @DisplayName("开发行都结束了，但 QA 行最大格子日 == 今天 → TEST")
        void qaWindowStillOpen() {
            List<RequirementGroupAggVO> groups = List.of(passedGroup("DEV"), ongoingGroup("QA"));
            assertThat(derive("DEV", groups)).isEqualTo("TEST");
        }

        @Test
        @DisplayName("全部行（含 QA）的格子日都是过去 → ARCHIVING")
        void allPassed() {
            List<RequirementGroupAggVO> groups = List.of(passedGroup("DEV"), passedGroup("QA"));
            assertThat(derive("TEST", groups)).isEqualTo("ARCHIVING");
        }

        @Test
        @DisplayName("边界：最大格子日 == 今天 不算过去（判据是 isBefore，不含今天）")
        void todayIsNotPast() {
            assertThat(derive("DEV", List.of(ongoingGroup("DEV")))).isEqualTo("DEV");
        }

        @Test
        @DisplayName("多组时只要有一组没结束，就不能进 ARCHIVING")
        void oneUnfinishedBlocksArchiving() {
            List<RequirementGroupAggVO> groups = List.of(passedGroup("DEV"), passedGroup("ART"));
            assertThat(derive("DEV", groups)).isEqualTo("ARCHIVING");

            List<RequirementGroupAggVO> withUnfinished = List.of(
                    passedGroup("DEV"), passedGroup("ART"), ongoingGroup("QA"));
            assertThat(derive("DEV", withUnfinished)).isEqualTo("TEST");
        }
    }
}
