package com.pm.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑 Bug 入参（PUT /api/bugs/{id}）——Excel 单元格逐格保存，全字段可空部分更新
 * 任何登录用户可编辑未归档行；
 * 清空哨兵约定：null=不修改；字符串 ""=清空；数值/ID 0=清空
 */
@Data
public class BugUpdateDTO {

    /** 发现版本 */
    @Size(max = 20, message = "发现版本不能超过20字")
    private String version;

    /** 功能模块 */
    @Size(max = 50, message = "功能模块不能超过50字")
    private String feature;

    /** 问题描述 */
    @Size(max = 2000, message = "问题描述不能超过2000字")
    private String description;

    /** 优先级：1高 2中 3低；0=清空 */
    @Min(value = 0, message = "优先级只能为1/2/3")
    @Max(value = 3, message = "优先级只能为1/2/3")
    private Integer priority;

    /** 负责人；0=清空 */
    @Min(value = 0, message = "负责人参数非法")
    private Long ownerId;

    /** 测试人员；0=清空 */
    @Min(value = 0, message = "测试人员参数非法")
    private Long testerId;

    /** 处理进度：DONE已处理/HOLD暂不处理/SKIP不处理；""=清空（值域 Service 校验） */
    @Size(max = 10, message = "处理进度参数非法")
    private String handleStatus;

    /** 验收进度：PASSED验收通过/REJECTED验收不通过；""=清空（值域 Service 校验） */
    @Size(max = 10, message = "验收进度参数非法")
    private String acceptStatus;

    /** 处理方案 */
    @Size(max = 2000, message = "处理方案不能超过2000字")
    private String solution;

    /** 开发备注 */
    @Size(max = 500, message = "开发备注不能超过500字")
    private String devRemark;

    /** 测试备注 */
    @Size(max = 500, message = "测试备注不能超过500字")
    private String testRemark;

    /** 问题视频链接；""=清空 */
    @Size(max = 500, message = "视频链接不能超过500字")
    private String videoUrl;

    /** 视频名称；""=清空 */
    @Size(max = 200, message = "视频名称不能超过200字")
    private String videoName;
}
