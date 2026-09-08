# 06 · 关键类与核心函数说明

聚焦系统内**最复杂、最值得理解**的实现。路径均相对 `backend/src/main/java/com/pm/`（后端）与 `frontend/src/`（前端）。

## 1. 启动与配置

### `PmApplication`（启动类）
- `@SpringBootApplication` + `@MapperScan("com.pm.mapper")` + `@EnableScheduling`。
- `main()` → `SpringApplication.run()`。

### `config/WebConfig`
- **CORS**：`/**` 全路径，`allowedOriginPatterns("*")`、允许 GET/POST/PUT/DELETE/PATCH/OPTIONS、任意头、`allowCredentials(true)`、预检缓存 3600s。
- **拦截器注册**：`JwtInterceptor` 拦截 `/api/**`，排除 `/api/auth/login`。
- **静态资源**：`/upload/**` → `file:{pm.upload-dir}/`（路径统一正斜杠并以 / 结尾，兼容 Windows）。

### `config/MybatisPlusConfig`
- 注册 `MybatisPlusInterceptor` + `PaginationInnerInterceptor(DbType.MYSQL)` 分页插件。

### `config/FileStorageProperties`
- `@ConfigurationProperties(prefix = "pm")`，字段 `uploadDir`（默认 `D:/pm-upload`）。

## 2. 认证与公共支撑

| 类 | 关键成员 | 说明 |
|----|------|------|
| `security/JwtUtil` | `generateToken(Long userId, String username)`、`parseToken(String token)` | HS256；subject=userId；有效期 = now + `jwt.expire` 分钟。解析失败抛异常由拦截器转 401 |
| `security/JwtInterceptor` | `preHandle()` | 校验 `Bearer` 头 → 解析 → `request.setAttribute("userId"/"username")`；失败输出 HTTP 200 + code 401 |
| `security/CurrentUser` | `getUserId(request)`、`getAdminUserId(request)` | 静态工具（@PostConstruct 注入静态 UserMapper）；后者查库校验 is_admin，非管理员抛 403 |
| `common/Result<T>` | `success()`、`success(T)`、`error(code,msg)` | 统一 `{code,message,data}`，成功 200 |
| `common/BizException` | `BizException(message)`、`BizException(code, message)` | 业务错误载体（默认 code 500），全局处理器转 HTTP 200 + 业务码 |
| `common/GlobalExceptionHandler` | `@RestControllerAdvice` | BizException/参数校验（400「参数错误：xxx」）/上传超限（400）/兜底（HTTP 500 + 日志） |
| `common/CraftConstants` | `PLANNER/FE/BE/OPS/QA/UI`、`ALL_CRAFTS`、`CRAFT_NAMES` | 六工种常量；**PM 不是工种** |
| `common/NotifyTypes` | 10 个通知类型常量 | 见 [02-backend-modules.md](02-backend-modules.md) 第 9 节 |

## 3. `RequirementServiceImpl` —— 需求状态机与编号生成

状态机（7 态）：`PENDING → READY → DEV → TEST → ARCHIVING → ARCHIVED`（终态），任意非终态 → `CANCELLED`（终态）。本类负责创建（PENDING）、编辑、取消、归档；其余流转在排期模块触发。

| 方法 | 签名要点 | 核心逻辑 |
|------|------|------|
| `generateReqNo` | 私有，`REQ-{缩写}-%04d` | Redis key `seq:req:{projectId}`；key 缺失时 `selectMaxSeq`（`MAX(CAST(RIGHT(req_no,4) AS UNSIGNED))`）经 `setIfAbsent` 回填，再 `increment` 取号 |
| `createRequirement` | `@Transactional`，返回需求 id | 权限=项目 PM 或 PLANNER 成员（403）；参与人按 `userId:craft` 去重且必须是该项目对应工种成员（400）；策划默认当前人；初始 PENDING；插入参与人并逐个发 `REQ_PARTICIPANT_ADDED`；附件落库；写 CREATE 日志 |
| `updateRequirement` | `@Transactional` | 权限=PM 或创建人；终态 400；**乐观锁**（dto.updatedAt 毫秒 vs 库值，不一致 409）；四段变更检测：①基础字段（null=不改）②参与人 diff（`applyParticipantDiff`：新增插入+通知，移除物理删除+通知）③附件 diff（非 null 全量替换）④有实际变更才 `updateById` 并用 `nextVersionTime()` 显式推进 updated_at（截断到秒且严格大于旧值）+ `buildUpdateSummary` 变更摘要日志（如「优先级：中→高；参与人 +张三(前端)」） |
| `cancelRequirement` | `@Transactional` | 仅 PM；ARCHIVED 400、CANCELLED 重复操作 400；置 CANCELLED + 取消三字段；写 CANCEL 日志；通知全部参与人+策划（LinkedHashSet 去重） |
| `archiveRequirement` | `@Transactional` | 仅 PM；仅 ARCHIVING 可归档；置 ARCHIVED；写 ARCHIVE 日志；通知参与人 |
| `pageRequirements` | — | 状态/优先级合法性校验；全局可见模型（不按成员过滤）；批量组装项目名/参与人/myRole |
| `writeLog` | 私有 | 需求日志恒 `round_no=1`，bizType=REQUIREMENT |

## 4. `ScheduleServiceImpl` —— 排期核心（全系统最复杂）

常量：行状态 `TODO/DONE/VOID`；开发类工种 `DEV_CRAFTS = {FE, BE, OPS}`；`EFFORT_MAX = 999.99`；可排班日 = `WORKDAY/ADJUSTED`。

| 方法 | 权限 | 核心逻辑 |
|------|------|------|
| `createMonth` | 管理员或任意项目 PM | 年月唯一（400「该月排期表已存在」） |
| `addRequirement` | 该需求项目的 PM | 需求必须 PENDING；同需求同月只生成一次；按 `requirement_participant` 逐行生成（一行=工种×负责人，status=TODO），`seq = 当月需求数+1`；**需求保持 PENDING**；返回行数（参与人为空返回 0） |
| `saveRow` | 该项目 PM | 见下方六步详解 |
| `doneRow` | 行负责人本人或 PM 代点 | 仅 TODO（重复完成/VOID 400）；置 DONE + doneAt/doneBy；写 NODE_DONE 日志；事务内 `checkAndAdvanceStatus` 并返回需求新状态 |
| `getMonthView` | 登录 | 月信息 + 整月日历（灰显依据）+ 当月全部行（含 VOID）按「工种+id」排序 → 按需求分组（GroupVO），groups 按 seq 升序；列全部 JOIN 派生不冗余存储 |
| `advanceReadyRequirements` | 定时任务调用 | 查 READY 且任一 FE/BE/OPS 行存在 `day <= today` 格子的需求，逐个条件更新 READY→DEV（**不通知**），返回推进数 |

### `saveRow` 六步详解（格子保存核心）

1. **校验链**：行必须 TODO → 乐观锁（dto.updatedAt 毫秒 vs 行 updated_at，不一致 409）→ `plannedEffort` 校验（可空、>0、≤2 位小数、≤999.99）→ `checkAndNormalizeCells` 格子校验。
2. **格子校验 `checkAndNormalizeCells`**：日期可解析且属于本月；必须可排班日（否则「X日为非工作日」）；单格 `0 < v ≤ 1` 且为 0.1 整数倍（BigDecimal：`v×10` 与最近整数差 ≤1e-8），规范化一位小数落库。
3. **同人同日限额 `checkUserDayTotal`**：SQL 统计该用户该日除本行外全部非 VOID 行格子合计，`day` 为绝对日期天然跨月对齐，合计 ≤1 否则 400。
4. **落库**：**全量替换**该行格子（先 delete 后逐格 insert，空 map=清空）；LambdaUpdateWrapper 更新 plannedEffort（null=清空）并显式推进 updated_at。
5. **跨月顺延 `checkAndCreateOverflow`**：条件 = plannedEffort 非空 且 当月格子合计 < 计划工期 且 当月剩余可排工作日为 0 → 下月表不存在则自动创建（跨年进位）→ 幂等检查（下月已有同需求+工种+负责人行不插）→ 插入 `is_overflow=1` 顺延行（planned_effort=剩余工期，格子空白）→ 通知负责人 `SCHEDULE_OVERFLOW`；返回 OverflowVO{year, month, carriedEffort}。
6. **联动与通知**：需求仍 PENDING 则条件更新 `advanceStatus(PENDING→READY)`（eq status 防并发，如期间被取消则不推进），成功写 SCHEDULE_SAVE 日志；格子与库中比对（compareTo 忽略精度）有实际变化 → 通知行负责人 `SCHEDULE_CHANGED`；返回新 updatedAt + overflow。

### 状态推进 `checkAndAdvanceStatus`

- 取该需求**所有月份**的全部非 VOID 行；DEV 或 READY 且全部开发类行（FE/BE/OPS）DONE → 推进 TEST（通知参与人；READY 也参与判定，避免定时任务推进前已全部完成导致卡死）；随后 TEST 且全部行 DONE → 推进 ARCHIVING（通知参与人，级联复查）。
- `advanceStatus` 用 `eq(status, expect)` 条件更新防并发覆盖。

## 5. 其他 Service 要点

| 类 | 关键方法/要点 |
|----|------|
| `AuthServiceImpl` | `login`：查用户 →「用户名或密码错误」统一提示（防探测）→ BCrypt.matches → 停用拦截 → 签发 token 组装 LoginVO；`changePassword`：校验原密码 → BCrypt 落库 → `forceChangePwd=0` |
| `UserServiceImpl` | 常量 `DEFAULT_PASSWORD="Pm@123456"`；`createUser`：用户名唯一校验、密码空则默认、`forceChangePwd=1`；`updateStatus`：不允许停用自己；VO 只映射白名单字段（密码绝不出参） |
| `ProjectServiceImpl` | `pageProjects`：管理员全量 / 普通用户「成员或创建者」过滤，成员数 `COUNT(DISTINCT user_id)` 去重、buildMyRole；`createProject`（@Transactional）：code 唯一、创建者=PM 不写 member；`addMember`（@Transactional）：工种合法性 + **增量合并**（仅插新工种行）；`removeMember`：带 craft 删单工种 / 不带删全部（TODO：不联动排期行） |
| `CalendarServiceImpl` | 实现 **ApplicationRunner**：启动时预生成 `[当月, 今天+12个月]` 缺失日期（周末规则，幂等）；`getMonthDays`：库中记录优先，缺失按周末推导回退（不落库），供排期校验与顺延判定；`updateDay`：类型校验，插入或更新，name=null 清空 |
| `FileServiceImpl` | 白名单：图片 png/jpg/jpeg/gif/webp ≤10MB（IMAGE）/ 文档 pdf/doc/docx/xlsx/zip ≤50MB（DOC）；文件名剥离路径、扩展名小写、截断 100 字符；落盘 `{upload-dir}/{yyyy}/{MM}/{32位uuid}.{ext}`；返回 url=`/upload/...` |
| `NotificationServiceImpl` | `send`：同步落库，content 超 300 字截断（`substring(0,299)+"…"`）；`unreadCount`/`markAllRead`：is_read 条件查询/更新 |
| `BugServiceImpl` | Excel 化编辑模型（详见第 5.1 节） |

### 5.1 `BugServiceImpl` —— Bug Excel 化编辑核心

「最后写入胜出」模型：**无乐观锁、无通知、无操作日志**，靠归档态约束写入。

| 方法 | 权限 | 核心逻辑 |
|------|------|------|
| `pageBugs` | 登录 | projectId 必选（400「请先选择项目」）；archived 仅 0/1；size 上限 1000；keyword 模糊匹配 feature 或 bugNo；`assembleVOs` 全部 **in 批查 + 分组**（截图/项目/用户名/myRole，避免 N+1），截图按 id 升序、第一张即缩略图 |
| `createBug` | 任何登录用户 | 仅需 projectId：生成 `BUG-{缩写}-%04d`、测试人=当前用户、archived=0，其余留空 |
| `updateBug` | 任何登录用户（未归档行） | **逐格部分更新**：进度值域校验 → `LambdaUpdateWrapper` 显式 set（null=不改、""=清空、0=清空 ID/数值）；**不用 `updateById`**（忽略 null 无法表达清空） |
| `archiveBug` / `reopenBug` | 项目 PM 或管理员 | 归档置 archived=1+时间/人（重复归档 400）；重新打开显式 set null 清归档三字段并**置空 handle_status / accept_status** |
| `deleteBug` | 任何登录用户（含归档行） | 物理删除行 + 连带删除该 Bug 的 IMAGE 附件记录 |
| `addImages` / `deleteImage` | 任何登录用户（未归档行） | 逐张插入（仅 IMAGE）返回 id 列表；删除校验附件归属（biz_type=BUG + biz_id + IMAGE，否则 404） |
| `generateBugNo` | 私有 | Redis `INCR seq:bug:{projectId}`，key 缺失时 `selectMaxSeq` 回填（SETNX 防竞态），与需求编号同套路 |

## 6. Mapper 自定义 SQL（其余为纯 BaseMapper）

| Mapper | 方法 | 用途 |
|------|------|------|
| `ScheduleRowMapper` | `countRequirementsInMonth` | 当月已有需求数 → 新需求 seq |
| | `selectUserDayTotal` | 同人同日已排合计（跨需求跨月） |
| | `selectReadyReqIdsWithDueDevCell` | READY 且有开发行日期 ≤ 今天的需求 id（定时任务用） |
| `RequirementMapper` | `selectMaxSeq` | `MAX(CAST(RIGHT(req_no,4) AS UNSIGNED))` 流水回填 |
| `BugMapper` | `selectMaxSeq` | `MAX(CAST(RIGHT(bug_no,4) AS UNSIGNED))` Bug 编号流水回填（COALESCE 0） |

## 7. 定时任务 `task/RequirementStatusTask`

- `@Scheduled(cron = "0 0 * * * *")` **每小时整点**执行 `advanceReadyRequirements()`：READY 且存在开发类（FE/BE/OPS）行最早已排日期 ≤ 今天 → 推进 DEV，**不发通知**；执行后 `log.info` 记录推进数。
- 由启动类 `@EnableScheduling` 启用。多实例部署需注意避免重复执行（见 [08-run-deploy.md](08-run-deploy.md)）。

## 8. 前端关键实现

| 文件 | 关键实现点 |
|------|------|
| `api/request.ts` | 双拦截器：请求带 Bearer token；响应按业务 code 分支（401 清 token 跳登录 / 非 200 统一 ElMessage 弹错）——页面无需重复处理错误 |
| `router/index.ts` | 四段守卫：无 token 重定向 → `fetchUserInfo` 回填 → `meta.admin` 拦截 → `forceChangePwd` 强制改密拦截（锁死在 /change-password） |
| `stores/user.ts` | token/userInfo/forceChangePwd 手动 localStorage 双写（与 request.ts 读 localStorage 一致）；`markPwdChanged()` 改密成功后清标记 |
| `views/module/Schedule.vue` | ① `mergeCells` 按 `GROUP_COL_INDEXES=[0,1,4,5,6]` 计算需求级纵向合并；② 日期列 `:key` 用**日号**而非完整日期（避免 vxe-table 动态列全量重建导致 staticColumns 收集错乱），数据加载后 `refreshColumn()` + `setMergeCells()`；③ 自动保存：`savingRows/dirtyRows` 两个 Set 实现「在途合并脏标记」，同行串行提交；④ 409 冲突整表刷新；⑤ 单格编辑状态 `rowId|day` 全局唯一 |
| `components/ProgressSteps.vue` | `currentIndex = STEP_STATUS.indexOf(status)` 驱动点亮；DEV 按参与人工种扇出分支卡；SVG Bézier 三类路径（merge/fan/straight）连接线；头像色板按 userId 取模稳定取色 |
| `components/RequirementFormDialog.vue` | 项目下拉按 myRole 过滤；参与人「工种→该工种成员」级联动态行；wangEditor `customUpload` 复用附件接口；编辑提交带 `updatedAt` 乐观锁，409 时 emit `conflict` |
| `views/module/Bugs.vue` | ① 单元格 DIY 编辑（照 Schedule.vue 模式）：`editingKey = 行id|字段名` 全局唯一，Enter/失焦提交、Esc 取消，textarea 自适应高度（v-focus 指令挂载聚焦）；② 清空哨兵：文本 '' / 数值与 ID 0，本地先置 null 再 PUT，失败回滚旧值；③ 截图管理弹窗：上传 + **窗口 paste 事件粘贴上传**（打开挂载/关闭移除），缩略图 = images[0]；④ 视频弹窗：上传（mp4/webm/mov ≤50MB）或粘贴外链，保存一次 PUT 双字段（videoUrl+videoName），名称兜底取 URL 尾段 |

## 9. 容易误改的「雷区」（源自代码注释与实现约定）

1. **排期已排工作量不落库**：`planned_effort` 只是计划值，实际已排 = `SUM(schedule_cell.value)`；跨月顺延按二者比较。不要在 `schedule_row` 上存实时工期。
2. **空格子不落库**：`schedule_cell` 只存已填的（行 × 工作日），一切统计基于现有格子集合。
3. **0.1 步进必须 BigDecimal 精确判断**：浮点误差（如 0.30000000000000004）会导致校验误判，落库前规范化为一位小数。
4. **状态推进必须条件更新**：`advanceStatus` 的 `eq(status, expect)` 保护不能删，否则与取消/归档并发时会覆盖终态。
5. **需求状态由排期事实驱动**：不要手动把需求从 READY/DEV 直接改成 TEST——必须经排期行完成事件触发，否则状态机逻辑被打断。
6. **乐观锁版本显式推进**：同秒内多次编辑需 `nextVersionTime()` 保证版本严格递增，直接依赖数据库时间可能导致同秒版本不变。
7. **PM 不写 project_member**：PM 身份 = `project.creator_id`，前端 `myRole` 中的 `PM` 由代码组装（创建者与工种可并存）。
8. **`updateById` 忽略 null 字段**：MyBatis-Plus 的固有行为——凡需「清空字段」的更新（Bug 逐格编辑、重新打开置空进度）必须用 `LambdaUpdateWrapper` 显式 `set(field, null)`。
9. **Bug 无乐观锁是有意设计**：Excel 化「最后写入胜出」模型，不要给 Bug 编辑补加 updatedAt 比对（与需求/排期的乐观锁是两套并存的并发策略）。
