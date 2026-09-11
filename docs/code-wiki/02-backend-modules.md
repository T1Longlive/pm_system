# 02 · 后端模块与接口职责

后端由 **13 个 Controller** 对外暴露 `/api/**` 接口，与前端 `frontend/src/api/*` 中的 12 个领域模块对应（`request.ts` 为统一实例，不占模块名额；`user.ts` 同时承载通用分页类型）。所有接口统一返回 `Result{code, message, data}`；除 `/api/auth/login` 外均需 `Authorization: Bearer <token>`。

> 文中「权限」为业务规则层面的校验（实现在 Service 层）；「登录」= 通过 JWT 拦截器即可。

## 0. 模块总览

| Controller | 基础路径 | 对应 Service | 主要能力 |
|-----------|---------|-------------|----------|
| AuthController | `/api/auth` | AuthService | 登录、改密、当前用户信息 |
| UserController | `/api/users` | UserService | 人员管理（管理员）、用户下拉 |
| ProjectController | `/api/projects` | ProjectService | 项目 CRUD、成员（工种）管理 |
| RequirementController | `/api/requirements` | RequirementService | 需求全生命周期、操作日志 |
| ScheduleController | `/api/schedule` | ScheduleService | 月表、排期矩阵、行保存/移除、状态对账触发 |
| CalendarController | `/api/calendar` | CalendarService | 工作日历查询与维护 |
| FileController | `/api/files` | FileService | 附件上传 |
| NotificationController | `/api/notifications` | NotificationService | 站内通知 |
| BugController | `/api/bugs` | BugService | Bug Excel 化列表、逐格编辑、归档表 |
| **DashboardController** | `/api/dashboard` | DashboardService | 工作台个人汇总 |
| **ReportsController** | `/api/reports` | ReportsService | 统计报表（只读聚合） |
| **SearchController** | `/api/search` | SearchService | 全局搜索（四类聚合） |
| **OperationLogController** | `/api/operation-logs` | OperationLogService | 统一操作日志查询 |

> 加粗的 4 个模块为 2026-09 新增（此前文档标为"未实现/占位"）。

## 1. 认证模块 AuthController `/api/auth`

| 方法 | 路径 | 入参 | 返回 | 说明 |
|------|------|------|------|------|
| POST | `/api/auth/login` | `LoginDTO`（username/password，@Valid） | `LoginVO` | **公开接口**（JWT 白名单）。校验密码与账号状态，签发 token，返回 token/userId/username/realName/isAdmin/**isPm**/forceChangePwd。「用户不存在」与「密码错误」统一提示防探测；停用账号提示「账号已停用」 |
| POST | `/api/auth/change-password` | `ChangePasswordDTO` | `Result<Void>` | 校验原密码 → BCrypt 新密码落库 → 清除 `forceChangePwd` 标记 |
| GET | `/api/auth/me` | — | `UserVO` | 当前登录用户信息（含 `isPm` 标志） |

服务：`AuthServiceImpl`（`service/impl/AuthServiceImpl.java`）。`isPm` = 当前用户是否为任一项目的创建者（项目经理），供前端「操作日志」菜单显隐，避免前端拉项目列表猜测（项目超分页上限时会漏判）。

## 2. 用户模块 UserController `/api/users`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/users` | 管理员 | 分页查询（query: keyword/status/page/size），keyword 模糊匹配姓名/用户名，VO 不含密码 |
| POST | `/api/users` | 管理员 | 新增人员（`UserCreateDTO`）。密码可空 → 默认 `Pm@123456`；创建后 `force_change_pwd=1` 强制首次改密；用户名唯一校验 |
| PUT | `/api/users/{id}` | 管理员 | 编辑人员姓名（`UserUpdateDTO`） |
| POST | `/api/users/{id}/reset-password` | 管理员 | 重置密码（`ResetPasswordDTO`，空则默认 `Pm@123456`），重置后再次强制改密 |
| POST | `/api/users/{id}/status` | 管理员 | 启用(1)/停用(0)（`UserStatusDTO`），不允许停用自己 |
| GET | `/api/users/options` | 登录 | 全部正常状态用户的下拉选项（`UserOptionVO`），供添加成员/参与人等场景 |

服务：`UserServiceImpl`（常量 `DEFAULT_PASSWORD = "Pm@123456"`）。

## 3. 项目模块 ProjectController `/api/projects`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/projects` | 登录 | 分页列表（keyword）。管理员看全部；普通用户仅「我参与或我创建的」。列表 VO 含创建者姓名、成员数（去重）、我的角色 |
| POST | `/api/projects` | 管理员 | 创建项目（`ProjectCreateDTO`）。缩写 code 全局唯一（2-6 位大写字母，前端校验），创建者自动担任 PM（**不写 project_member**） |
| GET | `/api/projects/{id}` | 登录 | 项目详情（`ProjectDetailVO`：基本信息 + 成员列表按用户聚合工种 + myRole）。可见性：创建者/成员/管理员 |
| PUT | `/api/projects/{id}` | 该项目 PM | 编辑名称/描述（缩写不可改） |
| POST | `/api/projects/{id}/members` | 该项目 PM | 添加成员（`MemberAddDTO`：userId + crafts 多选）。**增量合并**：已有工种跳过，仅插新工种行 |
| DELETE | `/api/projects/{id}/members/{userId}?craft=` | 该项目 PM | 移除成员。带 craft 删该工种一行；不带删该用户全部工种行 |

服务：`ProjectServiceImpl`。成员按工种建行，唯一键 `(project_id, user_id, craft)`。已知 TODO：移除成员不联动其未完成的排期行。

## 4. 需求模块 RequirementController `/api/requirements`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/requirements` | 登录 | 分页列表（query: projectId/status/priority/plannerId/keyword/**excludeTerminal**）。**全局汇总视图，不按成员身份过滤**；keyword 模糊匹配编号或标题；`excludeTerminal=true` 排除终态（需求池场景）。列表 VO 含参与人平铺 + **各工种排期日期区间 `craftSchedules`**（进度缩略图按日期点亮用）+ myRole |
| POST | `/api/requirements` | 项目 PM 或 PLANNER 工种成员 | 创建需求（`RequirementCreateDTO`），返回新需求 id。参与人必须是对应工种的项目成员；生成 `reqNo`；初始状态 PENDING；逐个通知参与人 |
| GET | `/api/requirements/{id}` | 登录 | 需求详情（`RequirementDetailVO`：基本信息 + 富文本 + 参与人平铺 + 附件 + 取消信息 + 各工种排期区间 + myRole） |
| GET | `/api/requirements/{id}/logs` | **管理员或该项目 PM** | 操作日志时间线（`OperationLogVO`，id 倒序）。非管理员且非该项目 PM 返回 403 |
| PUT | `/api/requirements/{id}` | 项目 PM 或创建人 | 编辑需求（`RequirementUpdateDTO`）。**乐观锁**（updatedAt 毫秒，冲突 409）；终态不可编辑；参与人 diff（**新增 → 已有排期月份补行；移除 → 排期行标 VOID**）、附件全量替换（非 null）、变更摘要留痕；完成后 `syncStage` 对账 |
| POST | `/api/requirements/{id}/cancel` | 项目 PM | 取消需求（`RequirementCancelDTO` 原因必填）。任意非终态可取消，不可逆；通知全部参与人+策划 |
| POST | `/api/requirements/{id}/archive` | 项目 PM | 归档需求。**仅 ARCHIVING 状态可归档**；通知全部参与人 |

服务：`RequirementServiceImpl`（需求 CRUD 与编号生成核心；**不再承载状态机**——状态由 `RequirementStageService` 派生，本类在参与人变更后调 `syncStage` 收口，联动细节见 [09-state-derivation.md](09-state-derivation.md)，详见 [06-key-classes.md](06-key-classes.md)）。

## 5. 排期模块 ScheduleController `/api/schedule`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/schedule/months` | 登录 | 月表列表（按年月升序） |
| POST | `/api/schedule/months` | 管理员或任意项目 PM | 创建月度排期表（`ScheduleMonthCreateDTO`），同年月唯一，已存在 400 |
| GET | `/api/schedule/months/{monthId}/view` | 登录 | 排期视图（`ScheduleViewVO`：月份 + 整月日历（控制列灰显）+ 按需求分组的排期矩阵行，每行含跨月已排合计 scheduledSum；VOID 行也返回供前端灰显） |
| POST | `/api/schedule/months/{monthId}/requirements` | 该需求项目的 PM | 将需求加入月表（`ScheduleRequirementDTO`）。需求必须非终态；同需求同月只生成一次；按参与人逐行生成排期行，返回生成行数；完成后 `syncStage` 对账 |
| DELETE | `/api/schedule/months/{monthId}/requirements/{requirementId}` | 该需求项目的 PM | **把需求从月表移除**：删除该需求在该月的全部排期行及格子（其他月份不受影响）；写 SCHEDULE_REMOVE 日志并通知参与人；完成后 `syncStage` 对账（全部月份无行 → 回退 PENDING）；返回删除的行数 |
| PUT | `/api/schedule/rows/{rowId}` | 该项目 PM | 保存排期行（`ScheduleRowSaveDTO`：cells 整行替换 + plannedEffort + updatedAt 乐观锁；工期同步全组）。返回新 updatedAt 与跨月顺延信息；完成后 `syncStage` 对账 |
| POST | `/api/schedule/rows/{rowId}/to-next-month` | 该项目 PM | **整个需求添加到下月**：该需求全部排期组（工种×负责人）逐组插入下月新行（格子空白、工期跨月共用）；下月表不存在自动创建；幂等；返回 {monthId, duplicated, insertedCount}；终态需求拒绝 |
| GET | `/api/schedule/incomplete` | 登录 | **排期未完成需求列表**（前端退出排期界面拦截用）：仅返回**当前用户为项目 PM** 的可修复需求（非终态、排期组未排满、且已有开发工种开工）；普通成员返回空不拦截 |

服务：`ScheduleServiceImpl` —— 全系统规则最复杂的模块（格子校验、同人同日限额、跨月顺延、写路径状态对账），详见 [06-key-classes.md](06-key-classes.md)。**本服务不直接写需求 status**，全部经 `RequirementStageService.syncStage` 收口。

## 6. 日历模块 CalendarController `/api/calendar`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/calendar?year=&month=` | 登录 | 查询整月日历（`CalendarMonthVO`，每天 {day,type,name}）。库中缺失的日期按周末规则推导回退（不落库） |
| PUT | `/api/calendar/{day}` | 管理员 | 维护某天类型（`CalendarUpdateDTO`：type=WORKDAY/WEEKEND/HOLIDAY/ADJUSTED，节假日可带名称，null 清空）；day 形如 `2026-09-01` |

服务：`CalendarServiceImpl`。实现 `ApplicationRunner`：启动时预生成 `[当月, 今天+12个月]` 缺失日期（按周末规则，幂等）。**可排班日 = WORKDAY + ADJUSTED**，是排期格子校验与跨月顺延判定的依据。

## 7. 文件模块 FileController `/api/files`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/files/upload` | 登录 | 上传附件（`multipart/form-data`，字段名 `file`）。白名单：图片 png/jpg/jpeg/gif/webp ≤10MB（IMAGE）；文档 pdf/doc/docx/xlsx/zip ≤50MB（DOC）。落盘 `{pm.upload-dir}/{yyyy}/{MM}/{uuid}.{ext}`，返回 FileVO{url,fileName,fileType,fileSize}，url 供需求附件/富文本内嵌图片引用 |

> 下载/预览不走业务接口：返回的 `/upload/...` 路径经 WebConfig 静态映射直接访问（前端 `window.open` 新窗口打开）。multipart 全局上限 60MB/65MB（略宽于业务白名单，超限返回业务 400「上传文件过大」）。

## 8. 通知模块 NotificationController `/api/notifications`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/notifications` | 登录 | 我的通知分页（page/size，id 倒序） |
| GET | `/api/notifications/unread-count` | 登录 | 未读数，返回 `{count}`（顶栏铃铛角标，前端 60s 轮询） |
| POST | `/api/notifications/read-all` | 登录 | 全部标记已读（条件更新 is_read=0→1） |

服务：`NotificationServiceImpl`。`send` 为同步落库（业务模块在自己事务内调用），content 超 300 字截断。

## 9. Bug 模块 BugController `/api/bugs`

「Excel 化编辑模型」：字段全部可空、单元格逐格部分更新、最后写入胜出（**无乐观锁**）。

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/bugs` | 登录 | 分页列表（query: **projectId 必选**、archived 0 总表/1 归档表、keyword 模糊匹配功能模块或编号、page/size，size 上限 1000 一页全量）。VO 含项目名、负责人/测试人/创建人/归档人姓名、问题截图列表（id 升序，第一张即缩略图）、myRole |
| POST | `/api/bugs` | 任何登录用户 | 创建 Bug（`BugCreateDTO`：仅 projectId）。**编号自动生成** `BUG-{缩写}-{4位流水}`（Redis INCR + DB 回填），测试人员=当前用户，其余字段留空由前端单元格编辑填充；返回新行完整 VO |
| PUT | `/api/bugs/{id}` | 任何登录用户（未归档行） | 逐格部分更新（`BugUpdateDTO`）。**清空哨兵**：null=不修改、字符串 `""`=清空、数值/ID 0=清空；统一 `LambdaUpdateWrapper` 显式 set（`updateById` 会忽略 null，无法表达清空）；处理进度值域 DONE/HOLD/SKIP、验收进度值域 PASSED/REJECTED（""=清空） |
| DELETE | `/api/bugs/{id}` | 任何登录用户（归档行也可删） | 物理删除行并**连带删除截图附件记录**（biz_type=BUG + fileType=IMAGE） |
| POST | `/api/bugs/{id}/archive` | 项目 PM 或管理员 | 归档（无状态限制，重复归档 400）。置 archived=1 + 归档时间/归档人 |
| POST | `/api/bugs/{id}/reopen` | 项目 PM 或管理员 | 重新打开：回总表（archived=0），**置空处理进度与验收进度**，并清空归档时间/归档人（显式 set null） |
| POST | `/api/bugs/{id}/images` | 任何登录用户（未归档行） | 批量追加问题截图（`BugImageAddDTO`，仅 fileType=IMAGE），逐张落附件表，返回新附件 id 列表（与入参顺序一致） |
| DELETE | `/api/bugs/{id}/images/{attachmentId}` | 任何登录用户（未归档行） | 删除单张截图（校验附件归属：biz_type=BUG + biz_id + IMAGE） |

服务：`BugServiceImpl`。要点：归档表行**只读**（update/addImages/deleteImage 校验 archived=0，但可删除）；列表 VO 装配全部 **in 批查 + 分组避免 N+1**；**会写操作日志**（CREATE/UPDATE/ARCHIVE/RESTORE/DELETE/ADD_IMAGE/DELETE_IMAGE），但**不发站内通知**（通知类型 `BUG_*` 仍为预留常量）。

## 10. 工作台模块 DashboardController `/api/dashboard`

个人视角只读汇总，任何登录用户；不做任何写操作。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/dashboard/summary` | 汇总卡片（`DashboardSummaryVO`）：未读通知数 / 我参与的非终态需求数 / 本自然月我的排期工时合计 / 我负责的待处理 Bug 数 |
| GET | `/api/dashboard/my-requirements` | 我参与的需求（`DashboardRequirementVO[]`：非终态、更新时间倒序、上限 20 条） |

服务：`DashboardServiceImpl`。口径：未读通知复用 `NotificationService.unreadCount`；本月工时 = `schedule_cell` 按排期行 `user=我` 聚合（排除 VOID），自然月；待处理 Bug = 我是负责人、未归档、`handle_status` 非 DONE/SKIP。

## 11. 统计报表模块 ReportsController `/api/reports`

只读聚合，全员可见。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/reports/requirement-status` | 需求状态分布（`ReportRequirementStatusVO[]`：项目 × 状态 × 个数，含终态） |
| GET | `/api/reports/workload?months=6` | 人员负载（`ReportWorkloadVO[]`：最近 months 个自然月（含当月）按 人员×月份 的排期工时合计；months 限 1~12，越界回落 6） |
| GET | `/api/reports/project-overview` | 项目概览（`ReportProjectOverviewVO[]`：成员数按人去重 / 非终态需求数 / 未归档 Bug 数） |

服务：`ReportsServiceImpl`。前端 `Reports.vue` 用 ECharts 渲染（环形图 + 堆叠柱 + 热力图 + 数据条表格）。

## 12. 全局搜索模块 SearchController `/api/search`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/search?keyword=` | 一次请求聚合四类结果，各类 LIMIT 5：需求（标题/编号，**全员可见、含终态**）、Bug（功能模块/编号，限用户可见项目）、项目（名称/缩写，管理员全量、普通用户仅参与或创建）、人员（姓名/用户名，仅公开字段） |

服务：`SearchServiceImpl`。可见性口径与各列表页一致；空 keyword 直接返回全空结果。

## 13. 操作日志模块 OperationLogController `/api/operation-logs`

**仅管理员与项目经理可见**：管理员查全部项目，项目经理仅查自己创建（`creator_id=自己`）的项目（越权 403）。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/operation-logs` | 分页查询（`bizType` / `action` / `operatorId` / `projectId` / `keyword`（模糊匹配 remark）/ `startDate` / `endDate` / page / size，size 上限 200）。VO 批量回填操作人姓名、业务编号/名称（REQUIREMENT/BUG/PROJECT 三类）、项目名，避免 N+1 |
| GET | `/api/operation-logs/operators` | 操作人下拉选项（可见范围内日志出现过的操作人去重，SQL 层 DISTINCT） |

服务：`OperationLogServiceImpl`。

## 14. 通知类型与操作日志常量

**通知类型 `common/NotifyTypes`（10 个）**：

| 类型 | 产生方 |
|------|------|
| `BUG_ASSIGNED` / `BUG_HANDLED` / `BUG_ACCEPTED` | Bug 模块（预留常量，当前 Bug 流程不发通知） |
| `REQ_PARTICIPANT_ADDED` / `REQ_PARTICIPANT_REMOVED` | 需求创建/编辑参与人 diff |
| `REQ_STATUS_CHANGED` | 状态派生对账（进入开发中/测试中/待归档/回退待排期） |
| `REQ_ARCHIVED` | 需求归档 |
| `REQ_CANCELLED` | 需求取消 |
| `SCHEDULE_CHANGED` | 排期格子实际变化 |
| `SCHEDULE_OVERFLOW` | 跨月顺延行生成 |

**操作日志 action 取值**（`biz_operation_log.action`，见 `init.sql` 注释）：`CREATE / UPDATE / DELETE / CANCEL / ARCHIVE / RESTORE / ADD_PARTICIPANT / REMOVE_PARTICIPANT / SCHEDULE_ADD / SCHEDULE_SAVE / SCHEDULE_REMOVE / SCHEDULE_COPY / NODE_DONE / STAGE_SYNC（状态对账）/ ADD_MEMBER / REMOVE_MEMBER / ADD_IMAGE / DELETE_IMAGE`，另有 Bug 预留 `HANDLE_DONE / ACCEPT_PASS / ACCEPT_REJECT`（未产生）。

## 15. Service 接口 → 实现对应关系

| 接口 | 实现 | 依赖的 Mapper / Service |
|------|------|------|
| AuthService | `AuthServiceImpl` | UserMapper, ProjectMapper |
| UserService | `UserServiceImpl` | UserMapper |
| ProjectService | `ProjectServiceImpl` | ProjectMapper, ProjectMemberMapper, UserMapper |
| RequirementService | `RequirementServiceImpl` | RequirementMapper, RequirementParticipantMapper, ProjectMapper, UserMapper, AttachmentMapper, BizOperationLogMapper, NotificationService, RequirementStageService, ScheduleMonth/RowMapper, StringRedisTemplate |
| RequirementStageService | `RequirementStageServiceImpl` | RequirementMapper, ScheduleRowMapper, RequirementParticipantMapper, BizOperationLogMapper, NotificationService |
| ScheduleService | `ScheduleServiceImpl` | ScheduleMonth/Row/CellMapper, RequirementMapper, RequirementParticipantMapper, ProjectMapper, UserMapper, BizOperationLogMapper, CalendarService, NotificationService, RequirementStageService |
| CalendarService | `CalendarServiceImpl` | CalendarDayMapper |
| FileService | `FileServiceImpl` | AttachmentMapper + FileStorageProperties |
| NotificationService | `NotificationServiceImpl` | NotificationMapper |
| BugService | `BugServiceImpl` | BugMapper, ProjectMapper, ProjectMemberMapper, UserMapper, AttachmentMapper, BizOperationLogMapper, StringRedisTemplate（**不依赖 RequirementStageService**，Bug 与需求排期无关） |
| DashboardService | `DashboardServiceImpl` | RequirementMapper, RequirementParticipantMapper, ProjectMapper, BugMapper, ScheduleCellMapper, NotificationService |
| ReportsService | `ReportsServiceImpl` | RequirementMapper, ScheduleCellMapper, ProjectMapper, ProjectMemberMapper, BugMapper |
| SearchService | `SearchServiceImpl` | RequirementMapper, BugMapper, ProjectMapper, ProjectMemberMapper, UserMapper |
| OperationLogService | `OperationLogServiceImpl` | BizOperationLogMapper, ProjectMapper, RequirementMapper, BugMapper, UserMapper |

> `ScheduleServiceImpl` 同时依赖 CalendarService（可排班日判定）、NotificationService（顺延/变更通知）与 RequirementStageService（状态对账），是模块协作的枢纽；`RequirementStageServiceImpl` 是**唯一写需求 status 的派生入口**（归档/取消除外）。所有写操作均带 `@Transactional(rollbackFor = Exception.class)`。
