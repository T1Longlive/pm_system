package com.pm.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 需求详情（GET /api/requirements/{id}），任何登录用户可看
 */
@Data
public class RequirementDetailVO {

    private Long id;

    /** 需求编号：REQ-{项目缩写}-{4位流水} */
    private String reqNo;

    private Long projectId;

    private String projectName;

    /** 需求名称 */
    private String title;

    /** 需求描述（富文本HTML） */
    private String description;

    /** 优先级：1高 2中 3低 */
    private Integer priority;

    /** 状态：PENDING/READY/DEV/TEST/ARCHIVING/ARCHIVED/CANCELLED */
    private String status;

    private Long plannerId;

    private String plannerName;

    private Long createdBy;

    /** 创建人姓名（展示用） */
    private String creatorName;

    private LocalDateTime createdAt;

    /** 最后更新时间（毫秒时间戳），编辑时原样回传用于乐观锁校验 */
    private Long updatedAt;

    /** 取消人（仅 CANCELLED 状态有值） */
    private Long cancelledBy;

    /** 取消人姓名 */
    private String cancelledByName;

    /** 取消时间 */
    private LocalDateTime cancelledAt;

    /** 取消原因 */
    private String cancelReason;

    /** 归档时间（仅 ARCHIVED 状态有值） */
    private LocalDateTime archivedAt;

    /** 参与人平铺列表（前端按工种分组展示） */
    private List<ParticipantVO> participants;

    /** 工种排期日期区间（该需求全部非 VOID 行按工种聚合 min/max 格子日；进度条分支卡按日期配色） */
    private List<CraftScheduleVO> craftSchedules;

    /** 附件列表 */
    private List<AttachmentVO> attachments;

    /** 当前用户在该项目的角色：PM+工种并存时含 PM，无角色返回空数组 */
    private List<String> myRole;
}
