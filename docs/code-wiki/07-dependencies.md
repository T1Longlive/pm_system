# 07 · 依赖关系

## 1. 后端 Maven 依赖（`backend/pom.xml`）

父工程 `spring-boot-starter-parent` **3.2.5** 统管版本；`java.version=17`。

| 依赖 | 版本 | 作用域 | 用途 |
|------|------|--------|------|
| spring-boot-starter-web | BOM | compile | Web MVC + REST |
| spring-boot-starter-validation | BOM | compile | `@Valid` / `@NotBlank` 参数校验 |
| mybatis-plus-spring-boot3-starter | 3.5.5 | compile | ORM：BaseMapper、条件构造器、分页插件（Spring Boot 3 专用 starter） |
| mysql-connector-j | BOM | runtime | MySQL 驱动 |
| spring-boot-starter-data-redis | BOM | compile | 需求/Bug 编号流水号（`StringRedisTemplate`） |
| spring-security-crypto | BOM | compile | **仅 BCrypt** 密码哈希（刻意未引入完整 Spring Security） |
| jjwt-api | 0.11.5 | compile | JWT API（编译期） |
| jjwt-impl | 0.11.5 | runtime | JWT 实现 |
| jjwt-jackson | 0.11.5 | runtime | JWT JSON 序列化 |
| lombok | BOM | optional | 编译期简化 POJO |
| spring-boot-starter-test | BOM | test | 测试（仅一个上下文加载冒烟测试） |

**构建插件**：`spring-boot-maven-plugin`（打包时排除 Lombok）。

**构建配置**：
- 仓库根 `settings.xml`：将 `central` 镜像到**阿里云公共仓库**（`https://maven.aliyun.com/repository/public`），用法 `mvn -s d:\IDEA_Project\pm-system\settings.xml <goal>`。
- `backend/.mvn/wrapper/`：Maven Wrapper；仓库另自带完整 Maven 3.9.16 于 `.tools/apache-maven-3.9.16/`。

## 2. 后端内部模块依赖

```
task(RequirementStatusTask, 每小时+启动) ──► RequirementStageService.syncAllStages()
controller(13) ──► service 接口(14) ──► service.impl(14) ──► mapper(13) ──► MySQL / Redis
                                        │
                                        ├──► CalendarService（排期判定可排班日）
                                        ├──► NotificationService（状态/顺延/变更通知）
                                        └──► OperationLogService（操作审计埋点）
common / config / security / entity / dto / vo ←── 被各层依赖（自身不依赖上层）
```

- **协作枢纽**：`ScheduleServiceImpl` 依赖 CalendarService（可排班日判定）、NotificationService（通知）、`RequirementStageService`（状态对账）、`OperationLogService`（审计）——它是排期与需求状态机的「连接器」。**注意方向**：排期写完后调 `syncStage` 反推需求状态，而不是反过来。
- **`RequirementStageService`** 是新的状态中枢：被 `ScheduleServiceImpl`（保存/删需求/添加需求后对账）、`RequirementServiceImpl`（参与人变更后对账）以及定时任务共同调用；它本身只读排期数据 + 条件写 `requirement.status`。（`BugServiceImpl` **不**调用它——Bug 与需求排期是两条线。）
- **Service 间协作全部单向**，无循环依赖；`BugServiceImpl` 只依赖 Mapper + Redis + BizOperationLogMapper（不依赖其他业务 Service）。
- Mapper 层：`ScheduleRowMapper`（6 个自定义方法）、`RequirementMapper`（1 个）、`BugMapper`（1 个）有自定义 SQL，其余为纯 `BaseMapper`。
- 特殊点：`CurrentUser`（security 包）静态持有 `UserMapper`（@PostConstruct 注入）做管理员校验。

## 3. 前端 npm 依赖（`frontend/package.json`）

name `pm-system-frontend`，private，`type: "module"`。scripts：`dev`（vite）、`build`（`vue-tsc --noEmit && vite build`，先全量类型检查）、`preview`。

**dependencies**：

| 包 | 版本 | 用途 |
|------|------|------|
| vue | ^3.4.0 | 框架 |
| vue-router | ^4.6.4 | 路由（history 模式 + 守卫） |
| pinia | ^4.0.3 | 状态管理（仅一个 user store） |
| element-plus | ^2.14.5 | UI 组件库（main.ts 全量注册 + zhCn） |
| @element-plus/icons-vue | ^2.3.2 | 图标（全局逐个注册） |
| axios | ^1.20.0 | HTTP（统一实例 + 双拦截器） |
| @wangeditor/editor + @wangeditor/editor-for-vue | ^5.1.23 / ^5.1.12 | 需求描述富文本（内嵌图片复用附件上传） |
| vxe-table + vxe-pc-ui | ^4.21.5 / ^4.17.28 | 高性能表格（**排期页 Schedule.vue 与 Bug 页 Bugs.vue 局部引入**） |
| **echarts** | **^6.1.0** | **看板/报表图表（2026-09 新增，Dashboard.vue / Reports.vue 使用）** |

**devDependencies**：vite ^5.4.21、@vitejs/plugin-vue ^5.2.4、typescript ~5.9.3、vue-tsc ^3.3.11、@types/node ^26.4.1。未配置 ESLint/Prettier/测试框架。

**前端内部依赖方向**：

```
main.ts ──► router/index.ts（守卫 ──► stores/user.ts ──► api/auth.ts）
views/* ──► api/*（12 个领域模块）──► api/request.ts（axios 实例）
views/module/Schedule.vue ──► components/{ScheduleRequirementPicker, ScheduleFilterHeader}.vue
views/{Requirements, RequirementDetail}.vue ──► components/{RequirementFormDialog, ProgressSteps}.vue
views/{Dashboard, Reports}.vue ──► echarts（按需引入图表）
views/OperationLogs.vue ──► api/operationLog.ts
views/module/Bugs.vue ──► api/{bug, project, file}.ts + constants/bug.ts
constants/* ──► 被 views/components 引用（状态/工种/Bug 进度文案与颜色）
```

## 4. 前后端耦合点（联调契约）

| 前端 | 后端 | 契约要点 |
|------|------|------|
| `vite.config.ts` 代理 `/api`、`/upload` → 8080 | `WebConfig` 静态映射 `/upload/**` | 开发期同源，生产由 Nginx 承担 |
| `api/request.ts` 读取 `code` | `Result{code,message,data}` | 成功 200；401 跳登录；其余统一弹错；409 乐观锁由页面特殊处理 |
| `api/*.ts` 函数 | **13 个 Controller** | 路径一一对应（见 [02-backend-modules.md](02-backend-modules.md)）；新增 dashboard / reports / search / operationLog 四个模块 |
| `constants/craft.ts` | `common/CraftConstants` | 六工种码**手工同步**（无自动生成机制） |
| `constants/requirement.ts` 状态/优先级 | `requirement.status`（派生投影）/`priority` 取值 | 派生态集合（PENDING/DEV/TEST/ARCHIVING/ARCHIVED/CANCELLED）、1/2/3 优先级；**前端不做状态推导，直接渲染后端返回值** |
| `constants/bug.ts` 处理/验收进度 | `bug.handle_status/accept_status` 取值 | DONE/HOLD/SKIP、PASSED/REJECTED（手工同步） |
| `updatedAt` 毫秒时间戳回传 | 乐观锁比对（需求编辑、排期行保存） | 不一致返回 409（**Bug 逐格编辑不走乐观锁**） |
| `myRole: string[]` | Service 组装（PM=创建者 + 工种） | 空数组 = 只读；前端 `user store.isPm` 由此派生 |
| `PageResult<T>{records,total,current,size}` | MyBatis-Plus `Page` 序列化 | 通用分页结构 |
| 通知跳转 `bizType==='REQUIREMENT'` | `notification.biz_type/biz_id` | BUG 类通知暂无产生方，跳转分支未接入 |
| 操作日志 `action` 枚举 | `biz_operation_log.action` | 前端映射中文标签；含新增的 `STAGE_SYNC`（系统派生状态留痕） |

## 5. 外部运行时依赖

| 中间件/环境 | 配置 | 用途 |
|------|------|------|
| MySQL 8.x | `localhost:3306/pm_system`（root/123456） | 持久化，13 张表 |
| Redis | `localhost:6379` db0（密码 123456） | 需求/Bug 编号流水号（INCR） |
| JDK | 17+（编译目标 17） | 后端运行 |
| Node.js + npm | 18+ | 前端开发/构建 |
| 本地磁盘 | `D:/pm-upload`（`pm.upload-dir`） | 附件存储（目录自动创建） |

## 6. 预留/未闭环项

- **Bug 通知仍缺产生方**：`NotifyTypes` 中 `BUG_*` 三类型无调用点——Bug 有意不发通知（高频编辑噪声）。但 Bug **操作日志已闭环**（CREATE/UPDATE/ARCHIVE/RESTORE/DELETE/ADD_IMAGE/DELETE_IMAGE 全部落 `biz_operation_log`，`biz_type=BUG`）。
- `attachment.biz_type='BUG'` 已投入使用（问题截图/视频）；`notification` 表的 BUG 多态仍未使用。
- `schedule_row.done_at/done_by` 两列**已废弃**（状态派生化后「行完成」不再是独立事件），保留列但不再写入，见 [03-database-design.md](03-database-design.md)。
- `biz_operation_log.project_id` 为后加列 + `idx_project` 索引，支撑 PM「只看自己项目日志」的范围查询。
- `spring-boot-starter-data-redis` 也被注释标注用于「统计缓存」，当前仅编号流水号在用。
- 通知 90 天清理：代码注释明确标注 TODO，清理任务未实现。
