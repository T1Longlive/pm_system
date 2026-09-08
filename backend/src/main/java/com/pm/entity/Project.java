package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目（对应表 project）
 */
@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 项目缩写，编号前缀 */
    private String code;

    /** 项目名称 */
    private String name;

    /** 项目描述 */
    private String description;

    /** 创建者=项目经理 */
    private Long creatorId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
