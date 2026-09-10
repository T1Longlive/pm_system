package com.pm.controller;

import com.pm.common.Result;
import com.pm.dto.ScheduleMonthCreateDTO;
import com.pm.dto.ScheduleRequirementDTO;
import com.pm.dto.ScheduleRowSaveDTO;
import com.pm.service.ScheduleService;
import com.pm.vo.CopyRowResultVO;
import com.pm.vo.ScheduleIncompleteVO;
import com.pm.vo.ScheduleMonthVO;
import com.pm.vo.ScheduleRowSaveResultVO;
import com.pm.vo.ScheduleViewVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 排期管理接口（/api/schedule）
 * 月表列表/排期视图对任何登录用户可见（全局跨项目排期总览）；
 * 写操作在 Service 层按角色校验（月表=管理员或PM，排期/保存/复制到下月=项目PM）
 */
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /**
     * 月表列表（任何登录用户，按年月升序）
     */
    @GetMapping("/months")
    public Result<List<ScheduleMonthVO>> months() {
        return Result.success(scheduleService.listMonths());
    }

    /**
     * 创建月度排期表（管理员或任意项目 PM）：已存在则 400「该月排期表已存在」
     */
    @PostMapping("/months")
    public Result<ScheduleMonthVO> createMonth(@Valid @RequestBody ScheduleMonthCreateDTO dto,
                                               HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(scheduleService.createMonth(userId, dto));
    }

    /**
     * 月表排期视图（任何登录用户，全局可见）：月份 + 整月日历 + 按需求分组的排期矩阵行（VOID 行也返回，前端灰显）
     */
    @GetMapping("/months/{monthId}/view")
    public Result<ScheduleViewVO> view(@PathVariable Long monthId) {
        return Result.success(scheduleService.getMonthView(monthId));
    }

    /**
     * 把需求加入月表（仅该需求项目的 PM）：按需求参与人生成排期行，返回生成行数
     */
    @PostMapping("/months/{monthId}/requirements")
    public Result<Integer> addRequirement(@PathVariable Long monthId,
                                          @Valid @RequestBody ScheduleRequirementDTO dto,
                                          HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(scheduleService.addRequirement(userId, monthId, dto));
    }

    /**
     * 把需求从月表移除（仅该需求项目的 PM）：删除该需求在该月的全部排期行及格子（其他月份不受影响），
     * 返回删除的行数
     */
    @DeleteMapping("/months/{monthId}/requirements/{requirementId}")
    public Result<Integer> removeRequirement(@PathVariable Long monthId,
                                             @PathVariable Long requirementId,
                                             HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(scheduleService.removeRequirement(userId, monthId, requirementId));
    }

    /**
     * 保存排期行（仅项目 PM）：全量替换格子与计划工期，执行全部校验与跨月顺延检查，
     * 返回新版本 updatedAt 与顺延信息（overflow 非空时前端提示）
     */
    @PutMapping("/rows/{rowId}")
    public Result<ScheduleRowSaveResultVO> saveRow(@PathVariable Long rowId,
                                                   @Valid @RequestBody ScheduleRowSaveDTO dto,
                                                   HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(scheduleService.saveRow(userId, rowId, dto));
    }

    /**
     * 整个需求添加到下月（仅项目 PM）：下月表不存在自动创建；
     * 该需求全部排期组逐组插入下月新行（格子空白、工期跨月共用）；幂等（已存在的组跳过）；
     * 返回下月表 ID、是否有新插入（duplicated=false 时前端提示已存在）与插入行数
     */
    @PostMapping("/rows/{rowId}/to-next-month")
    public Result<CopyRowResultVO> copyRequirementToNextMonth(@PathVariable Long rowId,
                                                              HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(scheduleService.copyRequirementToNextMonth(userId, rowId));
    }

    /**
     * 排期未完成的需求列表（退出排期界面前拦截检查用）：仅返回当前用户为项目 PM 的可修复需求，
     * 非空时前端强制弹窗阻止跳转；普通成员无权修复，返回空不拦截
     */
    @GetMapping("/incomplete")
    public Result<List<ScheduleIncompleteVO>> incompleteRequirements(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(scheduleService.listIncompleteRequirements(userId));
    }
}
