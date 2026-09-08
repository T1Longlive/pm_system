package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.entity.Notification;
import com.pm.mapper.NotificationMapper;
import com.pm.service.NotificationService;
import com.pm.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内通知服务实现
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** notification.content 列宽上限 */
    private static final int CONTENT_MAX = 300;

    private final NotificationMapper notificationMapper;

    @Override
    public Page<NotificationVO> pageMyNotifications(Long userId, long page, long size) {
        Page<Notification> notificationPage = notificationMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getReceiverId, userId)
                        .orderByDesc(Notification::getId));
        Page<NotificationVO> voPage =
                new Page<>(notificationPage.getCurrent(), notificationPage.getSize(), notificationPage.getTotal());
        voPage.setRecords(notificationPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    @Override
    public Long unreadCount(Long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getReceiverId, userId)
                .eq(Notification::getIsRead, 0));
    }

    @Override
    public void markAllRead(Long userId) {
        Notification update = new Notification();
        update.setIsRead(1);
        // 仅更新当前用户未读记录
        notificationMapper.update(update, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getReceiverId, userId)
                .eq(Notification::getIsRead, 0));
    }

    @Override
    public void send(Long receiverId, String type, String title, String content, String bizType, Long bizId) {
        Notification notification = new Notification();
        notification.setReceiverId(receiverId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(truncate(content));
        notification.setBizType(bizType);
        notification.setBizId(bizId);
        notification.setIsRead(0);
        notificationMapper.insert(notification);
    }

    /**
     * 实体转 VO
     */
    private NotificationVO toVO(Notification notification) {
        NotificationVO vo = new NotificationVO();
        vo.setId(notification.getId());
        vo.setType(notification.getType());
        vo.setTitle(notification.getTitle());
        vo.setContent(notification.getContent());
        vo.setBizType(notification.getBizType());
        vo.setBizId(notification.getBizId());
        vo.setIsRead(notification.getIsRead());
        vo.setCreatedAt(notification.getCreatedAt());
        return vo;
    }

    /**
     * content 超过 300 字自动截断（列宽 varchar(300)）
     */
    private String truncate(String content) {
        if (content != null && content.length() > CONTENT_MAX) {
            return content.substring(0, CONTENT_MAX - 1) + "…";
        }
        return content;
    }
}
