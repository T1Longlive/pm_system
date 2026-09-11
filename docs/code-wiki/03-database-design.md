# 03 · 数据模型与数据库设计

数据库 `pm_system`（utf8mb4），共 **13 张业务表**，由 `backend/src/main/resources/db/init.sql` 一次性初始化（建库 + 建表 + 种子管理员）。

设计约定先行：
- **无物理外键**：全部靠唯一键 + 普通索引关联（id 为 BIGINT 自增主键）。
- **成员/参与人按「工种」建行**：一人多工种 = 多行，唯一键 `(xxx, user_id, craft)`。
- **格子稀疏存储**：`schedule_cell` 只存已填写的（工作日 × 行），空格子不落库。
- **已排工作量不落库**：= 该行 `SUM(schedule_cell.value)` 实时计算。
- **多态关联**：附件/操作日志/通知用 `biz_type + biz_id` 同时服务需求与 Bug。

## 1. 实体 — 表映射总览

| 实体类（`com/pm/entity/`） | 表名 | 说明 |
|------|------|------|
| `User` | `` `user` `` | 系统用户（表名关键字，反引号转义） |
| `Project` | `project` | 项目（创建者 = PM） |
| `ProjectMember` | `project_member` | 项目成员（user × craft 一行） |
| `Requirement` | `requirement` | 需求 |
| `RequirementParticipant` | `requirement_participant` | 需求参与人（排期行生成来源） |
| `ScheduleMonth` | `schedule_month` | 月度排期表 |
| `ScheduleRow` | `schedule_row` | 排期行（需求 × 工种 × 负责人） |
| `ScheduleCell` | `schedule_cell` | 排期格子（行 × 日） |
| `CalendarDay` | `calendar_day` | 工作日历（**day DATE 为主键**，`IdType.INPUT`） |
| `Bug` | `bug` | 缺陷（Excel 化编辑模型，archived 区分总表/归档表） |
| `Attachment` | `attachment` | 附件/富文本内嵌图片（统一表） |
| `BizOperationLog` | `biz_operation_log` | 统一操作留痕 |
| `Notification` | `notification` | 站内通知 |

## 2. 表结构详解

### 2.1 `user` — 系统用户

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增 |
| username | VARCHAR(30) `uk_username` | 登录名，全局唯一 |
| password | VARCHAR(100) | BCrypt 哈希 |
| real_name | VARCHAR(30) | 姓名 |
| is_admin | TINYINT | 0 普通成员 / 1 管理员 |
| status | TINYINT | 1 正常 / 0 停用 |
| force_change_pwd | TINYINT | 1 需强制改密（新建/重置密码后） |

### 2.2 `project` — 项目

| 字段 | 说明 |
|------|------|
| code | VARCHAR(6) `uk_code` 项目缩写，全局唯一，编号前缀（前端限 2-6 位大写字母） |
| name / description | 名称 ≤50 字 / 描述 |
| creator_id | 创建者 = 项目经理（`idx_creator`）；**不写入 project_member** |

### 2.3 `project_member` — 项目成员

- 唯一键 `uk_proj_user_craft(project_id, user_id, craft)`，索引 `idx_user`。
- `craft` 取值：`PLANNER / FE / BE / OPS / QA / UI`（见后端 `CraftConstants`、前端 `constants/craft.ts`）。

### 2.4 `requirement` — 需求

| 字段 | 说明 |
|------|------|
| req_no | `REQ-{项目缩写}-{4位流水}`，唯一键 `uk_project_reqno(project_id, req_no)` |
| project_id | 从属项目（`idx_project_status`） |
| title / description | 标题 ≤100 字 / 富文本 HTML（MEDIUMTEXT） |
| priority | 1 高 / 2 中 / 3 低 |
| status | `PENDING/DEV/TEST/ARCHIVING/ARCHIVED/CANCELLED`（`READY` 为历史遗留值，不再写入）。**派生值**：由排期数据 + 日期投影而来，仅归档/取消为人工落库 |
| planner_id | 策划人（默认创建人，可改，`idx_planner`） |
| created_by | 创建人 |
| cancelled_by / cancelled_at / cancel_reason | 取消留痕 |
| archived_at | 归档时间 |
| updated_at | DATETIME，**乐观锁版本**（对外出参毫秒时间戳） |

### 2.5 `requirement_participant` — 需求参与人

- 唯一键 `uk_req_user_craft(requirement_id, user_id, craft)`，索引 `idx_user`。
- **排期行的生成来源**：把需求加入月表时按此表逐行生成 `schedule_row`。

### 2.6 `schedule_month` — 月度排期表

- `year` + `month`(1-12)，唯一键 `uk_year_month`；`created_by`（PM 手动创建或跨月顺延时系统自动创建）。
- 对应前端排期页的一个「月 Tab / Sheet」。

### 2.7 `schedule_row` — 排期行

| 字段 | 说明 |
|------|------|
| month_id / requirement_id / craft / user_id | 唯一键 `uk_month_req_craft_user` 四列；一行 = 该月表中某需求的某工种 × 某负责人 |
| seq | 当月表内需求展示序号（同一需求的行共用 seq，分组排序用） |
| planned_effort | DECIMAL(5,2) 计划工期（PM 填，≤999.99，可空）。**跨全部月份共用**：排期组 = 同（需求,工种,负责人）的全部行，任一月保存工期会同步全组 |
| status | `TODO 待开始 / DONE 已完成 / VOID 作废`（VOID = 参与人被移除等场景，前端灰显）。**注**：`DONE` 已废弃不再写入，行完成态由前端按「工期 vs 已排合计」派生 |
| done_at / done_by | **已废弃**（状态重构后不再写入，仅保留列） |
| updated_at | 乐观锁版本 |

### 2.8 `schedule_cell` — 排期格子

- 唯一键 `uk_row_day(row_id, day)`，索引 `idx_day`；`day` 为绝对日期（跨月对齐天然成立）。
- `value` DECIMAL(4,2)：当天工作量，`0 < v ≤ 1`，步进 0.1；**空格子不落库**。
- **已排统计按排期组跨月求和**：某组（需求×工种×负责人）已排 = 组内全部月份行的格子之和（`selectGroupCellsSum` / 视图 `scheduledSum`），排满判定与「已排」列展示均用此口径。

### 2.9 `calendar_day` — 工作日历

- **主键 `day` DATE**；`type`：`WORKDAY / WEEKEND / HOLIDAY / ADJUSTED`（`idx_type`）；`name` 节假日名称。
- 启动时 `CalendarServiceImpl`（ApplicationRunner）预生成 `[当月, 今天+12个月]` 缺失日期（周末规则，幂等）；节假日/调休由管理员维护。
- **可排班日 = WORKDAY + ADJUSTED**，是排期格子校验、跨月顺延、「待开始/进行中」细分的基础。

### 2.10 `bug` — 缺陷（Excel 化编辑模型）

「总表/归档表」不物理拆表，靠 `archived` 区分；字段全部可空（除编号/项目/创建人），对应前端单元格逐格编辑。

| 字段 | 说明 |
|------|------|
| bug_no | `BUG-{项目缩写}-{4位流水}`，唯一键 `uk_project_bugno`（Redis INCR `seq:bug:{projectId}` 生成） |
| project_id | 从属项目（`idx_project_archived`） |
| version | 发现版本 VARCHAR(20) |
| feature | 功能模块 VARCHAR(50)，纯文本（不关联需求） |
| description / solution | MEDIUMTEXT 问题描述 / 处理方案 |
| priority | 1 高 / 2 中 / 3 低，可空 |
| owner_id | 处理负责人（`idx_owner(owner_id, handle_status)`） |
| handle_status | 空=未处理 / **DONE 已处理 / HOLD 暂不处理 / SKIP 不处理** |
| tester_id | 测试人员（`idx_tester(tester_id, accept_status)`） |
| accept_status | 空=未验收 / **PASSED 验收通过 / REJECTED 验收不通过** |
| dev_remark / test_remark | 开发备注 / 测试备注 VARCHAR(500) |
| video_url / video_name | 问题视频链接 / 视频名称（显示用） |
| archived | 0 总表 / 1 归档表（**不物理拆表**）；archived_at / archived_by |
| created_by | 创建人 |

> 重新打开（归档 → 总表）会**置空 handle_status 与 accept_status**（显式 set null）。`BugMapper` 有自定义 SQL `selectMaxSeq`（编号流水回填，同需求编号套路）。

### 2.11 `attachment` — 附件（统一）

- 索引 `idx_biz(biz_type, biz_id)`；`biz_type`：`REQUIREMENT / BUG`。
- `file_url` = `/upload/{yyyy}/{MM}/{uuid}.{ext}`；`file_name`（截断至 100 字符）、`file_type`（IMAGE/DOC）、`file_size`、`uploaded_by`。
- 需求编辑时附件为**全量替换**（先删后插）；富文本内嵌图片同样落此表。Bug 问题截图（biz_type=BUG + fileType=IMAGE）逐张追加/删除，删除 Bug 行时连带清理。

### 2.12 `biz_operation_log` — 操作留痕

- 索引 `idx_biz(biz_type, biz_id)`、`idx_operator`、**`idx_project(project_id)`**。
- **`project_id`**：所属项目（日志按项目过滤权限用，操作日志页据此限定可见范围；管理员全量、PM 仅自己项目）。
- `biz_type`：`REQUIREMENT / BUG / PROJECT`。
- `action`：`CREATE / UPDATE / DELETE / CANCEL / ARCHIVE / RESTORE / ADD_PARTICIPANT / REMOVE_PARTICIPANT / SCHEDULE_ADD / SCHEDULE_SAVE / SCHEDULE_REMOVE / SCHEDULE_COPY / NODE_DONE / STAGE_SYNC / ADD_MEMBER / REMOVE_MEMBER / ADD_IMAGE / DELETE_IMAGE`；另有 Bug 预留 `HANDLE_DONE / ACCEPT_PASS / ACCEPT_REJECT`（当前无产生方）。
- `round_no`：Bug 验收轮次（= 该 bug ACCEPT_REJECT 次数 + 1）；需求恒为 1。
- `remark`：变更摘要（如「标题已修改；优先级：中→高；参与人 +张三(前端)、-李四(测试)；附件 +2」）。

> `STAGE_SYNC` 为状态派生对账专用动作：仅在**用户触发**的写路径对账时写（定时任务对账无操作人，不写日志）。

### 2.13 `notification` — 站内通知

- 索引 `idx_receiver_read(receiver_id, is_read, created_at)`。
- `type`（10 种，见 [02-backend-modules.md](02-backend-modules.md) 第 9 节）、`title`、`content`（VARCHAR 300，超长截断）、`biz_type/biz_id`（前端跳转目标）、`is_read`。
- 注释约定保留 90 天后清理（**清理定时任务未实现**）。

## 3. 实体关系（ER 概览）

```
user ──┬──< project_member >── project（creator_id → user，即 PM）
       │
       ├── planner_id / created_by / cancelled_by / done_by / owner_id / tester_id ...
       │
requirement >── project
    │  ├──< requirement_participant >── user（工种 × 人员）
    │  ├──< attachment >（biz_type=REQUIREMENT）
    │  ├──< biz_operation_log >（biz_type=REQUIREMENT）
    │  └──< schedule_row >──< schedule_cell >
    │              │
schedule_month >─┘（month_id）

bug >── project（owner_id / tester_id → user；attachment 多态已用、log/通知多态预留）
calendar_day：独立维表（day 主键）
notification：receiver_id → user
```

**排期三级结构**：`schedule_month`（1 : N）`schedule_row`（1 : N）`schedule_cell`；排期行由需求参与人生成，格子按日填写，已排工时 = 格子之和。

**排期组**：同（需求, 工种, 负责人）跨**全部月份**的行集合。工期组内共用（任一月改工期同步全组）；已排 = 组内跨全部月份格子之和；排满 = 每组有工期且组内跨月合计 ≥ 工期。这是需求状态派生的基本单元。

## 4. 需求状态机（派生化口径）

> **`status` 是「排期数据 + 日期」的派生投影，不是手工维护的物化字段。** 详见 [09-state-derivation.md](09-state-derivation.md)。

```
                    ┌── 人工落库决定（仅此两类不参与派生）──┐
                    │  PM 取消(任意非终态) → CANCELLED(终态) │
                    │  PM 归档(仅 ARCHIVING) → ARCHIVED(终态)│
                    └──────────────────────────────────────┘

 派生规则（deriveStage 纯函数，按排期组数据 + 今天）：
   PENDING   ← 无非 VOID 排期行，或 任一排期组未排满（无工期/工期≤0/跨月格子合计<工期）
   DEV       ← 已排满 且 存在非 QA 行 且（有行无格子 或 最大非 QA 格子日 ≥ 今天）   开发窗口未结束
   TEST      ← 开发窗口已结束 且（有行无格子 或 最大格子日 ≥ 今天，含 QA 行）      测试窗口未结束
   ARCHIVING ← 全部行（含 QA）的格子日都已是过去                                等待 PM 归档
```

| 派生结果 | 判定（跨全部月份非 VOID 行聚合的排期组） |
|------|------|
| PENDING | `groups.isEmpty()` 或 任一组 `plannedEffort` 为空/≤0 或 `sum(cells) < plannedEffort` |
| DEV | 全部组排满，且存在非 QA 组，且非 QA 组中存在「组内有空行」或「组内最大格子日 ≥ 今天」 |
| TEST | 非 QA 组已全部「每行有格子且最大格子日 < 今天」，且全部组（含 QA）中存在「组内有空行」或「最大格子日 ≥ 今天」 |
| ARCHIVING | 全部组（含 QA）每行有格子且最大格子日 < 今天 |

**写入路径（唯一）**：
- 排期写路径（加入/移除需求、保存行、复制下月）末尾调 `syncStage(reqId, operatorId)` → 派生 vs 库中不一致则条件更新（`eq 旧状态` 防并发）+ 通知参与人 + 写 `STAGE_SYNC` 日志。
- 需求编辑改参与人（`applyParticipantDiff`）末尾同样调 `syncStage`。
- 定时任务 `RequirementStatusTask` 每小时整点 + 启动时调 `syncAllStages()` 全量对账（无操作人，不写日志，仅通知）。
- 归档/取消由 `RequirementServiceImpl` 直写 status（人工终态，不参与派生）。

**不变式**：
- 归档/取消是仅有的人工落库状态，`deriveStage` 遇到终态直接沿用、不改写；
- 条件更新 `WHERE status=旧值` 不可删，否则会与取消/归档并发时覆盖终态；
- `READY` 不再作为存储阶段：前端筛选已移除，仅后端常量与历史数据兜底。

## 5. 编号规则

| 业务 | 规则 | 生成方式 |
|------|------|------|
| 需求 | `REQ-{项目缩写}-{4位流水}`，如 `REQ-PM-0001` | Redis `INCR seq:req:{projectId}`；key 缺失时先用 `selectMaxSeq`（`MAX(CAST(RIGHT(req_no,4) AS UNSIGNED))`）从 DB 回填（SETNX 防并发覆盖），格式化 `String.format("%04d", seq)` |
| Bug | `BUG-{项目缩写}-{4位流水}` | 同套路：Redis `INCR seq:bug:{projectId}` + `BugMapper.selectMaxSeq`（`COALESCE` 到 0）回填 |

流水号按项目独立递增；唯一键 `(project_id, req_no)` / `(project_id, bug_no)` 兜底。

## 6. 种子数据

`init.sql` 末尾仅插入一条初始管理员：

```
username: admin
password: Pm@123456   （BCrypt 哈希存储）
real_name: 系统管理员   is_admin=1  status=1  force_change_pwd=0
```

其余业务数据（项目/成员/需求/排期/日历）均由运行产生；日历由后端启动时自动预生成 12 个月。
