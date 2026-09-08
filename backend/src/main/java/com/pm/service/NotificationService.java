package com.pm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.vo.NotificationVO;

/**
 * 站内通知服务：铃铛列表 / 未读数 / 全部已读，以及供业务模块调用的通知写入
 */
public interface NotificationService {

    /**
     * 我的通知分页（id 倒序，最新在前）
     */
    Page<NotificationVO> pageMyNotifications(Long userId, long page, long size);

    /**
     * 我的未读通知数（铃铛角标）
     */
    Long unreadCount(Long userId);

    /**
     * 我的全部通知标记已读
     */
    void markAllRead(Long userId);

    /**
     * 写入一条站内通知（同步落库，由业务模块在自己的事务内直接调用，量小无需异步）。
     * content 超过列宽 300 字时自动截断
     */
    void send(Long receiverId, String type, String title, String content, String bizType, Long bizId);
}
