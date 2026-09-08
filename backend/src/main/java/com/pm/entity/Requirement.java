package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需求（对应表 requirement）
 */
@Data
@TableName("requirement")
public class Requirement {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 需求编号：REQ-{项目缩写}-{4位流水} */
    private String reqNo;

    private Long projectId;

    /** 需求名称 */
    private String title;

    /** 富文本HTML */
    private String description;

    /** 优先级：1高 2中 3低 */
    private Integer priority;

    /** 状态：PENDING待排期/READY待开始/DEV开发中/TEST测试中/ARCHIVING待归档/ARCHIVED已归档/CANCELLED已取消 */
    private String status;

    /** 策划（默认创建人，可改） */
    private Long plannerId;

    /** 创建人 */
    private Long createdBy;

    /** 取消人 */
    private Long cancelledBy;

    /** 取消时间 */
    private LocalDateTime cancelledAt;

    /** 取消原因 */
    private String cancelReason;

    /** 归档时间 */
    private LocalDateTime archivedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
