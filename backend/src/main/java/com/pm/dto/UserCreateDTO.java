package com.pm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增人员入参（POST /api/users）
 */
@Data
public class UserCreateDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 30, message = "用户名长度需为3-30位")
    @Pattern(regexp = "^[a-zA-Z0-9]+$", message = "用户名仅支持字母和数字")
    private String username;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 30, message = "姓名不能超过30字")
    private String realName;

    /** 初始密码：可空，空则使用默认密码 Pm@123456 */
    private String password;
}
