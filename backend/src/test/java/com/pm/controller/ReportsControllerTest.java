package com.pm.controller;

import com.pm.service.ReportsService;
import com.pm.vo.ReportProjectOverviewVO;
import com.pm.vo.ReportRequirementStatusVO;
import com.pm.vo.ReportWorkloadVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统计报表接口切片测试：三类只读汇总与 months 参数默认/越界回退。
 */
@ExtendWith(MockitoExtension.class)
class ReportsControllerTest {

    @Mock
    private ReportsService reportsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new ReportsController(reportsService));
    }

    @Test
    @DisplayName("需求状态分布：原样返回服务层聚合结果")
    void requirementStatus() throws Exception {
        ReportRequirementStatusVO vo = new ReportRequirementStatusVO();
        vo.setProjectId(1L);
        vo.setStatus("DEV");
        vo.setCount(4L);
        when(reportsService.requirementStatus()).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/reports/requirement-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("DEV"))
                .andExpect(jsonPath("$.data[0].count").value(4));
    }

    @Test
    @DisplayName("人员负载：months 缺省为 6")
    void workloadDefaultMonths() throws Exception {
        ReportWorkloadVO vo = new ReportWorkloadVO();
        vo.setUserId(2L);
        vo.setYear(2026);
        vo.setMonth(11);
        vo.setEffort(new BigDecimal("8"));
        when(reportsService.workload(6)).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/reports/workload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].effort").value(8));

        verify(reportsService).workload(6);
    }

    @Test
    @DisplayName("人员负载：months 越界（0/99）回退为 6")
    void workloadClampsOutOfRange() throws Exception {
        mockMvc.perform(get("/api/reports/workload").param("months", "0"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/workload").param("months", "99"))
                .andExpect(status().isOk());

        verify(reportsService, times(2)).workload(6);
    }

    @Test
    @DisplayName("项目概览：原样返回服务层聚合结果")
    void projectOverview() throws Exception {
        ReportProjectOverviewVO vo = new ReportProjectOverviewVO();
        vo.setProjectId(1L);
        vo.setProjectName("ACE 项目");
        vo.setMemberCount(3L);
        vo.setOpenBugCount(2L);
        when(reportsService.projectOverview()).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/reports/project-overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].projectName").value("ACE 项目"))
                .andExpect(jsonPath("$.data[0].openBugCount").value(2));
    }
}
