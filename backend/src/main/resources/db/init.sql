-- =============================================================
-- 项目管理系统数据库初始化脚本
-- 依据：docs/02-数据库设计.md v1.0（13 张表 DDL 原样整理）
-- 字符集：utf8mb4
-- =============================================================

CREATE DATABASE IF NOT EXISTS `pm_system` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `pm_system`;

-- -------------------------------------------------------------
-- 2.1 user —— 系统用户
-- -------------------------------------------------------------
CREATE TABLE `user` (
  `id`              BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `username`        VARCHAR(30)  NOT NULL COMMENT '登录名',
  `password`        VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密',
  `real_name`       VARCHAR(30)  NOT NULL COMMENT '姓名',
  `is_admin`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0普通成员 1管理员',
  `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `force_change_pwd` TINYINT     NOT NULL DEFAULT 1 COMMENT '1需强制改密',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_username` (`username`)
) COMMENT='系统用户';

-- -------------------------------------------------------------
-- 2.2 project —— 项目
-- -------------------------------------------------------------
CREATE TABLE `project` (
  `id`          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `code`        VARCHAR(6)   NOT NULL COMMENT '项目缩写，编号前缀',
  `name`        VARCHAR(50)  NOT NULL,
  `description` VARCHAR(500) NULL,
  `creator_id`  BIGINT       NOT NULL COMMENT '创建者=项目经理',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_code` (`code`),
  KEY `idx_creator` (`creator_id`)
) COMMENT='项目';

-- -------------------------------------------------------------
-- 2.3 project_member —— 项目成员（按工种建行）
-- -------------------------------------------------------------
CREATE TABLE `project_member` (
  `id`         BIGINT      PRIMARY KEY AUTO_INCREMENT,
  `project_id` BIGINT      NOT NULL,
  `user_id`    BIGINT      NOT NULL,
  `craft`      VARCHAR(10) NOT NULL COMMENT 'PLANNER/FE/BE/OPS/QA/UI',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_proj_user_craft` (`project_id`, `user_id`, `craft`),
  KEY `idx_user` (`user_id`)
) COMMENT='项目成员（一人可多工种=多行）';

-- -------------------------------------------------------------
-- 2.4 requirement —— 需求
-- -------------------------------------------------------------
CREATE TABLE `requirement` (
  `id`             BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `req_no`         VARCHAR(20)  NOT NULL COMMENT 'REQ-{项目缩写}-{4位流水}',
  `project_id`     BIGINT       NOT NULL,
  `title`          VARCHAR(100) NOT NULL COMMENT '需求名称',
  `description`    MEDIUMTEXT   NULL COMMENT '富文本HTML',
  `priority`       TINYINT      NOT NULL DEFAULT 2 COMMENT '1高 2中 3低',
  `status`         VARCHAR(12)  NOT NULL DEFAULT 'PENDING'
                   COMMENT 'PENDING待排期/READY待开始/DEV开发中/TEST测试中/ARCHIVING待归档/ARCHIVED已归档/CANCELLED已取消',
  `planner_id`     BIGINT       NOT NULL COMMENT '策划（默认创建人，可改）',
  `created_by`     BIGINT       NOT NULL,
  `cancelled_by`   BIGINT       NULL,
  `cancelled_at`   DATETIME     NULL,
  `cancel_reason`  VARCHAR(500) NULL,
  `archived_at`    DATETIME     NULL,
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_project_reqno` (`project_id`, `req_no`),
  KEY `idx_project_status` (`project_id`, `status`),
  KEY `idx_planner` (`planner_id`)
) COMMENT='需求';

-- -------------------------------------------------------------
-- 2.5 requirement_participant —— 需求参与人
-- -------------------------------------------------------------
CREATE TABLE `requirement_participant` (
  `id`             BIGINT      PRIMARY KEY AUTO_INCREMENT,
  `requirement_id` BIGINT      NOT NULL,
  `user_id`        BIGINT      NOT NULL,
  `craft`          VARCHAR(10) NOT NULL COMMENT 'PLANNER/FE/BE/OPS/QA/UI',
  `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_req_user_craft` (`requirement_id`, `user_id`, `craft`),
  KEY `idx_user` (`user_id`)
) COMMENT='需求参与人（排期行生成来源）';

-- -------------------------------------------------------------
-- 2.6 schedule_month —— 月度排期表
-- -------------------------------------------------------------
CREATE TABLE `schedule_month` (
  `id`         BIGINT   PRIMARY KEY AUTO_INCREMENT,
  `year`       SMALLINT NOT NULL,
  `month`      TINYINT  NOT NULL COMMENT '1-12',
  `created_by` BIGINT   NOT NULL COMMENT '创建人（PM点击+ 或系统自动）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_year_month` (`year`, `month`)
) COMMENT='月度排期表（对应排期样板一个sheet）';

-- -------------------------------------------------------------
-- 2.7 schedule_row —— 排期行（需求×工种×负责人×月）
-- -------------------------------------------------------------
CREATE TABLE `schedule_row` (
  `id`             BIGINT        PRIMARY KEY AUTO_INCREMENT,
  `month_id`       BIGINT        NOT NULL,
  `requirement_id` BIGINT        NOT NULL,
  `craft`          VARCHAR(10)   NOT NULL,
  `user_id`        BIGINT        NOT NULL COMMENT '负责人',
  `seq`            INT           NOT NULL DEFAULT 0 COMMENT '当月表内需求展示序号（编号列）',
  `planned_effort` DECIMAL(5,2)  NULL COMMENT '计划工期（PM 填写，如 5 或 1.3）；跨月顺延判定=已排格子之和 < 计划工期 且 当月无剩余可填工作日',
  `status`         VARCHAR(10)   NOT NULL DEFAULT 'TODO'
                   COMMENT 'TODO待开始/VOID作废（参与人移除）；已排/排期中/未排期三态展示由前端按工期与已排合计派生',
  `done_at`        DATETIME      NULL COMMENT '已废弃（状态重构后不再写入，仅保留列）',
  `done_by`        BIGINT        NULL COMMENT '已废弃（状态重构后不再写入，仅保留列）',
  `is_overflow`    TINYINT       NOT NULL DEFAULT 0 COMMENT '1=跨月自动顺延生成的行',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_month_req_craft_user` (`month_id`, `requirement_id`, `craft`, `user_id`),
  KEY `idx_requirement` (`requirement_id`),
  KEY `idx_user_status` (`user_id`, `status`)
) COMMENT='排期行；工期=该行 schedule_cell 之和，不落库';

-- -------------------------------------------------------------
-- 2.8 schedule_cell —— 排期格子（行×日）
-- -------------------------------------------------------------
CREATE TABLE `schedule_cell` (
  `id`      BIGINT        PRIMARY KEY AUTO_INCREMENT,
  `row_id`  BIGINT        NOT NULL,
  `day`     DATE          NOT NULL,
  `value`   DECIMAL(4,2)  NOT NULL COMMENT '当天工作量 0<x<=1，步进0.1',
  UNIQUE KEY `uk_row_day` (`row_id`, `day`),
  KEY `idx_day` (`day`)
) COMMENT='排期格子；空格子不落库，只存已填格子';

-- -------------------------------------------------------------
-- 2.9 calendar_day —— 工作日历
-- -------------------------------------------------------------
CREATE TABLE `calendar_day` (
  `day`      DATE         PRIMARY KEY,
  `type`     VARCHAR(12)  NOT NULL COMMENT 'WORKDAY工作日/WEEKEND周末/HOLIDAY法定节假日/ADJUSTED调休上班(周末标记为工作日)',
  `name`     VARCHAR(30)  NULL COMMENT '节假日名称',
  `updated_at` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_type` (`type`)
) COMMENT='工作日历；初始化生成未来3年，周末自动标记，节假日/调休由管理员维护';

-- -------------------------------------------------------------
-- 2.10 bug —— 缺陷
-- -------------------------------------------------------------
CREATE TABLE `bug` (
  `id`            BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `bug_no`        VARCHAR(20)  NOT NULL COMMENT 'BUG-{项目缩写}-{4位流水}',
  `project_id`    BIGINT       NOT NULL,
  `version`       VARCHAR(20)  NULL COMMENT '发现版本',
  `feature`       VARCHAR(50)  NULL COMMENT '功能模块（文本，不关联需求）',
  `description`   MEDIUMTEXT   NULL COMMENT '问题描述',
  `priority`      TINYINT      NULL DEFAULT NULL COMMENT '优先级 1高 2中 3低',
  `owner_id`      BIGINT       NULL COMMENT '负责人',
  `handle_status` VARCHAR(10)  NULL DEFAULT NULL
                  COMMENT '空=未处理/DONE已处理/HOLD暂不处理/SKIP不处理',
  `tester_id`     BIGINT       NULL COMMENT '测试人员',
  `accept_status` VARCHAR(10)  NULL DEFAULT NULL
                  COMMENT '空=未验收/PASSED验收通过/REJECTED验收不通过',
  `solution`      MEDIUMTEXT   NULL COMMENT '处理方案',
  `dev_remark`    VARCHAR(500) NULL COMMENT '开发备注',
  `test_remark`   VARCHAR(500) NULL COMMENT '测试备注',
  `video_url`     VARCHAR(500) NULL COMMENT '问题视频链接',
  `video_name`    VARCHAR(200) NULL COMMENT '视频名称（显示用）',
  `archived`      TINYINT      NOT NULL DEFAULT 0 COMMENT '0总表 1归档表',
  `archived_at`   DATETIME     NULL,
  `archived_by`   BIGINT       NULL,
  `created_by`    BIGINT       NOT NULL,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_project_bugno` (`project_id`, `bug_no`),
  KEY `idx_project_archived` (`project_id`, `archived`),
  KEY `idx_owner` (`owner_id`, `handle_status`),
  KEY `idx_tester` (`tester_id`, `accept_status`)
) COMMENT='Bug；总表=archived 0，归档表=archived 1，不物理拆表';

-- -------------------------------------------------------------
-- 2.11 attachment —— 附件（统一）
-- -------------------------------------------------------------
CREATE TABLE `attachment` (
  `id`         BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `biz_type`   VARCHAR(20)  NOT NULL COMMENT 'REQUIREMENT/BUG',
  `biz_id`     BIGINT       NOT NULL COMMENT '业务主键',
  `file_url`   VARCHAR(300) NOT NULL COMMENT '/upload/2026/09/uuid.png',
  `file_name`  VARCHAR(100) NOT NULL COMMENT '原始文件名',
  `file_type`  VARCHAR(10)  NOT NULL COMMENT 'IMAGE/DOC/VIDEO_LINK等',
  `file_size`  BIGINT       NOT NULL COMMENT '字节',
  `uploaded_by` BIGINT      NOT NULL,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_biz` (`biz_type`, `biz_id`)
) COMMENT='附件；富文本内嵌图片同样走此表';

-- -------------------------------------------------------------
-- 2.12 biz_operation_log —— 统一操作留痕
-- -------------------------------------------------------------
CREATE TABLE `biz_operation_log` (
  `id`          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `biz_type`    VARCHAR(20)  NOT NULL COMMENT 'REQUIREMENT/BUG',
  `biz_id`      BIGINT       NOT NULL,
  `operator_id` BIGINT       NOT NULL,
  `action`      VARCHAR(30)  NOT NULL COMMENT 'CREATE/UPDATE/HANDLE_DONE/ACCEPT_PASS/ACCEPT_REJECT/ARCHIVE/RESTORE/CANCEL/ADD_PARTICIPANT/REMOVE_PARTICIPANT/SCHEDULE_SAVE/NODE_DONE',
  `round_no`    INT          NOT NULL DEFAULT 1 COMMENT 'Bug验收轮次（需求恒1）',
  `remark`      VARCHAR(500) NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_biz` (`biz_type`, `biz_id`),
  KEY `idx_operator` (`operator_id`)
) COMMENT='操作日志；Bug轮次=该bug ACCEPT_REJECT 次数+1，由此推导';

-- -------------------------------------------------------------
-- 2.13 notification —— 站内通知
-- -------------------------------------------------------------
CREATE TABLE `notification` (
  `id`         BIGINT       PRIMARY KEY AUTO_INCREMENT,
  `receiver_id` BIGINT      NOT NULL,
  `type`       VARCHAR(30)  NOT NULL COMMENT 'BUG_ASSIGNED/BUG_HANDLED/BUG_ACCEPTED/REQ_PARTICIPANT_ADDED/REQ_PARTICIPANT_REMOVED/REQ_STATUS_CHANGED/REQ_ARCHIVED等',
  `title`      VARCHAR(100) NOT NULL,
  `content`    VARCHAR(300) NOT NULL,
  `biz_type`   VARCHAR(20)  NULL COMMENT '跳转目标类型',
  `biz_id`     BIGINT       NULL COMMENT '跳转目标ID',
  `is_read`    TINYINT      NOT NULL DEFAULT 0,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_receiver_read` (`receiver_id`, `is_read`, `created_at`)
) COMMENT='站内通知；保留90天，定时清理';

-- =============================================================
-- 种子数据：初始管理员
-- 用户名：admin / 初始密码：Pm@123456（BCrypt 加密存储）
-- 哈希由 spring-security-crypto 的 BCryptPasswordEncoder 生成并经 matches() 验证
-- force_change_pwd=0：管理员已为正式密码，不强制修改
-- =============================================================
INSERT INTO `user` (`username`, `password`, `real_name`, `is_admin`, `status`, `force_change_pwd`)
VALUES ('admin', '$2a$10$8Vlc7gKDCNIWgWuFn6sYr.QwKS0NJZn9vUnHlbaTxCWRakm0JfmZG', '系统管理员', 1, 1, 0);
