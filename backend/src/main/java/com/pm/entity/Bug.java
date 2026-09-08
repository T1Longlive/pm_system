package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 缺陷（对应表 bug）
 * Excel 化编辑模型：字段均可留空，前端单元格直接编辑；
 * 总表=archived 0，归档表=archived 1，不物理拆表
 */
@Data
@TableName("bug")
public class Bug {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Bug编号：BUG-{项目缩写}-{4位流水} */
    private String bugNo;

    private Long projectId;

    /** 发现版本 */
    private String version;

    /** 功能模块（文本，不关联需求） */
    private String feature;

    /** 问题描述 */
    private String description;

    /** 优先级：1高 2中 3低 */
    private Integer priority;

    /** 负责人 */
    private Long ownerId;

    /** 处理进度：空=未处理/DONE已处理/HOLD暂不处理/SKIP不处理 */
    private String handleStatus;

    /** 测试人员 */
    private Long testerId;

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

    /** 视频名称（显示用，与 video_url 同源写入） */
    private String videoName;

    /** 0总表 1归档表 */
    private Integer archived;

    /** 归档时间 */
    private LocalDateTime archivedAt;

    /** 归档人 */
    private Long archivedBy;

    /** 创建人 */
    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
