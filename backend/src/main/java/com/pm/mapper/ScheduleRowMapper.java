package com.pm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pm.entity.ScheduleRow;
import com.pm.vo.CraftScheduleVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 排期行表 Mapper
 */
public interface ScheduleRowMapper extends BaseMapper<ScheduleRow> {

    /**
     * 同人同日工作量合计（保存校验用）：
     * 统计该用户在指定日期、除本行外全部非 VOID 行（含其他需求、其他月份同日的行，day 为绝对日期天然跨月对齐）的格子合计
     */
    @Select("SELECT COALESCE(SUM(c.value), 0) FROM schedule_cell c " +
            "JOIN schedule_row r ON r.id = c.row_id " +
            "WHERE r.user_id = #{userId} AND c.day = #{day} AND r.status != 'VOID' AND r.id != #{excludeRowId}")
    BigDecimal selectUserDayTotal(@Param("userId") Long userId, @Param("day") LocalDate day,
                                  @Param("excludeRowId") Long excludeRowId);

    /**
     * 当月表已有的需求数（DISTINCT requirement_id），用于生成新进需求的展示序号 seq=已有需求数+1
     */
    @Select("SELECT COUNT(DISTINCT requirement_id) FROM schedule_row WHERE month_id = #{monthId}")
    Long countRequirementsInMonth(@Param("monthId") Long monthId);

    /**
     * 定时任务候选（READY→DEV，日期驱动）：查询状态为 READY 且任一非 VOID 行（不限工种）
     * 存在已排日期 ≤ today 的格子（对应"任一工种开工即开发中"）的需求 ID 集合
     */
    @Select("SELECT DISTINCT r.requirement_id FROM schedule_row r " +
            "JOIN schedule_cell c ON c.row_id = r.id " +
            "JOIN requirement q ON q.id = r.requirement_id " +
            "WHERE q.status = 'READY' AND r.status != 'VOID' AND c.day <= #{today}")
    List<Long> selectReadyReqIdsWithDueCell(@Param("today") LocalDate today);

    /**
     * 定时任务候选（DEV→TEST，日期驱动）：需求为 DEV 且
     * 1) 存在非 QA 非 VOID 排期行（开发类行）；
     * 2) 全部此类行"已过排期"——每行必须有格子，且不存在 day >= today 的格子（无格子行视为未过排期，阻塞推进）
     */
    @Select("SELECT q.id FROM requirement q WHERE q.status = 'DEV' " +
            "AND EXISTS (SELECT 1 FROM schedule_row r WHERE r.requirement_id = q.id AND r.status != 'VOID' AND r.craft != 'QA') " +
            "AND NOT EXISTS (SELECT 1 FROM schedule_row r WHERE r.requirement_id = q.id AND r.status != 'VOID' AND r.craft != 'QA' " +
            "AND (NOT EXISTS (SELECT 1 FROM schedule_cell c WHERE c.row_id = r.id) " +
            "OR EXISTS (SELECT 1 FROM schedule_cell c WHERE c.row_id = r.id AND c.day >= #{today})))")
    List<Long> selectDevReqIdsAllPassed(@Param("today") LocalDate today);

    /**
     * 定时任务候选（TEST→ARCHIVING，日期驱动）：需求为 TEST 且
     * 1) 存在非 VOID 排期行（含 QA）；2) 全部行"已过排期"（口径同上，含 QA 行）
     */
    @Select("SELECT q.id FROM requirement q WHERE q.status = 'TEST' " +
            "AND EXISTS (SELECT 1 FROM schedule_row r WHERE r.requirement_id = q.id AND r.status != 'VOID') " +
            "AND NOT EXISTS (SELECT 1 FROM schedule_row r WHERE r.requirement_id = q.id AND r.status != 'VOID' " +
            "AND (NOT EXISTS (SELECT 1 FROM schedule_cell c WHERE c.row_id = r.id) " +
            "OR EXISTS (SELECT 1 FROM schedule_cell c WHERE c.row_id = r.id AND c.day >= #{today})))")
    List<Long> selectTestReqIdsAllPassed(@Param("today") LocalDate today);

    /**
     * 排满判定（需求 PENDING→READY 用）：统计该需求"未排满"的非 VOID 行数。
     * 未排满 = 无工期 / 工期≤0 / 格子合计 < 工期；返回 0 且存在非 VOID 行时即为全部排满
     */
    @Select("SELECT COUNT(*) FROM schedule_row r WHERE r.requirement_id = #{requirementId} AND r.status != 'VOID' " +
            "AND (r.planned_effort IS NULL OR r.planned_effort <= 0 " +
            "OR r.planned_effort > (SELECT COALESCE(SUM(c.value), 0) FROM schedule_cell c WHERE c.row_id = r.id))")
    Long countNotFullRows(@Param("requirementId") Long requirementId);

    /**
     * 按工种聚合格子日期区间（需求详情进度条按工种配色用）：
     * 该需求全部非 VOID 行（跨全部月份）按 craft 聚合 min/max 格子日；无格子的工种无记录（前端视为未排）
     */
    @Select("SELECT r.craft AS craft, DATE_FORMAT(MIN(c.day), '%Y-%m-%d') AS startDate, " +
            "DATE_FORMAT(MAX(c.day), '%Y-%m-%d') AS endDate " +
            "FROM schedule_row r JOIN schedule_cell c ON c.row_id = r.id " +
            "WHERE r.requirement_id = #{requirementId} AND r.status != 'VOID' GROUP BY r.craft")
    List<CraftScheduleVO> selectCraftDateRange(@Param("requirementId") Long requirementId);
}
