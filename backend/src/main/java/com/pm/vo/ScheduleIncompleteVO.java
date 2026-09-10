package com.pm.vo;

import lombok.Data;

/**
 * 排期未完成的需求 VO（GET /api/schedule/incomplete，前端排期界面退出拦截用）：
 * 状态已推进（READY/DEV）但存在未排满排期组（开发已开始、不可回退）的需求
 */
@Data
public class ScheduleIncompleteVO {

    /** 需求 ID */
    private Long requirementId;

    /** 需求编号 */
    private String reqNo;

    /** 需求名称 */
    private String title;
}
