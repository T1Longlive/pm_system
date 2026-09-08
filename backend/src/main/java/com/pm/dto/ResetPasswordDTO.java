package com.pm.dto;

import lombok.Data;

/**
 * 重置密码入参（POST /api/users/{id}/reset-password）
 */
@Data
public class ResetPasswordDTO {

    /** 新密码：可空，空则重置为默认密码 Pm@123456 */
    private String password;
}
