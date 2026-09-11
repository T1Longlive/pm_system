package com.pm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.vo.OperationLogItemVO;
import com.pm.vo.UserOptionVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 统一操作日志查询服务
 * 权限：管理员可查全部项目；项目经理仅可查自己创建（creator_id=自己）的项目
 */
public interface OperationLogService {

    /**
     * 分页查询操作日志（bizType/action/operatorId/projectId/keyword/日期区间筛选，keyword 模糊匹配备注）
     */
    Page<OperationLogItemVO> pageLogs(Long userId, String bizType, String action, Long operatorId,
                                      Long projectId, String keyword, LocalDate startDate, LocalDate endDate,
                                      long page, long size);

    /**
     * 操作人下拉（当前用户可见范围内日志出现过的操作人去重）
     */
    List<UserOptionVO> listOperators(Long userId);
}
