package com.pm.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.entity.Notification;
import com.pm.mapper.NotificationMapper;
import com.pm.vo.NotificationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内通知的写入约束：内容截断、字段落库、已读状态。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationMapper notificationMapper;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(notificationMapper);
    }

    @Test
    @DisplayName("内容不超过 300 字时原样落库，默认未读")
    void shortContentIsKept() {
        service.send(7L, "REQ_STATUS_CHANGED", "标题", "短内容", "REQUIREMENT", 1L);

        Notification saved = captureSaved();
        assertThat(saved.getContent()).isEqualTo("短内容");
        assertThat(saved.getReceiverId()).isEqualTo(7L);
        assertThat(saved.getType()).isEqualTo("REQ_STATUS_CHANGED");
        assertThat(saved.getBizType()).isEqualTo("REQUIREMENT");
        assertThat(saved.getBizId()).isEqualTo(1L);
        assertThat(saved.getIsRead()).isZero();
    }

    @Test
    @DisplayName("内容超过 300 字时截断为 299 字 + 省略号")
    void longContentIsTruncated() {
        String longContent = "a".repeat(500);

        service.send(7L, "TYPE", "标题", longContent, "REQUIREMENT", 1L);

        Notification saved = captureSaved();
        assertThat(saved.getContent()).hasSize(300);
        assertThat(saved.getContent()).endsWith("…");
        assertThat(saved.getContent().substring(0, 299)).isEqualTo("a".repeat(299));
    }

    @Test
    @DisplayName("边界：正好 300 字不截断")
    void exactlyMaxContentIsKept() {
        String content = "b".repeat(300);

        service.send(7L, "TYPE", "标题", content, "REQUIREMENT", 1L);

        assertThat(captureSaved().getContent()).isEqualTo(content);
    }

    @Test
    @DisplayName("null 内容保持 null")
    void nullContentStaysNull() {
        service.send(7L, "TYPE", "标题", null, "REQUIREMENT", 1L);

        assertThat(captureSaved().getContent()).isNull();
    }

    @Test
    @DisplayName("我的通知分页：实体字段完整映射为 VO")
    void pageMyNotificationsMapsVo() {
        Notification notification = new Notification();
        notification.setId(9L);
        notification.setReceiverId(7L);
        notification.setType("REQ_STATUS_CHANGED");
        notification.setTitle("标题");
        notification.setContent("内容");
        notification.setBizType("REQUIREMENT");
        notification.setBizId(1L);
        notification.setIsRead(1);
        notification.setCreatedAt(LocalDateTime.of(2026, 11, 17, 10, 0));
        Page<Notification> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(notification));
        when(notificationMapper.selectPage(any(), any())).thenReturn(page);

        Page<NotificationVO> result = service.pageMyNotifications(7L, 1, 10);

        assertThat(result.getTotal()).isEqualTo(1);
        NotificationVO vo = result.getRecords().get(0);
        assertThat(vo.getId()).isEqualTo(9L);
        assertThat(vo.getType()).isEqualTo("REQ_STATUS_CHANGED");
        assertThat(vo.getTitle()).isEqualTo("标题");
        assertThat(vo.getContent()).isEqualTo("内容");
        assertThat(vo.getBizType()).isEqualTo("REQUIREMENT");
        assertThat(vo.getBizId()).isEqualTo(1L);
        assertThat(vo.getIsRead()).isEqualTo(1);
        assertThat(vo.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 17, 10, 0));
    }

    @Test
    @DisplayName("未读数与全部已读委托给 Mapper")
    void unreadCountAndMarkAllRead() {
        when(notificationMapper.selectCount(any())).thenReturn(3L);

        assertThat(service.unreadCount(7L)).isEqualTo(3L);

        service.markAllRead(7L);
        verify(notificationMapper).update(any(Notification.class), any());
    }

    private Notification captureSaved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        return captor.getValue();
    }
}
