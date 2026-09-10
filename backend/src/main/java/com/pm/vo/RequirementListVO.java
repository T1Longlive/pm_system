package com.pm.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 需求列表项（GET /api/requirements 记录行）
 */
@Data
public class RequirementListVO {

    private Long id;

    /** 需求编号：REQ-{项目缩写}-{4位流水} */
    private String reqNo;

    private Long projectId;

    private String projectName;

    /** 需求名称 */
    private String title;

    /** 优先级：1高 2中 3低 */
    private Integer priority;

    /** 状态：PENDING/READY/DEV/TEST/ARCHIVING/ARCHIVED/CANCELLED */
    private String status;

    private Long plannerId;

    private String plannerName;

    private Long createdBy;

    /** 创建人姓名（列表展示用） */
    private String creatorName;

    private LocalDateTime createdAt;

    /** 最后更新时间（毫秒时间戳），编辑时原样回传用于乐观锁校验 */
    private Long updatedAt;

    /** 参与人平铺列表（同一人多工种多行，前端按工种分组展示） */
    private List<ParticipantVO> participants;

    /** 各工种排期日期区间（列表页进度缩略图的开发/测试节点按日期点亮用） */
    private List<CraftScheduleVO> craftSchedules;

    /** 当前用户在该项目的角色：PM+工种并存时含 PM，无角色返回空数组 */
    private List<String> myRole;
}
