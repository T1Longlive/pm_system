package com.pm.dto;

import lombok.Data;

/**
 * 当前登录用户信息（GET /api/auth/me 响应）
 */
@Data
public class UserVO {

    private Long userId;

    private String username;

    private String realName;

    /** 0普通成员 1管理员 */
    private Integer isAdmin;

    /** 1需强制修改密码 */
    private Integer forceChangePwd;
}
