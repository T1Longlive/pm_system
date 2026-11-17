package com.pm.controller;

import com.pm.mapper.UserMapper;
import com.pm.service.CalendarService;
import com.pm.vo.CalendarMonthVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作日历接口切片测试：月份参数可空、维护接口的管理员校验、日期路径格式与入参校验。
 */
@ExtendWith(MockitoExtension.class)
class CalendarControllerTest {

    @Mock
    private CalendarService calendarService;

    @Mock
    private UserMapper userMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new CalendarController(calendarService));
    }

    @AfterEach
    void tearDown() {
        ControllerTestSupport.clearCurrentUser();
    }

    @Test
    @DisplayName("查询：year/month 均可缺省并由服务层决定默认月份")
    void monthDefaults() throws Exception {
        CalendarMonthVO vo = new CalendarMonthVO();
        vo.setYear(2026);
        vo.setMonth(11);
        when(calendarService.getMonth(null, null)).thenReturn(vo);

        mockMvc.perform(get("/api/calendar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.year").value(2026))
                .andExpect(jsonPath("$.data.month").value(11));

        verify(calendarService).getMonth(null, null);
    }

    @Test
    @DisplayName("维护：type 为空 → 400（校验先于管理员判断）")
    void updateValidation() throws Exception {
        mockMvc.perform(put("/api/calendar/2026-09-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("维护：日期路径格式非法 → 400 且提示格式")
    void updateBadDayFormat() throws Exception {
        ControllerTestSupport.installAdmin(9L, userMapper);

        mockMvc.perform(put("/api/calendar/2026-9-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"WORKDAY\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(startsWith("日期格式错误：")));
    }

    @Test
    @DisplayName("维护：管理员通过后按 LocalDate 调用服务")
    void updateSuccess() throws Exception {
        ControllerTestSupport.installAdmin(9L, userMapper);

        mockMvc.perform(put("/api/calendar/2026-09-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"HOLIDAY\",\"name\":\"国庆节\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(calendarService).updateDay(eq(LocalDate.of(2026, 9, 1)), any());
    }

    @Test
    @DisplayName("维护：非管理员 → 403")
    void updateForbidden() throws Exception {
        ControllerTestSupport.installNonAdmin(9L, userMapper);

        mockMvc.perform(put("/api/calendar/2026-09-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"WORKDAY\"}")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }
}
