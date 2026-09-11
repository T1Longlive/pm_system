# pm-system 项目 Code Wiki

> 基于 2026-09 最新代码逐一分析整理的工程知识库，面向新加入/接手本仓库的开发人员。
> 数据来源：`backend/`（Spring Boot 3.2.5，约 117 个 Java 源文件）与 `frontend/`（Vue 3 + TypeScript，约 39 个源文件）全部源码。
> 本次更新（2026-09-11）：同步「需求状态派生化重构」与新增的 工作台 / 统计报表 / 全局搜索 / 操作日志 四个模块，修正此前文档的滞后描述。

---

## 项目一句话概述

**pm-system 是一套前后端分离的内部「项目管理系统」**，覆盖研发管理主链路：**需求立项 → 月度排期排班 → Bug 跟踪（Excel 化表格编辑）→ 统计分析**，并内置项目/成员管理、人员管理、工作日历、站内通知、操作日志、全局搜索、工作台、附件上传、首次登录强制改密等支撑能力。

## 技术栈总览

| 层 | 技术 | 版本/说明 |
|------|------|------|
| 前端 | Vue 3 + TypeScript | Composition API + 严格模式 |
| 前端构建 | Vite | 5.x，`vue-tsc` 类型检查 |
| UI 组件库 | Element Plus | 2.14（全量注册 + 中文语言包） |
| 表格组件 | vxe-table / vxe-pc-ui | 4.x（排期矩阵页与 Bug 列表页局部引入） |
| 图表库 | ECharts | 6.x（**仅统计报表页 Reports.vue** 按需引入：Pie/Bar/Heatmap） |
| 富文本 | wangEditor | 需求描述图文混排 |
| 状态管理 | Pinia | 4.x（用户会话） |
| 路由 | Vue Router 4 | history 模式 + 路由级守卫 |
| HTTP | Axios | 统一实例 + 双拦截器 |
| 后端 | Spring Boot | 3.2.5（Java 17） |
| ORM | MyBatis-Plus | 3.5.5（spring-boot3-starter） |
| 数据库 | MySQL 8.x | `pm_system` 库，13 张表 |
| 缓存 | Redis | 需求/Bug 编号流水号生成 |
| 认证 | JWT（jjwt 0.11.5）+ BCrypt | 自定义拦截器，未引入完整 Spring Security |
| 构建 | Maven（仓库自带 3.9.16 + mvnw） | 阿里云镜像（根目录 `settings.xml`） |

## 目录结构

```
pm-system/
├── settings.xml                # Maven 阿里云镜像配置（mvn -s 指定使用）
├── .tools/apache-maven-3.9.16/ # 仓库内置 Maven
├── backend/                    # Spring Boot 后端
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd         # Maven Wrapper
│   └── src/
│       ├── main/java/com/pm/
│       │   ├── PmApplication.java   # 启动类（@MapperScan + @EnableScheduling）
│       │   ├── controller/          # REST 接口层（13 个 Controller）
│       │   ├── service/ (+impl)     # 业务逻辑层（14 个 Service 接口 + 14 个实现）
│       │   ├── mapper/              # MyBatis-Plus Mapper（13 个）
│       │   ├── entity/              # 实体类（13 张表）
│       │   ├── dto/ / vo/           # 入参 DTO（20 个，另 dto/ 中混有 2 个 VO）/ 出参 VO（28 个）
│       │   ├── config/              # 配置类（MyBatis 分页、Web+拦截器+静态映射、文件存储）
│       │   ├── security/            # JWT 工具、登录拦截器、当前用户助手
│       │   ├── common/              # 统一返回/业务异常/全局异常/常量
│       │   └── task/                # 定时任务（需求状态全量对账）
│       └── main/resources/
│           ├── application.yml      # 端口/数据源/Redis/JWT/附件目录
│           └── db/init.sql          # 建库建表（13 张）+ 种子管理员；另有 2 个 migrate-*.sql
└── frontend/                   # Vue 3 前端
    ├── package.json / vite.config.ts / tsconfig.json
    └── src/
        ├── main.ts / App.vue
        ├── router/index.ts          # 路由表（含 /change-password、/operation-logs）+ 全局守卫
        ├── stores/user.ts           # Pinia 会话（token/userInfo/forceChangePwd/isPm，localStorage 持久化）
        ├── api/                     # request.ts 统一实例 + 12 个领域 API
        ├── layout/MainLayout.vue    # 主布局（侧边栏/顶栏：全局搜索 + 通知轮询）
        ├── components/              # 4 个公共组件
        ├── constants/               # 工种、需求状态/优先级、Bug 处理/验收进度枚举
        └── views/                   # Login + ChangePassword + module/ 下 11 个业务页
```

## 文档导航

| 文档 | 内容 |
|------|------|
| [01-architecture.md](01-architecture.md) | 整体架构：分层结构、请求链路、核心业务流程、端口与外部组件 |
| [02-backend-modules.md](02-backend-modules.md) | 后端模块职责：13 个 Controller 全量接口清单 + Service 职责 |
| [03-database-design.md](03-database-design.md) | 数据模型：13 张表结构、ER 关系、需求状态机、编号规则 |
| [04-security-auth.md](04-security-auth.md) | 安全与鉴权：JWT 链路、三层权限模型、统一响应与错误码约定 |
| [05-frontend-modules.md](05-frontend-modules.md) | 前端模块：路由/守卫/状态/API 层/13 个页面/4 个公共组件 |
| [06-key-classes.md](06-key-classes.md) | 关键类与函数：后端核心类方法说明、前端关键实现、易踩的"雷区" |
| [07-dependencies.md](07-dependencies.md) | 依赖关系：Maven/npm 依赖明细、内部模块依赖图、前后端耦合点 |
| [08-run-deploy.md](08-run-deploy.md) | 运行方式：环境要求、数据库初始化、前后端启动、打包部署、自检清单 |
| [09-state-derivation.md](09-state-derivation.md) | **需求状态派生化**：stage 纯函数、写路径对账、定时任务、参与人联动 |

## 核心领域概念

系统围绕 4 个核心业务实体展开：

1. **项目（Project）**：研发单元，含 2-6 位大写字母缩写 `code`（编号前缀），**创建者即项目经理（PM）**。成员通过 `project_member` 挂载，按「工种」建行——一人可挂多个工种 = 多行。六个工种：策划 PLANNER / 前端 FE / 后端 BE / 运维 OPS / 测试 QA / UI（PM 是角色不是工种）。
2. **需求（Requirement）**：从属项目，编号 `REQ-{项目缩写}-{4位流水}`（Redis INCR 生成）。状态为**「排期数据 + 日期」的实时派生投影**（见 [09-state-derivation.md](09-state-derivation.md)），有效取值 `PENDING → DEV → TEST → ARCHIVING → ARCHIVED`（终态）或 `CANCELLED`（终态）。仅**归档 / 取消**是人工落库决定，其余阶段由 `RequirementStageService` 对账写入。
3. **排期（Schedule）**：月度排期表（`schedule_month`，一年一月一张）→ 排期行（`schedule_row`，需求 × 工种 × 负责人，由需求参与人生成）→ 排期格子（`schedule_cell`，按日填写工作量 0~1、0.1 步进，稀疏存储）。**排期组 = 同（需求, 工种, 负责人）跨全部月份的行集合**，工期组内共用、已排 = 组内跨月格子之和。计划工期超出当月可排日自动**跨月顺延**。
4. **Bug**：从属项目，编号 `BUG-{项目缩写}-{4位流水}`（Redis INCR 生成）。采用**Excel 化编辑模型**：进入页面先选项目，列表/归档表双 Tab 一页全量展示，所有登录用户可直接在单元格内编辑未归档行（最后写入胜出，无乐观锁）；归档/重新打开限项目经理或管理员（重新打开会清空处理与验收进度）；删除所有人可用。问题截图与视频以附件/字段形式挂在行上。**Bug 变更会写操作日志（不发站内通知）**。

## 快速开始

```bash
# 1. 初始化数据库（建库 + 13 张表 + 种子管理员 admin/Pm@123456）
mysql -uroot -p < backend/src/main/resources/db/init.sql

# 2. 启动后端（8080；依赖本机 MySQL root/123456、Redis 密码 123456，可在 application.yml 修改）
cd backend && ./mvnw spring-boot:run

# 3. 启动前端（5173）
cd frontend && npm install && npm run dev

# 4. 浏览器访问 http://localhost:5173，使用 admin / Pm@123456 登录
```

Windows 下后端启动命令为 `.\mvnw.cmd spring-boot:run`（网络受限时加 `-s ..\settings.xml` 走阿里云镜像）。详见 [08-run-deploy.md](08-run-deploy.md)。

## 当前实现状态速览（重要）

| 模块 | 状态 |
|------|------|
| 登录认证 / 人员管理 / 项目与成员管理 | ✅ 完整实现 |
| 需求管理（富文本、参与人、附件、日志、取消/归档） | ✅ 完整实现 |
| 排期管理（月矩阵、格子编辑、跨月顺延、状态派生对账、需求移除、退出拦截） | ✅ 完整实现（系统最复杂模块） |
| Bug 管理（Excel 化列表、归档表、截图/视频、逐格编辑、操作日志） | ✅ 完整实现 |
| 工作日历 / 站内通知 / 附件上传 | ✅ 完整实现 |
| 首次登录强制改密（登录标记 → 守卫拦截 → 改密页） | ✅ 完整实现 |
| **工作台 Dashboard**（个人汇总卡片 + 我参与的需求 + 最近通知 + 快捷入口） | ✅ 完整实现（后端 `/api/dashboard`） |
| **统计报表 Reports**（状态分布环形图、项目×状态堆叠柱、人员负载热力图、项目概览） | ✅ 完整实现（后端 `/api/reports`，前端 ECharts） |
| **全局搜索**（顶栏，需求/Bug/项目/人员四类聚合，各 5 条） | ✅ 完整实现（后端 `/api/search`） |
| **操作日志**（独立页，多条件筛选；管理员=全部项目，PM=自己创建的项目） | ✅ 完整实现（后端 `/api/operation-logs`） |
| 404 路由 | ⚠️ 未定义 catch-all 路由，未知路径渲染空白 |

## 阅读约定

- 文中文件路径相对仓库根目录；后端包根 `com.pm`，前端源码根 `frontend/src`。
- 接口统一响应体 `{code, message, data}`：成功 `code=200`；业务错误 HTTP 恒 200（除兜底 500），靠业务码区分（400/401/403/404/409/500）。
- 「PM」= 项目经理 = 项目创建者（`project.creator_id`），不是工种；需求/项目列表 VO 中的 `myRole: string[]` 含 `PM` 或工种码，空数组 = 只读。
- **需求 `status` 是派生值**：排期数据 + 日期 = 唯一真相源，写路径统一经 `RequirementStageService.syncStage` 对账；`READY` 不再作为存储阶段（前端筛选已移除，仅后端常量与历史兜底保留）。
