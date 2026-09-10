# 02 · 后端模块与接口职责

后端由 **9 个 Controller** 对外暴露 `/api/**` 接口，与前端 `frontend/src/api/*` 中的 9 个领域模块一一对应。所有接口统一返回 `Result{code, message, data}`；除 `/api/auth/login` 外均需 `Authorization: Bearer <token>`。

> 文中「权限」为业务规则层面的校验（实现在 Service 层）；「登录」= 通过 JWT 拦截器即可。

## 0. 模块总览

| Controller | 基础路径 | 对应 Service | 主要能力 |
|-----------|---------|-------------|----------|
| AuthController | `/api/auth` | AuthService | 登录、改密、当前用户信息 |
| UserController | `/api/users` | UserService | 人员管理（管理员）、用户下拉 |
| ProjectController | `/api/projects` | ProjectService | 项目 CRUD、成员（工种）管理 |
| RequirementController | `/api/requirements` | RequirementService | 需求全生命周期、操作日志 |
| ScheduleController | `/api/schedule` | ScheduleService | 月表、排期矩阵、行保存/完成 |
| CalendarController | `/api/calendar` | CalendarService | 工作日历查询与维护 |
| FileController | `/api/files` | FileService | 附件上传 |
| NotificationController | `/api/notifications` | NotificationService | 站内通知 |
| BugController | `/api/bugs` | BugService | Bug Excel 化列表、逐格编辑、归档表 |

## 1. 认证模块 AuthController `/api/auth`

| 方法 | 路径 | 入参 | 返回 | 说明 |
|------|------|------|------|------|
| POST | `/api/auth/login` | `LoginDTO`（username/password，@Valid） | `LoginVO` | **公开接口**（JWT 白名单）。校验密码与账号状态，签发 token，返回 token/userId/username/realName/isAdmin/forceChangePwd。「用户不存在」与「密码错误」统一提示防探测；停用账号提示「账号已停用」 |
| POST | `/api/auth/change-password` | `ChangePasswordDTO` | `Result<Void>` | 校验原密码 → BCrypt 新密码落库 → 清除 `forceChangePwd` 标记 |
| GET | `/api/auth/me` | — | `UserVO` | 当前登录用户信息 |

服务：`AuthServiceImpl`（`service/impl/AuthServiceImpl.java`）。

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
| GET | `/api/requirements` | 登录 | 分页列表（query: projectId/status/priority/plannerId/keyword）。**全局汇总视图，不按成员身份过滤**；keyword 模糊匹配编号或标题 |
| POST | `/api/requirements` | 项目 PM 或 PLANNER 工种成员 | 创建需求（`RequirementCreateDTO`），返回新需求 id。参与人必须是对应工种的项目成员；生成 `reqNo`；初始状态 PENDING；逐个通知参与人 |
| GET | `/api/requirements/{id}` | 登录 | 需求详情（`RequirementDetailVO`：基本信息 + 富文本 + 参与人平铺 + 附件 + 取消信息 + myRole） |
| GET | `/api/requirements/{id}/logs` | 登录 | 操作日志时间线（`OperationLogVO`，id 倒序） |
| PUT | `/api/requirements/{id}` | 项目 PM 或创建人 | 编辑需求（`RequirementUpdateDTO`）。**乐观锁**（updatedAt 毫秒，冲突 409）；终态不可编辑；参与人 diff、附件全量替换（非 null）、变更摘要留痕 |
| POST | `/api/requirements/{id}/cancel` | 项目 PM | 取消需求（`RequirementCancelDTO` 原因必填）。任意非终态可取消，不可逆；通知全部参与人+策划 |
| POST | `/api/requirements/{id}/archive` | 项目 PM | 归档需求。**仅 ARCHIVING 状态可归档**；通知全部参与人 |

服务：`RequirementServiceImpl`（状态机与编号生成核心，详见 [06-key-classes.md](06-key-classes.md)）。

## 5. 排期模块 ScheduleController `/api/schedule`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET | `/api/schedule/months` | 登录 | 月表列表（按年月升序） |
| POST | `/api/schedule/months` | 管理员或任意项目 PM | 创建月度排期表（`ScheduleMonthCreateDTO`），同年月唯一，已存在 400 |
| GET | `/api/schedule/months/{monthId}/view` | 登录 | 排期视图（`ScheduleViewVO`：月份 + 整月日历（控制列灰显）+ 按需求分组的排期矩阵行，每行含跨月已排合计 scheduledSum；VOID 行也返回供前端灰显） |
| POST | `/api/schedule/months/{monthId}/requirements` | 该需求项目的 PM | 将需求加入月表（`ScheduleRequirementDTO`）。需求必须 PENDING；同需求同月只生成一次；按参与人逐行生成排期行，返回生成行数 |
| PUT | `/api/schedule/rows/{rowId}` | 该项目 PM | 保存排期行（`ScheduleRowSaveDTO`：cells 整行替换 + plannedEffort + updatedAt 乐观锁；工期同步全组）。返回新 updatedAt 与跨月顺延信息 |
| POST | `/api/schedule/rows/{rowId}/to-next-month` | 该项目 PM | **整个需求添加到下月**：该需求全部排期组（工种×负责人）逐组插入下月新行（格子空白、工期跨月共用）；幂等；返回 {monthId, duplicated, insertedCount} |

服务：`ScheduleServiceImpl` —— 全系统规则最复杂的模块（格子校验、同人同日限额、跨月顺延、状态推进），详见 [06-key-classes.md](06-key-classes.md)。

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

服务：`BugServiceImpl`。要点：归档表行**只读**（update/addImages/deleteImage 校验 archived=0，但可删除）；列表 VO 装配全部 **in 批查 + 分组避免 N+1**；**不发通知、不写操作日志**（通知类型 `BUG_*` 与日志 action `HANDLE_DONE/ACCEPT_*` 仍为预留常量）。

## 10. 通知类型与操作日志常量

**通知类型 `common/NotifyTypes`（10 个）**：

| 类型 | 产生方 |
|------|------|
| `BUG_ASSIGNED` / `BUG_HANDLED` / `BUG_ACCEPTED` | Bug 模块（预留常量，当前 Bug 流程不发通知） |
| `REQ_PARTICIPANT_ADDED` / `REQ_PARTICIPANT_REMOVED` | 需求创建/编辑参与人 diff |
| `REQ_STATUS_CHANGED` | 排期节点完成 → 进入测试中/待归档 |
| `REQ_ARCHIVED` | 需求归档 |
| `REQ_CANCELLED` | 需求取消 |
| `SCHEDULE_CHANGED` | 排期格子实际变化 |
| `SCHEDULE_OVERFLOW` | 跨月顺延行生成 |

**操作日志 action 取值**（`biz_operation_log.action`）：`CREATE / UPDATE / CANCEL / ARCHIVE / ADD_PARTICIPANT / REMOVE_PARTICIPANT / SCHEDULE_SAVE / NODE_DONE`，另有 Bug 预留 `HANDLE_DONE / ACCEPT_PASS / ACCEPT_REJECT / RESTORE`。

## 11. Service 接口 → 实现对应关系

| 接口 | 实现 | 依赖的 Mapper / Service |
|------|------|------|
| AuthService | `AuthServiceImpl` | UserMapper |
| UserService | `UserServiceImpl` | UserMapper |
| ProjectService | `ProjectServiceImpl` | ProjectMapper, ProjectMemberMapper, UserMapper |
| RequirementService | `RequirementServiceImpl` | RequirementMapper, RequirementParticipantMapper, ProjectMapper, UserMapper, AttachmentMapper, BizOperationLogMapper, NotificationService, StringRedisTemplate |
| ScheduleService | `ScheduleServiceImpl` | ScheduleMonth/Row/CellMapper, RequirementMapper, RequirementParticipantMapper, ProjectMapper, UserMapper, BizOperationLogMapper, CalendarService, NotificationService |
| CalendarService | `CalendarServiceImpl` | CalendarDayMapper |
| FileService | `FileServiceImpl` | AttachmentMapper + FileStorageProperties |
| NotificationService | `NotificationServiceImpl` | NotificationMapper |
| BugService | `BugServiceImpl` | BugMapper, ProjectMapper, ProjectMemberMapper, UserMapper, AttachmentMapper, StringRedisTemplate |

> `ScheduleServiceImpl` 同时依赖 CalendarService（可排班日判定）与 NotificationService（状态/顺延/变更通知），并直接操作 RequirementMapper（状态推进），是模块协作的枢纽。所有写操作均带 `@Transactional(rollbackFor = Exception.class)`。
