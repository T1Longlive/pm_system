package com.pm.controller;

import com.pm.common.Result;
import com.pm.service.SearchService;
import com.pm.vo.SearchResultVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局搜索接口（/api/search，任何登录用户）
 * 顶部搜索框一次请求聚合 需求/Bug/项目/人员 四类结果；可见性口径与各列表页一致
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    /**
     * 全局搜索：keyword 必传（可含首尾空格），命中各类前 5 条
     */
    @GetMapping
    public Result<SearchResultVO> search(@RequestParam String keyword,
                                         HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(searchService.search(userId, keyword));
    }
}
