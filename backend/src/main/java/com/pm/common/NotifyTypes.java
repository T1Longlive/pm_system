package com.pm.common;

/**
 * 站内通知类型常量（notification.type，一期仅核心流转）
 */
public final class NotifyTypes {

    /** Bug 被指派（创建/改负责人） */
    public static final String BUG_ASSIGNED = "BUG_ASSIGNED";

    /** Bug 处理完成 */
    public static final String BUG_HANDLED = "BUG_HANDLED";

    /** Bug 验收结果（通过/未通过） */
    public static final String BUG_ACCEPTED = "BUG_ACCEPTED";

    /** 需求添加参与人 */
    public static final String REQ_PARTICIPANT_ADDED = "REQ_PARTICIPANT_ADDED";

    /** 需求移除参与人 */
    public static final String REQ_PARTICIPANT_REMOVED = "REQ_PARTICIPANT_REMOVED";

    /** 需求状态变更（进入测试中/待归档，由排期模块触发） */
    public static final String REQ_STATUS_CHANGED = "REQ_STATUS_CHANGED";

    /** 需求归档 */
    public static final String REQ_ARCHIVED = "REQ_ARCHIVED";

    /** 需求取消 */
    public static final String REQ_CANCELLED = "REQ_CANCELLED";

    /** 排期被修改 */
    public static final String SCHEDULE_CHANGED = "SCHEDULE_CHANGED";

    /** 排期跨月顺延生成新行 */
    public static final String SCHEDULE_OVERFLOW = "SCHEDULE_OVERFLOW";

    private NotifyTypes() {
    }
}
