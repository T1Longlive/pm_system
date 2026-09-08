package com.pm.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 月度排期表视图 VO（GET /api/schedule/months/{monthId}/view）
 * 结构：月份信息 + 整月日历（控制列灰显）+ 按需求分组的排期矩阵行
 */
@Data
public class ScheduleViewVO {

    /** 月表信息 */
    private ScheduleMonthVO month;

    /** 整月日历（同 GET /api/calendar，前端据此灰显周末/节假日列） */
    private List<CalendarDayVO> days;

    /** 按需求分组（groups 按 seq 升序；行内 rows 按工种+id 升序；VOID 行也返回，前端灰显） */
    private List<GroupVO> groups;

    /**
     * 需求分组：一个需求在当月表占连续多行（需求级列合并展示），seq 为当月表内展示序号
     */
    @Data
    public static class GroupVO {

        /** 当月表内展示序号（编号列） */
        private Integer seq;

        /** 需求 ID */
        private Long requirementId;

        /** 需求编号，如 REQ-ACE-0001 */
        private String reqNo;

        /** 需求名称 */
        private String title;

        /** 所属项目 ID */
        private Long projectId;

        /** 所属项目名称 */
        private String projectName;

        /** 策划姓名 */
        private String plannerName;

        /** 优先级：1高 2中 3低 */
        private Integer priority;

        /** 该需求的排期行 */
        private List<RowVO> rows;
    }

    /**
     * 排期行：工种×负责人一行
     */
    @Data
    public static class RowVO {

        /** 行 ID */
        private Long rowId;

        /** 工种：PLANNER/FE/BE/OPS/QA/UI */
        private String craft;

        /** 负责人 ID */
        private Long userId;

        /** 负责人姓名 */
        private String realName;

        /** 行状态：TODO待开始/VOID作废（已排/排期中/未排期三态由前端按工期与已排合计派生） */
        private String status;

        /** 1=跨月自动顺延生成的行 */
        private Integer isOverflow;

        /** 计划工期（PM 填写，可空） */
        private BigDecimal plannedEffort;

        /** 行版本（毫秒时间戳，编辑保存时原样回传做乐观锁校验） */
        private Long updatedAt;

        /** 逐日工作量：key=日期（2026-09-04），value=当天工作量 */
        private Map<String, BigDecimal> cells;
    }
}
