package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.Result;
import com.pm.dto.RequirementCancelDTO;
import com.pm.dto.RequirementCreateDTO;
import com.pm.dto.RequirementUpdateDTO;
import com.pm.service.RequirementService;
import com.pm.vo.OperationLogVO;
import com.pm.vo.RequirementDetailVO;
import com.pm.vo.RequirementListVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 需求管理接口（/api/requirements）
 * 列表/详情对任何登录用户可见（全局汇总视图）；写操作在 Service 层按角色校验
 */
@RestController
@RequestMapping("/api/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private final RequirementService requirementService;

    /**
     * 需求分页列表：projectId 空=全部项目（全局汇总视图）；
     * status/priority/plannerId 精确过滤；keyword 模糊匹配标题或编号；
     * excludeTerminal=true 排除终态（ARCHIVED/CANCELLED，需求池场景）
     */
    @GetMapping
    public Result<Page<RequirementListVO>> page(@RequestParam(required = false) Long projectId,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) Integer priority,
                                                @RequestParam(required = false) Long plannerId,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "false") boolean excludeTerminal,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "10") long size,
                                                HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(requirementService.pageRequirements(userId, projectId, status, priority,
                plannerId, keyword, excludeTerminal, page, size));
    }

    /**
     * 创建需求（该项目 PM 或 PLANNER 工种成员），返回新需求 id
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody RequirementCreateDTO dto,
                               HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(requirementService.createRequirement(userId, dto));
    }

    /**
     * 需求详情（任何登录用户可看）
     */
    @GetMapping("/{id}")
    public Result<RequirementDetailVO> detail(@PathVariable Long id,
                                              HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(requirementService.getRequirementDetail(userId, id));
    }

    /**
     * 需求操作日志时间线（id 倒序）
     */
    @GetMapping("/{id}/logs")
    public Result<List<OperationLogVO>> logs(@PathVariable Long id) {
        return Result.success(requirementService.listLogs(id));
    }

    /**
     * 编辑需求（项目 PM 或需求创建人）：乐观锁 + 终态校验 + 参与人 diff + 附件全量替换 + 变更摘要留痕
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody RequirementUpdateDTO dto,
                               HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        requirementService.updateRequirement(userId, id, dto);
        return Result.success();
    }

    /**
     * 取消需求（仅项目 PM，任意非终态可取消，终态不可逆）
     */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id,
                               @Valid @RequestBody RequirementCancelDTO dto,
                               HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        requirementService.cancelRequirement(userId, id, dto);
        return Result.success();
    }

    /**
     * 归档需求（仅项目 PM，仅待归档状态可归档）
     */
    @PostMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable Long id,
                                HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        requirementService.archiveRequirement(userId, id);
        return Result.success();
    }
}
