package com.pm.controller;

import com.pm.service.ScheduleService;
import com.pm.vo.CopyRowResultVO;
import com.pm.vo.ScheduleIncompleteVO;
import com.pm.vo.ScheduleMonthVO;
import com.pm.vo.ScheduleRowSaveResultVO;
import com.pm.vo.ScheduleViewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 排期接口切片测试：保存参数校验、成功返回新版本、退出拦截与移除需求端点。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleControllerTest {

    @Mock
    private ScheduleService scheduleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new ScheduleController(scheduleService));
    }

    @Test
    @DisplayName("保存排期：缺少 cells/updatedAt → 400")
    void saveRowValidation() throws Exception {
        mockMvc.perform(put("/api/schedule/rows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("保存排期：成功返回新 updatedAt")
    void saveRowSuccess() throws Exception {
        ScheduleRowSaveResultVO result = new ScheduleRowSaveResultVO();
        result.setUpdatedAt(456L);
        when(scheduleService.saveRow(eq(9L), eq(1L), any())).thenReturn(result);

        mockMvc.perform(put("/api/schedule/rows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plannedEffort\":1,\"cells\":{\"2026-12-01\":0.5},\"updatedAt\":123}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.updatedAt").value(456));

        verify(scheduleService).saveRow(eq(9L), eq(1L), any());
    }

    @Test
    @DisplayName("退出拦截：返回当前 PM 的未完成需求列表")
    void incompleteRequirements() throws Exception {
        ScheduleIncompleteVO vo = new ScheduleIncompleteVO();
        vo.setRequirementId(3L);
        vo.setReqNo("REQ-T-0001");
        vo.setTitle("未完成");
        when(scheduleService.listIncompleteRequirements(9L)).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/schedule/incomplete").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].requirementId").value(3))
                .andExpect(jsonPath("$.data[0].reqNo").value("REQ-T-0001"));
    }

    @Test
    @DisplayName("加入月表：requirementId 缺失 → 400")
    void addRequirementValidation() throws Exception {
        mockMvc.perform(post("/api/schedule/months/1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("移除需求：路径变量与当前用户透传")
    void removeRequirement() throws Exception {
        mockMvc.perform(delete("/api/schedule/months/1/requirements/2")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(scheduleService).removeRequirement(9L, 1L, 2L);
    }

    @Test
    @DisplayName("月表列表：无需登录参数，按服务层返回")
    void months() throws Exception {
        ScheduleMonthVO month = new ScheduleMonthVO();
        month.setId(1L);
        month.setYear(2026);
        month.setMonth(12);
        when(scheduleService.listMonths()).thenReturn(List.of(month));

        mockMvc.perform(get("/api/schedule/months"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].year").value(2026))
                .andExpect(jsonPath("$.data[0].month").value(12));
    }

    @Test
    @DisplayName("创建月表：年份/月份缺失 → 400")
    void createMonthValidation() throws Exception {
        mockMvc.perform(post("/api/schedule/months")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("创建月表：成功返回新表，当前用户透传")
    void createMonthSuccess() throws Exception {
        ScheduleMonthVO month = new ScheduleMonthVO();
        month.setId(3L);
        when(scheduleService.createMonth(eq(9L), any())).thenReturn(month);

        mockMvc.perform(post("/api/schedule/months")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":12}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(3));

        verify(scheduleService).createMonth(eq(9L), any());
    }

    @Test
    @DisplayName("排期视图：路径 monthId 透传")
    void view() throws Exception {
        ScheduleViewVO view = new ScheduleViewVO();
        ScheduleMonthVO month = new ScheduleMonthVO();
        month.setId(2L);
        view.setMonth(month);
        when(scheduleService.getMonthView(2L)).thenReturn(view);

        mockMvc.perform(get("/api/schedule/months/2/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.month.id").value(2));
    }

    @Test
    @DisplayName("加入月表：成功返回生成行数，当前用户与路径透传")
    void addRequirementSuccess() throws Exception {
        when(scheduleService.addRequirement(eq(9L), eq(1L), any())).thenReturn(2);

        mockMvc.perform(post("/api/schedule/months/1/requirements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirementId\":5}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));

        verify(scheduleService).addRequirement(eq(9L), eq(1L), any());
    }

    @Test
    @DisplayName("整个需求复制到下月：返回目标月表与插入行数")
    void copyRequirementToNextMonth() throws Exception {
        CopyRowResultVO result = new CopyRowResultVO();
        result.setMonthId(9L);
        result.setDuplicated(false);
        result.setInsertedCount(2);
        when(scheduleService.copyRequirementToNextMonth(9L, 1L)).thenReturn(result);

        mockMvc.perform(post("/api/schedule/rows/1/to-next-month")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monthId").value(9))
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andExpect(jsonPath("$.data.insertedCount").value(2));

        verify(scheduleService).copyRequirementToNextMonth(9L, 1L);
    }
}
