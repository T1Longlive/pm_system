package com.pm.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bug 列表项（Excel 化编辑模型，无独立详情接口，列表 VO 携带全量字段；
 * images 供截图列缩略图与走马灯预览）
 */
@Data
public class BugListVO {

    private Long id;

    /** Bug编号：BUG-{项目缩写}-{4位流水} */
    private String bugNo;

    private Long projectId;

    private String projectName;

    /** 发现版本 */
    private String version;

    /** 功能模块 */
    private String feature;

    /** 问题描述 */
    private String description;

    /** 优先级：1高 2中 3低 */
    private Integer priority;

    /** 负责人 */
    private Long ownerId;

    private String ownerName;

    /** 处理进度：空=未处理/DONE已处理/HOLD暂不处理/SKIP不处理 */
    private String handleStatus;

    /** 测试人员 */
    private Long testerId;

    private String testerName;

    /** 验收进度：空=未验收/PASSED验收通过/REJECTED验收不通过 */
    private String acceptStatus;

    /** 处理方案 */
    private String solution;

    /** 开发备注 */
    private String devRemark;

    /** 测试备注 */
    private String testRemark;

    /** 问题视频链接 */
    private String videoUrl;

    /** 视频名称（显示用） */
    private String videoName;

    /** 创建人 */
    private Long createdBy;

    private String creatorName;

    private LocalDateTime createdAt;

    /** 更新时间（毫秒时间戳） */
    private Long updatedAt;

    /** 0总表（Bug 列表） 1归档表（归档表） */
    private Integer archived;

    /** 归档时间（归档表展示） */
    private LocalDateTime archivedAt;

    /** 归档人姓名（归档表展示） */
    private String archivedByName;

    /** 问题截图（attachment biz_type=BUG，按 id 升序，第一张即缩略图） */
    private List<AttachmentVO> images;

    /** 我的角色（项目级）：PM + 工种码；空数组 = 非成员（归档按钮判断用） */
    private List<String> myRole;
}
