package com.pm.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局搜索结果 VO：需求/Bug/项目/人员 四类各前 N 条（keyword 空白时四类均为空数组）
 */
@Data
public class SearchResultVO {

    private List<ReqItem> requirements = new ArrayList<>();

    private List<BugItem> bugs = new ArrayList<>();

    private List<ProjectItem> projects = new ArrayList<>();

    private List<UserItem> users = new ArrayList<>();

    /** 需求命中项 */
    @Data
    public static class ReqItem {
        private Long id;
        private String reqNo;
        private String title;
        private String projectName;
        private String status;
    }

    /** Bug 命中项 */
    @Data
    public static class BugItem {
        private Long id;
        private String bugNo;
        private Long projectId;
        private String feature;
        private String projectName;
        private String handleStatus;
        /** 0总表 1归档表 */
        private Integer archived;
    }

    /** 项目命中项 */
    @Data
    public static class ProjectItem {
        private Long id;
        private String name;
        private String code;
    }

    /** 人员命中项（仅公开信息：姓名/用户名，排期表全员可见同口径） */
    @Data
    public static class UserItem {
        private Long id;
        private String realName;
        private String username;
    }
}
