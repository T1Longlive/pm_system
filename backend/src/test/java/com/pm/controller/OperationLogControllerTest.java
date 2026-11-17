package com.pm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.service.OperationLogService;
import com.pm.vo.OperationLogItemVO;
import com.pm.vo.UserOptionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 操作日志接口切片测试：筛选条件与日期参数绑定、分页默认值、操作人下拉。
 */
@ExtendWith(MockitoExtension.class)
class OperationLogControllerTest {

    @Mock
    private OperationLogService operationLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new OperationLogController(operationLogService));
    }

    @Test
    @DisplayName("分页：筛选条件全空，分页默认 1/20")
    void pageDefaults() throws Exception {
        Page<OperationLogItemVO> page = new Page<>(1, 20);
        OperationLogItemVO vo = new OperationLogItemVO();
        vo.setId(1L);
        vo.setBizType("REQUIREMENT");
        vo.setAction("CREATE");
        page.setRecords(List.of(vo));
        when(operationLogService.pageLogs(9L, null, null, null, null, null, null, null, 1, 20))
                .thenReturn(page);

        mockMvc.perform(get("/api/operation-logs").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].action").value("CREATE"));

        verify(operationLogService).pageLogs(9L, null, null, null, null, null, null, null, 1, 20);
    }

    @Test
    @DisplayName("分页：日期区间按 ISO 日期绑定为 LocalDate，其余筛选原样透传")
    void pageBindsDateRange() throws Exception {
        mockMvc.perform(get("/api/operation-logs")
                        .param("bizType", "BUG")
                        .param("action", "UPDATE")
                        .param("operatorId", "7")
                        .param("projectId", "1")
                        .param("keyword", "登录")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-30")
                        .param("page", "2")
                        .param("size", "50")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(operationLogService).pageLogs(9L, "BUG", "UPDATE", 7L, 1L, "登录",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 2, 50);
    }

    @Test
    @DisplayName("操作人下拉：当前用户透传")
    void operators() throws Exception {
        UserOptionVO option = new UserOptionVO();
        option.setId(2L);
        option.setRealName("张三");
        when(operationLogService.listOperators(9L)).thenReturn(List.of(option));

        mockMvc.perform(get("/api/operation-logs/operators").requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].realName").value("张三"));
    }
}
