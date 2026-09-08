package com.pm.service;

import com.pm.dto.CalendarUpdateDTO;
import com.pm.entity.CalendarDay;
import com.pm.vo.CalendarMonthVO;

import java.time.LocalDate;
import java.util.Map;

/**
 * 工作日历服务：排期格子可填性、跨月顺延判定、状态定时任务均以此日历为准
 */
public interface CalendarService {

    /**
     * 查询整月日历（任何登录用户可查）：返回整月每天，缺失日期按周末规则回退推导（不落库）
     */
    CalendarMonthVO getMonth(Integer year, Integer month);

    /**
     * 查询整月日历明细（含缺失日期按周末规则回退推导，不落库），供排期模块做格子校验与跨月顺延判定。
     * 可排班日 = type 为 WORKDAY 或 ADJUSTED 的日期
     */
    Map<LocalDate, CalendarDay> getMonthDays(int year, int month);

    /**
     * 维护某天的日历类型（仅管理员，权限在 Controller 层校验）：日期不存在则插入，存在则更新（name 传 null 清空）
     */
    void updateDay(LocalDate day, CalendarUpdateDTO dto);
}
