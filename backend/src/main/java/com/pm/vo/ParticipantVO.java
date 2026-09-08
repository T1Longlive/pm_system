package com.pm.vo;

import lombok.Data;

/**
 * 需求参与人项（需求列表/详情 participants 元素，平铺返回，前端按工种分组展示）
 */
@Data
public class ParticipantVO {

    private Long userId;

    private String realName;

    /** 工种代码：PLANNER/FE/BE/OPS/QA/UI */
    private String craft;
}
