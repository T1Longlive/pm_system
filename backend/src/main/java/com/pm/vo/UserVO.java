package com.pm.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人员管理列表项（GET /api/users 记录行，密码等敏感字段绝不出参）
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String realName;

    /** 0普通成员 1管理员 */
    private Integer isAdmin;

    /** 1正常 0停用 */
    private Integer status;

    private LocalDateTime createdAt;
}
