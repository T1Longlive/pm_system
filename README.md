

# PM System

## 简介

PM System 是一套面向 IT 团队的项目管理系统，涵盖了**需求管理、研发排期、Bug 跟踪、项目协作**等核心业务场景。系统采用现代化的前后端分离架构，致力于通过结构化的数据模型（如需求状态机、月度排期格子）提升研发效能。

## 主要功能特性

1.  **需求全生命周期管理**：支持需求创建、指派、参与人管理、状态流转（待处理/就绪/开发/测试/归档）以及操作日志留痕。
2.  **可视化月度排期**：提供基于日历的排期视图，支持按工艺（FE/BE/QA 等）分配工时，处理排期溢出（Overflow）逻辑。
3.  **Bug 追踪与 Excel 化编辑**：支持 Bug 的全流程处理（受理、解决、验收），并提供类似 Excel 的批量处理模型。
4.  **项目与成员管理**：维护项目成员及角色（产品、前端、后端、测试等），支持灵活的权限控制。
5.  **站内通知系统**：针对需求状态变更、Bug 指派等关键事件推送站内信。
6.  **统一认证鉴权**：基于 JWT 的前后端分离认证架构。

## 技术栈

*   **后端**：Java 21, Spring Boot 3, MyBatis-Plus, Redis, MySQL
*   **前端**：Vue 3, Vue Router, Pinia, Axios
*   **文档**：详细的架构与开发文档位于 `docs/code-wiki/`

## 项目结构

```
pm-system/
├── backend/                # Spring Boot 后端项目
│   ├── src/main/java/com/pm/
│   │   ├── controller/     # REST API 控制器
│   │   ├── service/        # 业务逻辑层
│   │   ├── mapper/         # 数据访问层
│   │   ├── entity/         # 数据实体
│   │   ├── dto/            # 数据传输对象
│   │   ├── vo/             # 视图对象
│   │   └── security/       # 安全与认证组件
│   └── src/main/resources/
│       ├── application.yml # 配置文件
│       └── db/             # 数据库初始化脚本
├── frontend/               # Vue 3 前端项目
│   ├── src/
│   │   ├── api/            # 接口封装
│   │   ├── views/          # 页面组件
│   │   ├── components/     # 公共组件
│   │   └── stores/         # 状态管理
│   └── package.json
└── docs/code-wiki/         # 详细开发文档
```

## 快速开始

### 环境要求

*   JDK 21+
*   Node.js 18+
*   MySQL 8.0+ (建议 root/123456，或根据配置修改)
*   Redis (建议密码 123456)

### 1. 数据库初始化

创建数据库并导入初始化脚本：

```sql
CREATE DATABASE pm_system DEFAULT CHARACTER SET utf8mb4;
USE pm_system;
-- 导入 backend/src/main/resources/db/init.sql
```

### 2. 启动后端

后端默认监听端口 **8080**。

```bash
cd backend
# Windows
mvnw.cmd spring-boot:run
# Linux/macOS
./mvnw spring-boot:run
```

> **注意**：请确保 `application.yml` 中的数据库连接信息（用户名、密码）与本地环境一致。

### 3. 启动前端

前端默认监听端口 **5173**。

```bash
cd frontend
npm install
npm run dev
```

### 4. 访问系统

浏览器打开 [http://localhost:5173](http://localhost:5173)。

默认管理员账号：`admin`
默认管理员密码：`Pm@123456`

## 文档导航

*   **架构设计**：[docs/code-wiki/01-architecture.md](docs/code-wiki/01-architecture.md)
*   **后端模块与接口**：[docs/code-wiki/02-backend-modules.md](docs/code-wiki/02-backend-modules.md)
*   **数据库设计**：[docs/code-wiki/03-database-design.md](docs/code-wiki/03-database-design.md)
*   **安全与认证**：[docs/code-wiki/04-security-auth.md](docs/code-wiki/04-security-auth.md)
*   **部署指南**：[docs/code-wiki/08-run-deploy.md](docs/code-wiki/08-run-deploy.md)

## 许可证

本项目仅供学习与内部使用。