package com.pm.controller;

import com.pm.common.BizException;
import com.pm.common.Result;
import com.pm.dto.CalendarUpdateDTO;
import com.pm.security.CurrentUser;
import com.pm.service.CalendarService;
import com.pm.vo.CalendarMonthVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 工作日历接口（/api/calendar）
 * 查询对任何登录用户开放（排期矩阵灰显列头用）；维护仅管理员（CurrentUser 校验，非管理员返回 403）
 */
@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendarService;

    /**
     * 查询整月日历（任何登录用户）：返回整月每天 {day, type, name}
     */
    @GetMapping
    public Result<CalendarMonthVO> month(@RequestParam(required = false) Integer year,
                                         @RequestParam(required = false) Integer month) {
        return Result.success(calendarService.getMonth(year, month));
    }

    /**
     * 维护某天的日历类型（仅管理员）：day 路径参数格式 2026-09-01；
     * type=WORKDAY/WEEKEND/HOLIDAY/ADJUSTED，name 为节假日名称（可空）
     */
    @PutMapping("/{day}")
    public Result<Void> update(@PathVariable String day,
                               @Valid @RequestBody CalendarUpdateDTO dto,
                               HttpServletRequest request) {
        CurrentUser.getAdminUserId(request);
        calendarService.updateDay(parseDay(day), dto);
        return Result.success();
    }

    /**
     * 解析路径日期参数（2026-09-01），格式非法返回 400
     */
    private LocalDate parseDay(String day) {
        try {
            return LocalDate.parse(day);
        } catch (DateTimeParseException e) {
            throw new BizException(400, "日期格式错误：" + day + "（应为2026-09-01）");
        }
    }
}
