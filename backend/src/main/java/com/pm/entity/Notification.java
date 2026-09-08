package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知（对应表 notification）
 * 保留90天，定时清理
 */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人 */
    private Long receiverId;

    /** 通知类型：BUG_ASSIGNED/BUG_HANDLED/BUG_ACCEPTED/REQ_PARTICIPANT_ADDED/REQ_PARTICIPANT_REMOVED/REQ_STATUS_CHANGED/REQ_ARCHIVED等 */
    private String type;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 跳转目标类型 */
    private String bizType;

    /** 跳转目标ID */
    private Long bizId;

    /** 0未读 1已读 */
    private Integer isRead;

    private LocalDateTime createdAt;
}
