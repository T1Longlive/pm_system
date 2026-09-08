package com.pm.service;

import com.pm.dto.ChangePasswordDTO;
import com.pm.dto.LoginDTO;
import com.pm.dto.LoginVO;
import com.pm.dto.UserVO;

/**
 * 认证服务：登录 / 修改密码 / 当前用户信息
 */
public interface AuthService {

    /**
     * 登录：BCrypt 校验密码，签发 JWT，返回用户信息与强制改密标记
     */
    LoginVO login(LoginDTO loginDTO);

    /**
     * 修改密码：校验原密码，写入新 BCrypt 哈希并清除强制改密标记
     */
    void changePassword(Long userId, ChangePasswordDTO changePasswordDTO);

    /**
     * 查询当前登录用户信息
     */
    UserVO me(Long userId);
}
