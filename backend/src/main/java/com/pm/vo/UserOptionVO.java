package com.pm.vo;

import lombok.Data;

/**
 * 用户下拉选项（GET /api/users/options），供项目添加成员时选择
 */
@Data
public class UserOptionVO {

    private Long id;

    private String realName;

    private String username;
}
