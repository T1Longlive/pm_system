package com.pm.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知项（GET /api/notifications 记录行）
 */
@Data
public class NotificationVO {

    private Long id;

    /** 通知类型：见 com.pm.common.NotifyTypes */
    private String type;

    private String title;

    private String content;

    /** 跳转目标类型：REQUIREMENT/BUG */
    private String bizType;

    /** 跳转目标ID */
    private Long bizId;

    /** 0未读 1已读 */
    private Integer isRead;

    private LocalDateTime createdAt;
}
