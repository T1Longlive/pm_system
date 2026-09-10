package com.pm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pm.entity.ScheduleCell;
import com.pm.vo.ReportWorkloadVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 排期格子表 Mapper
 */
public interface ScheduleCellMapper extends BaseMapper<ScheduleCell> {

    /**
     * 工作台汇总：某用户某自然月的排期工时合计（join 排期行+月表，排除 VOID 行；无数据返回 0）
     */
    @Select("SELECT COALESCE(SUM(c.value), 0) FROM schedule_cell c " +
            "JOIN schedule_row r ON r.id = c.row_id AND r.status != 'VOID' " +
            "JOIN schedule_month m ON m.id = r.month_id " +
            "WHERE r.user_id = #{userId} AND m.year = #{year} AND m.month = #{month}")
    BigDecimal sumUserMonthEffort(@Param("userId") Long userId,
                                  @Param("year") Integer year,
                                  @Param("month") Integer month);

    /**
     * 报表-人员负载：自起始年月（含）起，按 人员×自然月 聚合的排期工时合计
     * （排除 VOID 排期行；仅在有排期的月份有记录，缺失月份由前端补 0）
     */
    @Select("SELECT r.user_id AS userId, u.real_name AS realName, m.year AS year, m.month AS month, " +
            "COALESCE(SUM(c.value), 0) AS effort " +
            "FROM schedule_cell c " +
            "JOIN schedule_row r ON r.id = c.row_id AND r.status != 'VOID' " +
            "JOIN schedule_month m ON m.id = r.month_id " +
            "JOIN `user` u ON u.id = r.user_id " +
            "WHERE m.year > #{startYear} OR (m.year = #{startYear} AND m.month >= #{startMonth}) " +
            "GROUP BY r.user_id, u.real_name, m.year, m.month ORDER BY u.real_name, m.year, m.month")
    List<ReportWorkloadVO> sumEffortGroupByUserMonth(@Param("startYear") Integer startYear,
                                                     @Param("startMonth") Integer startMonth);
}
