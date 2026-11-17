package com.pm.controller;

import com.pm.service.DashboardService;
import com.pm.vo.DashboardRequirementVO;
import com.pm.vo.DashboardSummaryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作台接口切片测试：当前用户透传与两类个人视图数据。
 */
@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private DashboardService dashboardService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new DashboardController(dashboardService));
    }

    @Test
    @DisplayName("汇总：当前用户透传并返回四张卡片数据")
    void summary() throws Exception {
        DashboardSummaryVO vo = new DashboardSummaryVO();
        vo.setUnreadNotifications(2L);
        vo.setMyRequirementCount(5L);
        vo.setMonthEffort(new BigDecimal("12.5"));
        vo.setOpenBugCount(1L);
        when(dashboardService.summary(9L)).thenReturn(vo);

        mockMvc.perform(get("/api/dashboard/summary").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.unreadNotifications").value(2))
                .andExpect(jsonPath("$.data.myRequirementCount").value(5))
                .andExpect(jsonPath("$.data.openBugCount").value(1));

        verify(dashboardService).summary(9L);
    }

    @Test
    @DisplayName("我参与的需求：当前用户透传并返回轻量列表")
    void myRequirements() throws Exception {
        DashboardRequirementVO vo = new DashboardRequirementVO();
        vo.setId(3L);
        vo.setReqNo("REQ-ACE-0001");
        vo.setStatus("DEV");
        when(dashboardService.myRequirements(9L)).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/dashboard/my-requirements").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reqNo").value("REQ-ACE-0001"))
                .andExpect(jsonPath("$.data[0].status").value("DEV"));
    }
}
