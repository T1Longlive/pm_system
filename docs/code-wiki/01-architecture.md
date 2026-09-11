# 01 · 项目整体架构与技术栈

## 1. 架构总览

pm-system 采用经典的**前后端分离 B/S 架构**：Vue 3 单页应用（SPA）通过 REST API 调用 Spring Boot 后端；后端为**无状态 JWT 认证的分层架构**（Controller → Service → Mapper → MySQL/Redis）；附件文件存于本地磁盘并经静态资源映射对外访问；另有定时任务对全部非终态需求做**状态全量对账**（派生化，见第 4 节与 [09-state-derivation.md](09-state-derivation.md)）。

```
┌──────────────────────────────┐          ┌───────────────────────────────────────────┐
│      浏览器（Vue 3 SPA）        │  /api/** │            后端 Spring Boot (8080)          │
│ ┌──────────────────────────┐ │  Bearer  │ ┌───────────────────────────────────────┐ │
│ │ Vue3 + TS + Element Plus │ │  token   │ │ JwtInterceptor（/api/** 鉴权，白名单    │ │
│ │ ECharts（统计报表页）      │ │          │ │ /api/auth/login）→ userId 写入 request │ │
│ │ Pinia（会话） + Router 守卫 │ │          │ │        │                               │ │
│ │ Axios 统一拦截器           │ │          │ │        ▼                               │ │
│ └──────────────────────────┘ │          │ │ Controller（13 个，参数校验/读当前用户）   │ │
│        views/*（13 个页面）    │          │ │        ▼                               │ │
│        api/*（12 个领域模块）   │          │ │ Service/Impl（14 个，业务规则+事务）      │ │
└──────────────────────────────┘          │ │        ▼                               │ │
        │  Vite dev proxy                  │ │ Mapper（13 个 MyBatis-Plus）            │ │
        │  /api   /upload                  │ │        ▼                               │ │
        └─────────────────────────────────►│ │ MySQL(pm_system, 13 表) + Redis(流水号) │ │
                                           │ └───────────────────────────────────────┘ │
                                           │ @EnableScheduling：每小时 + 启动时全量对账  │
                                           │ RequirementStageService（状态派生）        │
                                           │ /upload/** → 本地磁盘 D:/pm-upload（静态）  │
                                           └───────────────────────────────────────────┘
```

### 运行时端口与地址

| 项 | 值 | 说明 |
|------|------|------|
| 前端开发服务 | `http://localhost:5173` | Vite dev server（`frontend/vite.config.ts`） |
| 后端服务 | `http://localhost:8080` | `server.port`（`backend/src/main/resources/application.yml`） |
| MySQL | `localhost:3306/pm_system` | root/123456 |
| Redis | `localhost:6379` db0 | 密码 123456，用于需求/Bug 编号流水号 |
| 附件存储 | `D:/pm-upload` | `pm.upload-dir` 配置，经 `/upload/**` 访问 |

### 请求代理

开发环境下 Vite 将两类请求代理到后端（`changeOrigin: true`）：
- `/api` → 后端业务接口
- `/upload` → 后端附件静态资源（图片直链、附件下载都走此路径）

生产环境由 Nginx 承担同样的代理职责（见 [08-run-deploy.md](08-run-deploy.md)）。

## 2. 后端分层架构

后端包根 `com.pm`，严格按职责分层，依赖方向自上而下：

| 层 | 包 | 职责 | 依赖方向 |
|------|------|------|------|
| 启动 | `PmApplication` | `@SpringBootApplication` + `@MapperScan("com.pm.mapper")` + `@EnableScheduling` | — |
| 表现层 | `controller`（13 个） | REST 接口定义、`@Valid` 参数校验、从 request 读取当前用户 | → service |
| 业务层 | `service` + `service.impl`（13 对） | 核心业务规则、`@Transactional` 事务、跨模块编排、通知发送、日志留痕 | → mapper、service（跨模块） |
| 数据访问 | `mapper`（13 个） | MyBatis-Plus `BaseMapper` 为主，少量自定义 SQL（跨月组聚合、流水回填、日总量、报表聚合等） | → entity |
| 领域模型 | `entity`（13 个） | 与表一一对应的实体（Lombok `@Data`） | — |
| 传输对象 | `dto`（24 个）/ `vo`（约 30 个） | 入参（Bean Validation 注解）/ 出参（不泄露密码等敏感字段） | — |
| 支撑配置 | `config`（3 个） | 分页插件、CORS + 拦截器注册 + 静态资源映射、附件目录属性 | — |
| 认证 | `security`（3 个） | JWT 签发/解析、登录拦截、当前用户/管理员校验 | → mapper |
| 公共 | `common`（4 个） | 统一返回 `Result`、业务异常 `BizException`、全局异常处理器、常量 | — |
| 定时 | `task`（1 个） | 每小时 + 启动时全量对账（`RequirementStageService.syncAllStages`） | → service |

13 个 Controller / Service 一览：Auth、User、Project、Requirement、Schedule、Calendar、File、Notification、Bug、**Dashboard**、**Reports**、**Search**、**OperationLog**（后 4 个为 2026-09 新增）。

**依赖原则**：上层依赖下层；Service 之间协作单向（排期 → 日历/通知/状态派生）；实体/DTO/VO 不反向依赖上层；全项目无循环依赖。

## 3. 前端架构

前端源码根 `frontend/src`，按「视图 → API → 统一请求实例」组织：

```
main.ts（Element Plus 全量注册 + 中文包、图标全局注册、Pinia、Router）
 └── App.vue → router/index.ts（14 条路由 + beforeEach 多段守卫）
                ├── layout/MainLayout.vue（侧边栏 + 顶栏：全局搜索、通知 60s 轮询、用户下拉）
                │     └── views/module/*.vue（11 个业务页）
                ├── views/{Login, ChangePassword}.vue（主布局外的独立页）
                ├── stores/user.ts（Pinia：token/userInfo/forceChangePwd/isPm，localStorage 持久化）
                └── views 全部经 api/*.ts（12 个领域模块）→ api/request.ts（axios 实例 + 拦截器）
公共组件：components/{ProgressSteps, RequirementFormDialog, ScheduleRequirementPicker, ScheduleFilterHeader}.vue
常量：constants/{craft, requirement, bug}.ts（与后端枚举手工对齐）
图表：views/module/Reports.vue 局部 `import * as echarts from 'echarts/core'`（按需注册 Pie/Bar/Heatmap）
```

## 4. 核心业务流程

### 链路 A —— 需求排期流（系统主链路）

```
管理员创建项目（创建者=PM）→ PM 添加项目成员（工种×人员，project_member）
        │
        ▼
PM/策划 创建需求（reqNo = REQ-{缩写}-{4位流水}，Redis 流水号）[PENDING 待排期]
        │  指定参与人（requirement_participant：工种×人员）、富文本、附件
        ▼
PM 创建月度排期表（schedule_month，年月唯一）
        │  「从需求池添加」→ 按参与人自动生成排期行（schedule_row：一行=工种×负责人）
        ▼
PM 在月矩阵上填格子（schedule_cell：日×工作量0~1）
        │  计划工期超出当月 → 自动跨月顺延 is_overflow 行
        │  （参与人在需求编辑中被增删时，同样联动已有排期的所有月份：新增补行 / 移除标 VOID）
        ▼
【状态派生】排期写路径末尾统一调 RequirementStageService.syncStage 对账：
        │  全部排期组（跨全部月份）排满 → DEV（开发中）；任一未排满 → PENDING（排期节点黄）
        │  排满后：开发窗口未结束 → DEV；非 QA 行全部结束 → TEST；全部行结束 → ARCHIVING
        ▼
定时任务每小时 + 启动时 syncAllStages 全量对账（捕捉纯时间流逝导致的变化，如格子日全部过去）
        │
        ▼
PM 手动归档 → ARCHIVED 已归档（终态）；任意非终态 PM 可取消 → CANCELLED（终态）
```

**关键点**：需求 `status` 不是"存储后维护"的物化字段，而是**「排期数据 + 日期」的实时投影**（纯函数 `deriveStage` 派生）。仅**归档 / 取消**是人工落库决定，其余阶段变化全部由统一对账写库，避免"忘记同步"类漏洞。详见 [09-state-derivation.md](09-state-derivation.md)。

关键联动：状态变更/关键操作统一写 `biz_operation_log` 留痕，并向相关用户发站内通知（`notification`，类型见 `common/NotifyTypes`）。

### 链路 B —— Bug 流程（Excel 化编辑模型，已实现）

Bug 不走需求那套状态机，而是**类 Excel 表格**的轻量协作模型：

```
进入 Bug 管理 → 先选项目（必选，未选显示引导页）
        │
        ▼
「+ 新增 Bug」→ 后端生成编号 BUG-{缩写}-{4位流水}（Redis INCR）、测试人员=当前用户
        │  新行置顶，其余字段留空，全部通过单元格直接编辑填充
        ▼
所有登录用户在单元格内逐格编辑未归档行（文本/下拉/截图/视频，最后写入胜出，无乐观锁）
        │  处理进度：未处理 → DONE 已处理 / HOLD 暂不处理 / SKIP 不处理
        │  验收进度：未验收 → PASSED 通过 / REJECTED 不通过
        ▼
PM 或管理员归档 → 进归档表（只读，可删除）；重新打开 → 回总表并清空处理/验收进度
```

注意：Bug 模块**会写操作日志**（CREATE/UPDATE/ARCHIVE/RESTORE/DELETE/ADD_IMAGE/DELETE_IMAGE），但**不发站内通知**（`NotifyTypes` 中的 `BUG_*` 仍为预留常量，无产生方）。

## 5. 角色与权限模型（三层）

| 角色 | 判定依据 | 权限范围 |
|------|------|------|
| 系统管理员 | `user.is_admin=1` | 人员管理、创建项目、创建月表、维护工作日历、跳过项目可见性过滤、操作日志全量可见 |
| 项目经理 PM | `project.creator_id` = 当前用户 | 编辑项目、成员增删、创建/编辑/取消/归档需求、排期（建月表、加/移需求、填格子）、操作日志查看自己项目 |
| 需求创建人 | `requirement.created_by` | 编辑自己创建的需求（与 PM 并集） |
| 普通成员 | `project_member` 有记录 | 查看参与的项目；作为需求参与人完成自己的排期行；需求 `myRole` 为空数组即只读 |

前端通过路由 `meta.admin`（管理员页）+ `myRole` 控制按钮显隐、`userInfo.isPm` 控制「操作日志」菜单显隐；后端在各 Service 内用 `CurrentUser` 做细粒度校验（PM 判定、本人判定、状态可操作域）。登录接口直接返回 `isPm` 标志（是否任一项目创建者），供菜单显隐，避免前端拉列表猜测。详见 [04-security-auth.md](04-security-auth.md)。

## 6. 关键横切设计

| 机制 | 实现方式 | 位置 |
|------|------|------|
| 统一响应 | `Result{code,message,data}`，成功 200 | `common/Result` |
| 业务异常 | `BizException(code,message)`，全局处理器转 `Result.error`（HTTP 200） | `common/BizException` + `GlobalExceptionHandler` |
| 乐观锁 | 前端回传 `updatedAt`（毫秒时间戳），服务端比对，不一致返回 409 | 需求编辑、排期行保存 |
| 并发防护 | 状态对账用 `UPDATE ... WHERE status=旧值` 条件更新 | `RequirementStageServiceImpl.applyIfChanged` |
| 编号生成 | Redis `INCR seq:req:{projectId}` / `seq:bug:{projectId}`，key 缺失时从 DB `MAX(CAST(RIGHT(xxx_no,4)))` 回填（SETNX 防竞态） | `RequirementServiceImpl.generateReqNo` / `BugServiceImpl.generateBugNo` |
| 操作留痕 | 统一写 `biz_operation_log`（bizType+bizId+projectId+action+remark，需求恒 round_no=1） | 各 ServiceImpl（含 Bug 模块） |
| 站内通知 | `NotificationService.send` 同步落库，content 截断 300 字 | 各 ServiceImpl（Bug 模块暂未接入） |
| 状态派生 | stage 纯函数 `deriveStage` 由排期数据+日期派生；写路径 `syncStage` 实时对账，定时任务 `syncAllStages` 兜底 | `RequirementStageService` |
| 定时任务 | `@Scheduled(cron="0 0 * * * *")` 每小时整点 + `ApplicationReadyEvent` 启动时各对账一次（实时变更已在写路径完成，此处只兜底时间流逝） | `task/RequirementStatusTask` |

## 7. 技术栈组件速查

| 组件 | 用途 |
|------|------|
| Spring Boot 3.2.5 | Web MVC、事务、`@EnableScheduling`、参数校验 |
| MyBatis-Plus 3.5.5 | 单表 CRUD、LambdaQueryWrapper、分页插件（MySQL 方言） |
| MySQL 8.x | 持久化（13 张表，无物理外键，靠唯一键+索引关联） |
| Redis | 需求/Bug 编号流水号 |
| jjwt 0.11.5 | HS256 无状态 JWT，有效期 7200 分钟（5 天） |
| spring-security-crypto | 仅 BCrypt 密码哈希（未引入完整 Spring Security） |
| Element Plus 2.14 | 前端 UI 组件库（全量注册 + zhCn） |
| ECharts 6.x | 统计报表图表（环形图/堆叠柱/热力图；Reports.vue 按需引入 core + charts + components） |
| vxe-table 4.x | 排期月矩阵（动态日列、单元格合并）与 Bug 列表（逐格编辑），仅 Schedule.vue / Bugs.vue 局部引入 |
| wangEditor 5 | 需求描述富文本（内嵌图片复用 `/api/files/upload`） |
