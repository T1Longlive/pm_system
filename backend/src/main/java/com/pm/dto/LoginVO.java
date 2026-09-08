package com.pm.dto;

import lombok.Data;

/**
 * 登录响应：token + 用户信息 + 强制改密标记
 */
@Data
public class LoginVO {

    /** JWT token，后续请求放入 Authorization: Bearer <token> */
    private String token;

    private Long userId;

    private String username;

    private String realName;

    /** 0普通成员 1管理员 */
    private Integer isAdmin;

    /** 1需强制修改密码（前端据此引导跳转改密页） */
    private Integer forceChangePwd;
}
