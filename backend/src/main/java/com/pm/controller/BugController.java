package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.Result;
import com.pm.dto.BugCreateDTO;
import com.pm.dto.BugImageAddDTO;
import com.pm.dto.BugUpdateDTO;
import com.pm.service.BugService;
import com.pm.vo.BugListVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Bug 管理接口（/api/bugs）——Excel 化编辑模型
 * 所有登录用户可编辑未归档行；归档/重新打开限项目经理或管理员；删除所有人可用
 */
@RestController
@RequestMapping("/api/bugs")
@RequiredArgsConstructor
public class BugController {

    private final BugService bugService;

    /**
     * Bug 分页列表（按项目查询）：archived=0 总表 / 1 归档表；
     * keyword 模糊匹配功能模块或编号；size 上限 1000（一页全量 Excel 模式）
     */
    @GetMapping
    public Result<Page<BugListVO>> page(@RequestParam(required = false) Long projectId,
                                        @RequestParam(defaultValue = "0") Integer archived,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "1000") long size,
                                        HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(bugService.pageBugs(userId, projectId, archived, keyword, page, size));
    }

    /**
     * 创建 Bug（任何登录用户）：编号自动生成、测试人员=当前用户，返回新行完整 VO
     */
    @PostMapping
    public Result<BugListVO> create(@Valid @RequestBody BugCreateDTO dto,
                                    HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(bugService.createBug(userId, dto));
    }

    /**
     * 编辑 Bug（任何登录用户，未归档行）：单元格逐格部分更新，清空哨兵 null=不改/""或0=清空
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody BugUpdateDTO dto,
                               HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        bugService.updateBug(userId, id, dto);
        return Result.success();
    }

    /**
     * 删除 Bug（任何登录用户，归档行也可删）：物理删除行并连带删除截图附件记录
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        bugService.deleteBug(userId, id);
        return Result.success();
    }

    /**
     * 归档 Bug（项目经理或管理员，无状态限制）
     */
    @PostMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable Long id,
                                HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        bugService.archiveBug(userId, id);
        return Result.success();
    }

    /**
     * 重新打开 Bug（项目经理或管理员）：回总表并置空处理进度与验收进度
     */
    @PostMapping("/{id}/reopen")
    public Result<Void> reopen(@PathVariable Long id,
                               HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        bugService.reopenBug(userId, id);
        return Result.success();
    }

    /**
     * 批量追加问题截图（任何登录用户，未归档行），返回新附件 id 列表
     */
    @PostMapping("/{id}/images")
    public Result<List<Long>> addImages(@PathVariable Long id,
                                        @Valid @RequestBody BugImageAddDTO dto,
                                        HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(bugService.addImages(userId, id, dto));
    }

    /**
     * 删除单张问题截图（任何登录用户，未归档行；校验附件归属）
     */
    @DeleteMapping("/{id}/images/{attachmentId}")
    public Result<Void> deleteImage(@PathVariable Long id,
                                    @PathVariable Long attachmentId,
                                    HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        bugService.deleteImage(userId, id, attachmentId);
        return Result.success();
    }
}
