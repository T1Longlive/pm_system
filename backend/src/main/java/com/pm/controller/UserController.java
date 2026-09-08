package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.Result;
import com.pm.dto.ResetPasswordDTO;
import com.pm.dto.UserCreateDTO;
import com.pm.dto.UserStatusDTO;
import com.pm.dto.UserUpdateDTO;
import com.pm.security.CurrentUser;
import com.pm.service.UserService;
import com.pm.vo.UserOptionVO;
import com.pm.vo.UserVO;
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
 * 人员管理接口（/api/users）
 * 管理类接口仅 is_admin=1 可调用（CurrentUser 校验，非管理员返回 403）；
 * /options 为全登录用户可用的下拉选项接口
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 人员分页列表（仅管理员）：keyword 模糊匹配姓名/用户名，status 过滤账号状态
     */
    @GetMapping
    public Result<Page<UserVO>> page(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) Integer status,
                                     @RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "10") long size,
                                     HttpServletRequest request) {
        CurrentUser.getAdminUserId(request);
        return Result.success(userService.pageUsers(keyword, status, page, size));
    }

    /**
     * 新增人员（仅管理员）：初始密码可空（默认 Pm@123456），创建后首次登录强制改密
     */
    @PostMapping
    public Result<Void> create(@Valid @RequestBody UserCreateDTO dto,
                               HttpServletRequest request) {
        CurrentUser.getAdminUserId(request);
        userService.createUser(dto);
        return Result.success();
    }

    /**
     * 编辑人员姓名（仅管理员）
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody UserUpdateDTO dto,
                               HttpServletRequest request) {
        CurrentUser.getAdminUserId(request);
        userService.updateUser(id, dto);
        return Result.success();
    }

    /**
     * 重置密码（仅管理员）：密码可空（重置为 Pm@123456），重置后再次强制改密
     */
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @RequestBody ResetPasswordDTO dto,
                                      HttpServletRequest request) {
        CurrentUser.getAdminUserId(request);
        userService.resetPassword(id, dto);
        return Result.success();
    }

    /**
     * 停用/启用人员（仅管理员）：不允许停用自己
     */
    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody UserStatusDTO dto,
                                     HttpServletRequest request) {
        Long adminUserId = CurrentUser.getAdminUserId(request);
        userService.updateStatus(adminUserId, id, dto);
        return Result.success();
    }

    /**
     * 用户下拉选项：全部正常状态用户，任何登录用户可调（供项目添加成员）
     */
    @GetMapping("/options")
    public Result<List<UserOptionVO>> options() {
        return Result.success(userService.listOptions());
    }
}
