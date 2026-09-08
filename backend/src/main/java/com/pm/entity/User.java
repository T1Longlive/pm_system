package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（对应表 user）
 */
@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名 */
    private String username;

    /** BCrypt 加密后的密码 */
    private String password;

    /** 姓名 */
    private String realName;

    /** 0普通成员 1管理员 */
    private Integer isAdmin;

    /** 1正常 0停用 */
    private Integer status;

    /** 1需强制改密 */
    private Integer forceChangePwd;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
