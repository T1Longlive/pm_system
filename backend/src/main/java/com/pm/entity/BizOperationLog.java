package com.pm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一操作留痕（对应表 biz_operation_log）
 * Bug验收轮次 = 该bug ACCEPT_REJECT 次数+1，由此推导
 */
@Data
@TableName("biz_operation_log")
public class BizOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型：REQUIREMENT/BUG */
    private String bizType;

    private Long bizId;

    /** 所属项目（日志按项目过滤查询权限用；写入时回填） */
    private Long projectId;

    /** 操作人 */
    private Long operatorId;

    /** 动作：CREATE/UPDATE/HANDLE_DONE/ACCEPT_PASS/ACCEPT_REJECT/ARCHIVE/RESTORE/CANCEL/ADD_PARTICIPANT/REMOVE_PARTICIPANT/SCHEDULE_SAVE/NODE_DONE */
    private String action;

    /** Bug验收轮次（需求恒1） */
    private Integer roundNo;

    /** 备注 */
    private String remark;

    private LocalDateTime createdAt;
}
