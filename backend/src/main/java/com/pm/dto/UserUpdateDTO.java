package com.pm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑人员入参（PUT /api/users/{id}）
 */
@Data
public class UserUpdateDTO {

    @NotBlank(message = "姓名不能为空")
    @Size(max = 30, message = "姓名不能超过30字")
    private String realName;
}
