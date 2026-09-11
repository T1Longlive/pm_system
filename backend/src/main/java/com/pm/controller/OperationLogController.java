package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.Result;
import com.pm.service.OperationLogService;
import com.pm.vo.OperationLogItemVO;
import com.pm.vo.UserOptionVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 统一操作日志接口（/api/operation-logs）
 * 仅管理员与项目经理可见：管理员查全部项目，项目经理仅查自己创建的项目（越权 403）
 */
@RestController
@RequestMapping("/api/operation-logs")
@RequiredArgsConstructor
public class OperationLogController {

    private final OperationLogService operationLogService;

    /**
     * 分页查询操作日志：业务类型/动作/操作人/项目/关键词/日期区间筛选
     */
    @GetMapping
    public Result<Page<OperationLogItemVO>> page(@RequestParam(required = false) String bizType,
                                                 @RequestParam(required = false) String action,
                                                 @RequestParam(required = false) Long operatorId,
                                                 @RequestParam(required = false) Long projectId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false)
                                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                 @RequestParam(required = false)
                                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(operationLogService.pageLogs(userId, bizType, action, operatorId,
                projectId, keyword, startDate, endDate, page, size));
    }

    /**
     * 操作人下拉选项（可见范围内日志出现过的操作人去重）
     */
    @GetMapping("/operators")
    public Result<List<UserOptionVO>> operators(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(operationLogService.listOperators(userId));
    }
}
