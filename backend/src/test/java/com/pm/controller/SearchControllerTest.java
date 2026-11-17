package com.pm.controller;

import com.pm.service.SearchService;
import com.pm.vo.SearchResultVO;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局搜索接口切片测试：当前用户透传、关键词原样传递（含首尾空格）、四类结果结构。
 */
@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private SearchService searchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = ControllerTestSupport.build(new SearchController(searchService));
    }

    @Test
    @DisplayName("搜索：当前用户与关键词（含首尾空格）原样透传")
    void searchPassesKeyword() throws Exception {
        SearchResultVO result = new SearchResultVO();
        SearchResultVO.ReqItem item = new SearchResultVO.ReqItem();
        item.setId(1L);
        item.setReqNo("REQ-ACE-0001");
        result.setRequirements(List.of(item));
        when(searchService.search(9L, "  登录  ")).thenReturn(result);

        mockMvc.perform(get("/api/search")
                        .param("keyword", "  登录  ")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.requirements[0].reqNo").value("REQ-ACE-0001"))
                .andExpect(jsonPath("$.data.bugs").isArray())
                .andExpect(jsonPath("$.data.users").isArray());

        verify(searchService).search(9L, "  登录  ");
    }

    @Test
    @DisplayName("搜索：无命中时四类结果均为空数组")
    void searchEmpty() throws Exception {
        when(searchService.search(9L, "nohit")).thenReturn(new SearchResultVO());

        mockMvc.perform(get("/api/search")
                        .param("keyword", "nohit")
                        .requestAttr("userId", 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requirements").isEmpty())
                .andExpect(jsonPath("$.data.bugs").isEmpty())
                .andExpect(jsonPath("$.data.projects").isEmpty())
                .andExpect(jsonPath("$.data.users").isEmpty());
    }
}
