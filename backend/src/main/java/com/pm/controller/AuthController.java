package com.pm.controller;

import com.pm.common.Result;
import com.pm.dto.ChangePasswordDTO;
import com.pm.dto.LoginDTO;
import com.pm.dto.LoginVO;
import com.pm.dto.UserVO;
import com.pm.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录（白名单接口，无需 token）
     * 返回 token + 用户信息 + forceChangePwd（1=前端需引导强制修改密码）
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        return Result.success(authService.login(loginDTO));
    }

    /**
     * 修改密码：校验原密码并写入新 BCrypt 哈希，同时清除强制改密标记
     */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO changePasswordDTO,
                                       HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        authService.changePassword(userId, changePasswordDTO);
        return Result.success();
    }

    /**
     * 当前登录用户信息
     */
    @GetMapping("/me")
    public Result<UserVO> me(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(authService.me(userId));
    }
}
