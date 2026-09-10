package com.pm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pm.entity.ScheduleRow;
import com.pm.vo.CraftScheduleBatchVO;
import com.pm.vo.CraftScheduleVO;
import com.pm.vo.RequirementGroupAggVO;
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
     * 排期组批量聚合（需求状态派生用）：多个需求各自按（需求, 工种, 负责人）聚合跨全部月份的非 VOID 行，
     * 一次查询带回每组 工期(MAX)/行数/有格子行数/格子合计/最大格子日，避免逐需求 N+1。
     * 排满判定：组无工期(pe IS NULL 或 ≤0) 或 已排合计 < 工期 → 未排满；
     * 日期窗口判定：rowsWithCells < rowCount 表示组内存在空行（阻塞推进），maxDay 为组内最大格子日
     */
    @Select("<script>SELECT r.requirement_id AS requirementId, r.craft AS craft, r.user_id AS userId, " +
            "MAX(r.planned_effort) AS plannedEffort, " +
            "COUNT(DISTINCT r.id) AS rowCount, " +
            "COUNT(DISTINCT CASE WHEN c.id IS NOT NULL THEN r.id END) AS rowsWithCells, " +
            "COALESCE(SUM(c.value), 0) AS total, " +
            "MAX(c.day) AS maxDay " +
            "FROM schedule_row r LEFT JOIN schedule_cell c ON c.row_id = r.id " +
            "WHERE r.status != 'VOID' AND r.requirement_id IN " +
            "<foreach collection='reqIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY r.requirement_id, r.craft, r.user_id</script>")
    List<RequirementGroupAggVO> selectGroupAggBatch(@Param("reqIds") List<Long> reqIds);

    /**
     * 排期组（同需求同工种同负责人，跨全部月份）已排格子合计，排除指定行（保存校验/顺延判定用：
     * 传入当前行 id，得到"其他月份"已排，加上本行新格子即为组内跨月合计）
     */
    @Select("SELECT COALESCE(SUM(c.value), 0) FROM schedule_cell c " +
            "JOIN schedule_row r ON r.id = c.row_id " +
            "WHERE r.requirement_id = #{requirementId} AND r.craft = #{craft} AND r.user_id = #{userId} " +
            "AND r.status != 'VOID' AND r.id != #{excludeRowId}")
    BigDecimal selectGroupCellsSum(@Param("requirementId") Long requirementId, @Param("craft") String craft,
                                   @Param("userId") Long userId, @Param("excludeRowId") Long excludeRowId);

    /**
     * 批量查询"开发已开工"的需求 id 集合（排期拦截用）：需求存在开发工种（FE/BE/OPS/UI）非 VOID 行
     * 且有格子日 ≤ today（开发并行节点黄/绿）；与需求组聚合结果配合，判断排期未排满且不可回退的需求
     */
    @Select("<script>SELECT DISTINCT r.requirement_id FROM schedule_row r " +
            "JOIN schedule_cell c ON c.row_id = r.id " +
            "WHERE r.status != 'VOID' AND r.craft IN ('FE','BE','OPS','UI') AND c.day &lt;= #{today} " +
            "AND r.requirement_id IN " +
            "<foreach collection='reqIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> selectStartedDevReqIds(@Param("reqIds") List<Long> reqIds, @Param("today") LocalDate today);

    /**
     * 按工种聚合格子日期区间（需求详情进度条按工种配色用）：
     * 该需求全部非 VOID 行（跨全部月份）按 craft 聚合 min/max 格子日；无格子的工种无记录（前端视为未排）
     */
    @Select("SELECT r.craft AS craft, DATE_FORMAT(MIN(c.day), '%Y-%m-%d') AS startDate, " +
            "DATE_FORMAT(MAX(c.day), '%Y-%m-%d') AS endDate " +
            "FROM schedule_row r JOIN schedule_cell c ON c.row_id = r.id " +
            "WHERE r.requirement_id = #{requirementId} AND r.status != 'VOID' GROUP BY r.craft")
    List<CraftScheduleVO> selectCraftDateRange(@Param("requirementId") Long requirementId);

    /**
     * 按工种聚合格子日期区间（批量版，需求列表进度缩略图用）：
     * 多个需求的全部非 VOID 行按（需求, 工种）聚合 min/max 格子日；无格子的（需求,工种）无记录
     */
    @Select("<script>SELECT r.requirement_id AS requirementId, r.craft AS craft, " +
            "DATE_FORMAT(MIN(c.day), '%Y-%m-%d') AS startDate, DATE_FORMAT(MAX(c.day), '%Y-%m-%d') AS endDate " +
            "FROM schedule_row r JOIN schedule_cell c ON c.row_id = r.id " +
            "WHERE r.requirement_id IN " +
            "<foreach collection='reqIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND r.status != 'VOID' GROUP BY r.requirement_id, r.craft</script>")
    List<CraftScheduleBatchVO> selectCraftDateRangeBatch(@Param("reqIds") List<Long> reqIds);
}
