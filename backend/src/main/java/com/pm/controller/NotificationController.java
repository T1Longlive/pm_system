package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.Result;
import com.pm.service.NotificationService;
import com.pm.vo.NotificationVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 站内通知接口（/api/notifications）：顶栏铃铛入口
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 我的通知分页（id 倒序）
     */
    @GetMapping
    public Result<Page<NotificationVO>> page(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size,
                                             HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(notificationService.pageMyNotifications(userId, page, size));
    }

    /**
     * 未读通知数（铃铛角标）：{count}
     */
    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(Map.of("count", notificationService.unreadCount(userId)));
    }

    /**
     * 全部标记已读
     */
    @PostMapping("/read-all")
    public Result<Void> readAll(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        notificationService.markAllRead(userId);
        return Result.success();
    }
}
