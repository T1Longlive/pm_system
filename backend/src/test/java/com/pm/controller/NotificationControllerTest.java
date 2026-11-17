package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.service.NotificationService;
import com.pm.vo.NotificationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站内通知接口切片测试：当前用户透传、分页默认值、未读数结构。
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationService notificationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new NotificationController(notificationService));
    }

    @Test
    @DisplayName("分页：默认 page=1/size=10，当前用户透传")
    void pageDefaults() throws Exception {
        Page<NotificationVO> page = new Page<>(1, 10);
        NotificationVO vo = new NotificationVO();
        vo.setId(1L);
        vo.setTitle("需求已指派");
        vo.setIsRead(0);
        page.setRecords(List.of(vo));
        when(notificationService.pageMyNotifications(9L, 1, 10)).thenReturn(page);

        mockMvc.perform(get("/api/notifications").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].title").value("需求已指派"));

        verify(notificationService).pageMyNotifications(9L, 1, 10);
    }

    @Test
    @DisplayName("未读数：包装为 {count}")
    void unreadCount() throws Exception {
        when(notificationService.unreadCount(9L)).thenReturn(3L);

        mockMvc.perform(get("/api/notifications/unread-count").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    @DisplayName("全部已读：当前用户透传")
    void readAll() throws Exception {
        mockMvc.perform(post("/api/notifications/read-all").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationService).markAllRead(9L);
    }
}
