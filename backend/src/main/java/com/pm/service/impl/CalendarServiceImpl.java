package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pm.common.BizException;
import com.pm.dto.CalendarUpdateDTO;
import com.pm.entity.CalendarDay;
import com.pm.mapper.CalendarDayMapper;
import com.pm.service.CalendarService;
import com.pm.vo.CalendarDayVO;
import com.pm.vo.CalendarMonthVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作日历服务实现
 * 日历规则：
 * 1. WORKDAY 工作日 / WEEKEND 周末 / HOLIDAY 法定节假日 / ADJUSTED 调休上班；
 *    其中 WORKDAY 与 ADJUSTED 视为「可排班日」，WEEKEND 与 HOLIDAY 不可排班
 * 2. 启动时自动预生成：若未来 12 个月日历不足则按周末规则补生成（周六周日标 WEEKEND，其余标 WORKDAY），已存在的日期跳过
 * 3. 法定节假日/调休由管理员通过 PUT /api/calendar/{day} 维护
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarServiceImpl implements CalendarService, ApplicationRunner {

    /** 类型：工作日 */
    private static final String TYPE_WORKDAY = "WORKDAY";
    /** 类型：周末 */
    private static final String TYPE_WEEKEND = "WEEKEND";
    /** 类型：法定节假日 */
    private static final String TYPE_HOLIDAY = "HOLIDAY";
    /** 类型：调休上班（周末标记为工作日） */
    private static final String TYPE_ADJUSTED = "ADJUSTED";

    /** 全部合法日历类型 */
    private static final Set<String> ALL_TYPES = Set.of(TYPE_WORKDAY, TYPE_WEEKEND, TYPE_HOLIDAY, TYPE_ADJUSTED);

    private final CalendarDayMapper calendarDayMapper;

    @Override
    public CalendarMonthVO getMonth(Integer year, Integer month) {
        checkYearMonth(year, month);
        CalendarMonthVO vo = new CalendarMonthVO();
        vo.setYear(year);
        vo.setMonth(month);
        vo.setDays(toDayVOs(getMonthDays(year, month)));
        return vo;
    }

    @Override
    public Map<LocalDate, CalendarDay> getMonthDays(int year, int month) {
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate first = yearMonth.atDay(1);
        LocalDate last = yearMonth.atEndOfMonth();
        // 库中已有日历（可能不完整，如历史月份或超出预生成范围）
        Map<LocalDate, CalendarDay> dbDays = calendarDayMapper.selectList(
                        new LambdaQueryWrapper<CalendarDay>()
                                .ge(CalendarDay::getDay, first)
                                .le(CalendarDay::getDay, last))
                .stream().collect(Collectors.toMap(CalendarDay::getDay, d -> d));
        // 整月每天：缺失日期按周末规则回退推导（仅用于展示/校验，不落库）
        Map<LocalDate, CalendarDay> result = new LinkedHashMap<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            CalendarDay calendarDay = dbDays.get(day);
            if (calendarDay == null) {
                calendarDay = new CalendarDay();
                calendarDay.setDay(day);
                calendarDay.setType(deriveType(day));
            }
            result.put(day, calendarDay);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDay(LocalDate day, CalendarUpdateDTO dto) {
        if (!ALL_TYPES.contains(dto.getType())) {
            throw new BizException(400, "非法日历类型：" + dto.getType());
        }
        CalendarDay existing = calendarDayMapper.selectById(day);
        if (existing == null) {
            CalendarDay calendarDay = new CalendarDay();
            calendarDay.setDay(day);
            calendarDay.setType(dto.getType());
            calendarDay.setName(dto.getName());
            calendarDayMapper.insert(calendarDay);
        } else {
            // name 传 null 表示清空节假日名称
            calendarDayMapper.update(null, new LambdaUpdateWrapper<CalendarDay>()
                    .eq(CalendarDay::getDay, day)
                    .set(CalendarDay::getType, dto.getType())
                    .set(CalendarDay::getName, dto.getName()));
        }
    }

    /**
     * 启动时预生成工作日历：检查 [当月月初, 今天+12个月] 区间，
     * 缺失的日期按周末规则补生成（周六周日 WEEKEND，其余 WORKDAY），已存在的日期跳过（幂等）
     */
    @Override
    public void run(ApplicationArguments args) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.withDayOfMonth(1);
        LocalDate end = today.plusMonths(12);
        Set<LocalDate> existingDays = calendarDayMapper.selectList(
                        new LambdaQueryWrapper<CalendarDay>()
                                .ge(CalendarDay::getDay, start)
                                .le(CalendarDay::getDay, end))
                .stream().map(CalendarDay::getDay).collect(Collectors.toSet());
        int generated = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (existingDays.contains(day)) {
                continue;
            }
            CalendarDay calendarDay = new CalendarDay();
            calendarDay.setDay(day);
            calendarDay.setType(deriveType(day));
            calendarDayMapper.insert(calendarDay);
            generated++;
        }
        if (generated > 0) {
            log.info("工作日历预生成完成：补充生成 {} 天（{} ~ {}）", generated, start, end);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 年月参数校验：year 2000-2100，month 1-12
     */
    private void checkYearMonth(Integer year, Integer month) {
        if (year == null) {
            throw new BizException(400, "year不能为空");
        }
        if (month == null) {
            throw new BizException(400, "month不能为空");
        }
        if (year < 2000 || year > 2100) {
            throw new BizException(400, "年份不合法");
        }
        if (month < 1 || month > 12) {
            throw new BizException(400, "月份只能为1-12");
        }
    }

    /**
     * 周末规则推导默认类型：周六/周日 -> WEEKEND，其余 -> WORKDAY
     */
    private String deriveType(LocalDate day) {
        DayOfWeek dayOfWeek = day.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY ? TYPE_WEEKEND : TYPE_WORKDAY;
    }

    /**
     * 整月日历 -> 前端 VO 列表
     */
    private List<CalendarDayVO> toDayVOs(Map<LocalDate, CalendarDay> monthDays) {
        List<CalendarDayVO> days = new ArrayList<>();
        monthDays.forEach((day, calendarDay) -> {
            CalendarDayVO vo = new CalendarDayVO();
            vo.setDay(day.toString());
            vo.setType(calendarDay.getType());
            vo.setName(calendarDay.getName());
            days.add(vo);
        });
        return days;
    }
}
