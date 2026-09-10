package com.pm.service;

import com.pm.vo.SearchResultVO;

/**
 * 全局搜索服务：顶部搜索框一次请求聚合 需求/Bug/项目/人员 四类结果（每类前 5 条）
 */
public interface SearchService {

    /**
     * 全局搜索
     * 可见性口径与各列表页一致：需求全员可见；项目/Bug 按用户可见项目过滤（管理员全量，
     * 普通用户仅参与或创建的项目）；人员仅返回姓名/用户名（排期表全员可见同口径）。
     * keyword 空白时不查库，返回全空结果。
     */
    SearchResultVO search(Long userId, String keyword);
}
