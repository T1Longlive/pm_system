# pm-system DevOps 落地路线图

> 目标:以本项目为载体,从零建立一套**可运行、可验证**的 DevOps 工作流,而不是堆概念。
> 定位:学习驱动 + 真实可用。每一阶段都要有能亲眼看到的产出,而不是"配好了但没跑过"。

---

## 1. 现状体检

对仓库做了一次实际盘点(截至 2026-09-11),结果如下:

| 项 | 现状 | 影响 |
|----|------|------|
| 远程仓库 | `gitee.com/t1longlive/pm-system.git`,分支 `main`,共 3 次提交 | CI 平台应选 **Gitee Go**(原生集成、免费),而非 GitHub Actions |
| 工作区状态 | **32 个文件已修改、10 个文件未跟踪**(含新增 OperationLog 模块) | 工作区"脏",无稳定提交可构建 —— CI 无从谈起 |
| 密钥管理 | `backend/src/main/resources/application.yml` 被 git 跟踪,内含 MySQL/Redis 密码与 `jwt.secret` | 违反 12-Factor 配置原则;CI/容器化后无法按环境注入 |
| 自动化程度 | 无 Dockerfile、无 CI 配置、无部署脚本 | 部署全靠 [`08-run-deploy.md`](code-wiki/08-run-deploy.md) 的手工步骤 |
| 测试覆盖 | 仅 `PmApplicationTests` 一个上下文冒烟测试 | CI 中"运行测试"这一步目前是空的 |
| 外部依赖 | MySQL 8、Redis、附件目录 `D:/pm-upload` | 容器化时必须显式处理(卷、网络、配置) |
| 构建方式 | 后端 Maven Wrapper + 根目录 `settings.xml`(阿里云镜像);前端 Vite + `vue-tsc` 类型检查 | CI 中需还原镜像源与 Node 环境 |
| 构建产物 | `backend/target/pm-backend-1.0.0-SNAPSHOT.jar`、`frontend/dist/` | 制品的定义已天然存在 |

**结论**:项目代码组织结构良好(未提交 `node_modules`/`target`),但**运维工程是空白**。起点清晰,适合逐级建设。

---

## 2. 业界参考

选取了几类可对照的真实案例:

| 来源 | 可借鉴的点 |
|------|-----------|
| [Gitee 官方 · 主机部署插件](https://help.gitee.com/enterprise/pipeline/plugin/host-deploy) | 流水线产物为 `tar.gz` 制品,通过 SSH 下发到主机后执行部署脚本。官方提供 [Spring Boot deploy.sh 示例](https://gitee.com/gitee-go/spring-boot/blob/master/deploy.sh)。免费额度:200 分钟/仓库/月 |
| [Gitee Go 使用教程](https://www.kuazhi.com/post/716497737.html) | 配置文件放在 `.workflow/` 下;默认生成 `MasterPipeline.yml`/`BranchPipeline.yml`/`PRPipeline.yml`;支持 Push / PR / 定时三种触发 |
| [1Panel + Jenkins + Docker 流水线设计](https://blog.csdn.net/weixin_28700593/article/details/160109539) | 中小企业主流形态:后端 Maven、前端 Node 分阶段构建,统一容器化后部署 |
| [Spring Boot + Vue 合并构建方案](https://blog.csdn.net/zp357252539/article/details/147121221) | 两种部署模型:①前后端独立容器(Nginx + API);②Vue `dist` 打进 Spring Boot 静态资源,单镜像交付。后者适合小团队 |
| [GitHub: springboot-aws-github-actions-cicd](https://github.com/saadmdsabah/springboot-aws-github-actions-cicd) | 企业级范式:test/build 并行 → 打包 → 制品传递 → 多阶段镜像 → 推送仓库 → 滚动更新。反复强调密钥一律走 Secrets |
| [DevOps 学习路线(多源共识)](https://devopsschool.org/blog/build-and-track-devops-skills-with-projects-that-impress-recruiters) | 阶段顺序:**Git/Linux → CI/CD → Docker → K8s/IaC → 可观测性**。核心提醒:项目驱动优于考证;不要把 K8s 放在第一步 |

**三条最重要的共识**:
1. **CI 是 DevOps 的起点**,不是 Docker,更不是 K8s。
2. **每个阶段都要有可验收的产出**,否则学完就忘。
3. **配置与密钥外置**是所有后续环节(CI、容器、多环境)的前提。

---

## 3. 目标架构

### 3.1 流水线

```
开发者 push / PR 合并
        │
        ▼
┌───────────────────────────┐
│  Gitee Go 流水线           │
│  ① 后端:mvn clean verify   │
│  ② 前端:npm ci + build     │
│  ③ 打包制品:jar + dist     │
└───────────────────────────┘
        │  制品(tar.gz,带版本号)
        ▼
┌───────────────────────────┐
│  主机部署(SSH)            │
│  上传 → 停旧 → 启新        │
│  → 健康检查 → 失败回滚     │
└───────────────────────────┘
        │
        ▼
┌───────────────────────────┐
│  云主机 (Docker Compose)   │
│  Nginx(:80) ─┬─ 前端静态   │
│              └─ /api 反代  │
│  后端(:8080)               │
│  MySQL(:3306) Redis(:6379) │
│  upload 卷 → 持久化磁盘    │
└───────────────────────────┘
```

### 3.2 部署模型二选一(需决策)

| 模型 | 做法 | 优点 | 缺点 |
|------|------|------|------|
| **A. 分离部署** | 前端 Nginx 容器 + 后端 Spring Boot 容器 | 贴近生产实践;可独立扩缩;能学到反向代理与跨容器网络 | 多一层 Nginx 配置,概念更多 |
| **B. 合并交付** | `frontend/dist` 拷入后端静态资源,打成单个 jar/镜像 | 部署极简,一个进程一个端口 | 前后端耦合发布;少了 Nginx 这一课 |

> 建议:**学习目的选 A**(能覆盖更多知识点),**追求极简选 B**。两者不冲突,可先 A 后 B 对比。

---

## 4. 分阶段实施计划

### 阶段 0 · 版本控制与配置规范

**目标**:让仓库处于"随时可重复构建"的状态,并把配置从代码里剥离。

| 动作 | 说明 |
|------|------|
| 提交存量变更 | 把当前 32 改 + 10 新增按逻辑拆成 2~3 个提交(如:后端 OperationLog 模块、前端页面、文档更新),不要一个巨型提交 |
| 修订 `.gitignore` | 补充:`.workbuddy/`、`.mimode/`、`*.class`、`backend/logs/`、`frontend/dist/`(已含)等 |
| 配置外置(12-Factor) | `application.yml` 中的敏感值改为占位符 `${DB_PASSWORD}` / `${REDIS_PASSWORD}` / `${JWT_SECRET}`;真实值放 **未跟踪** 的 `application-local.yml`(提供 `.example` 模板) |
| 分支策略 | 建议:`main` 仅接受 PR 合并(受保护);日常在 `feature/xxx` 短分支开发。即"简化版 trunk-based" |
| 补 `README.md` | 项目简介 + 一键启动命令 + 指向 `docs/code-wiki/` |

**产出**:干净的 git 历史、可提交的 `.gitignore`、配置模板文件
**学习点**:Git 分支/PR/Rebase、**12-Factor App 之 Config**、密钥不入库
**验收**:在一台"没有出过 application-local.yml"的机器上 `git clone` 后,能通过环境变量启动服务

> ⚠️ 注意:`application.yml` 的密钥已存在于 git 历史中。**要彻底清除需重写历史**(`git filter-repo`)并轮换密钥。对本地学习项目可暂不处理,但要知道这是真实项目里的必做项。

---

### 阶段 1 · 持续集成 CI

**目标**:每次 push 自动构建 + 校验,失败立即暴露。

| 动作 | 说明 |
|------|------|
| 先补测试 | 当前几乎没有测试。**首选**为 `RequirementStageService.deriveStage` 写单元测试 —— 它是纯函数(输入需求+排期组+日期 → 输出状态),无 DB 依赖,最容易见效 |
| 编写流水线 | 新建 `.workflow/MasterPipeline.yml`;两个并行任务:后端 `mvn -s settings.xml clean verify`、前端 `npm ci && npm run build`(含 `vue-tsc` 类型检查) |
| 处理镜像源 | CI 环境需与 `settings.xml` 的阿里云镜像一致,否则依赖拉取超时 |
| 产出制品 | jar 与 dist 打包为制品,带构建号/时间戳 |
| 状态徽章 | 把流水线状态徽章挂到 `README.md` |

**产出**:`.workflow/MasterPipeline.yml`、若干单元测试、绿色的流水线
**学习点**:CI 概念、流水线即代码(Pipeline as Code)、制品artifact、fail-fast、测试金字塔
**验收**:故意提交一个编译不过/测试失败的改动,确认流水线**拦住**了它

---

### 阶段 2 · 容器化 Docker

**目标**:消灭"在我机器上能跑"。

| 动作 | 说明 |
|------|------|
| 后端镜像 | 多阶段 Dockerfile:`maven:3.9-eclipse-temurin-17` 构建 → `eclipse-temurin:17-jre`(建议 alpine)运行;设时区 `Asia/Shanghai` |
| 前端镜像 | 多阶段:`node:22-alpine` 构建 → `nginx:alpine` 托管 `dist` |
| 编排 | `docker-compose.yml` 拉起 mysql + redis + backend + frontend;MySQL 初始化挂载 `init.sql` |
| 卷与配置 | `pm-upload` 改挂具名卷;MySQL 数据卷;密码经 `.env` 注入 |
| 忽略文件 | `.dockerignore` 排除 `node_modules`、`target`、`.git` —— 否则构建上下文巨大且极慢 |

**产出**:2 个 Dockerfile、`.dockerignore`、`docker-compose.yml`、`.env.example`
**学习点**:镜像分层与缓存、多阶段构建、容器网络、数据卷、健康检查
**验收**:新机器上 `docker compose up -d` 一条命令拉起整套系统,浏览器可正常登录

---

### 阶段 3 · 持续部署 CD

**目标**:代码合并后自动上线,且能回滚。

| 动作 | 说明 |
|------|------|
| 准备主机 | 一台云主机(轻量应用服务器即可);安装 Docker / Java 运行环境 |
| Gitee Go 主机部署 | 使用官方主机部署插件:构建制品 → SSH 下发到 `~/gitee_go/deploy` → 执行部署脚本 |
| 部署脚本 | 参考官方 `deploy.sh` 结构:`停止旧进程 → 备份当前版本 → 启动新版本 → 健康检查 → 失败自动回滚` |
| 反向代理 | Nginx:`/` 指向前端静态资源,`/api` 与 `/upload` 反代到后端 8080 |
| 环境分离 | 测试环境 push 自动部署;**生产环境手动审批触发**(Gitee Go 支持阶段设 `trigger: manual`) |
| 密钥管理 | SSH 私钥、主机地址存 Gitee 的凭据/变量,绝不写进仓库 |

**产出**:`.workflow/` 中的部署阶段配置、`deploy.sh`、Nginx 配置
**学习点**:CI 与 CD 的区别、部署策略(滚动/蓝绿)、回滚机制、零停机发布
**验收**:改一行代码 push,几分钟后线上生效;手动触发一次回滚,恢复到上一版本

---

### 阶段 4 · 可观测性与质量

**目标**:系统"看得见、查得到"。

| 动作 | 说明 |
|------|------|
| 健康与指标 | 引入 `spring-boot-starter-actuator`,暴露 `/actuator/health`(部署脚本的健康检查直接复用)与 `/actuator/prometheus` |
| 监控看板 | Prometheus 采集 + Grafana 展示:JVM 内存/GC、HTTP QPS 与耗时、HikariCP 连接池、Redis 连接 |
| 日志 | 结构化日志(JSON 或统一格式)落盘 + 按天切割;进阶接 Loki/ELK 集中收集 |
| 代码质量 | JaCoCo 生成覆盖率报告;可选接入 SonarQube 做静态扫描 |
| 告警 | 设定阈值(如健康检查连续失败、错误率超限)触发通知 |

**产出**:Actuator 配置、`docker-compose` 中的 Prometheus/Grafana 服务、Grafana 看板 JSON、JaCoCo 报告
**学习点**:Metrics / Logs / Traces 三支柱、SLI/SLO、告警设计(避免告警疲劳)
**验收**:能在 Grafana 上看到压测时的 QPS 与 JVM 曲线;人为制造一次 500 错误并在看板上定位到

---

### 阶段 5 · 编排与基础设施即代码(进阶)

**目标**:面向规模化与可复现。

- **K8s**:先用 `minikube` / `kind` 本地跑通,把 compose 改写为 Deployment + Service + Ingress + ConfigMap/Secret,再用 Helm 打包
- **IaC**:用 Terraform 声明式创建云主机/网络/安全组;或用 Ansible 做主机初始化
- **GitOps(可选)**:ArgoCD 监听仓库,以 Git 为唯一事实源自动同步集群状态

**学习点**:容器编排、声明式基础设施、幂等、状态管理
**验收**:`terraform apply` 能从零创建出可用环境;`helm install` 一键部署整套应用

> ⚠️ 对单机小项目,K8s 属于过度设计。建议前四阶段全部跑通、真正遇到"多实例/弹性扩缩"需求后再进入。

---

## 5. 工具选型与理由

| 环节 | 选型 | 理由 |
|------|------|------|
| 代码托管 | Gitee(沿用现状) | 已在使用,迁移成本为零 |
| CI/CD | **Gitee Go** | 与仓库原生集成、免费额度足够个人项目(200 分钟/仓库/月)、有 Spring Boot 模板与主机部署插件 |
| 备选 CI | GitHub Actions / Jenkins | 前者需迁仓到 GitHub;后者重、需自维护,不建议个人学习期引入 |
| 容器 | Docker + Docker Compose | 阶段 2 的必备技能;Compose 足够单机编排 |
| 反向代理 | Nginx | 生产标配,前端静态托管 + API 反代 |
| 监控 | Prometheus + Grafana | 业界事实标准,社区资料最丰富 |
| IaC | Terraform(或 Ansible) | 声明式、可版本化,面试高频 |

### 5.1 CI 执行器 ≠ 部署目标(为什么服务器上不必跑 Jenkins)

**角色分离**:传统 Jenkins 是「自托管 CI」—— 调度与执行在同一台机器,因此需要 1~2GB 内存常驻,并持续维护插件与升级。SaaS 流水线把**执行器放在平台云端**,服务器退化为纯**部署目标**,只需满足:SSH 可达 + 有运行时 + 能执行部署脚本。

| 模型 | 谁承担构建 | 服务器负担 | 代表实现 |
|------|-----------|-----------|---------|
| **Push(推荐)** | 平台云端 Runner | 极低,仅运行 | Gitee Go 主机部署、GitHub Actions + ssh-action |
| **Pull** | 服务器自建 Runner | 高,需装 Runner 并本地构建 | GitLab Runner、Jenkins Agent、GitHub self-hosted runner |

**三大平台对比**(均可替代 Jenkins,且云服务器只作部署目标):

| 平台 | 执行器位置 | 免费额度 | 前置条件 | 对本项目 |
|------|-----------|---------|---------|---------|
| **Gitee Go** | Gitee 云端 | 200 分钟/仓库/月;账号级 500 分钟/月 | 无(仓库已在 Gitee) | **首选**,零迁移成本 |
| **GitHub Actions** | GitHub 云端 | 公共仓库不限;私有 2000 分钟/月 | 代码托管需迁至 GitHub(或做 Gitee→GitHub 镜像) | 备选 |
| **GitLab CI/CD** | GitLab.com 共享 Runner | 400 compute minutes/月 | 需迁仓/镜像到 GitLab | 备选 |

> ⚠️ GitLab 有两种形态易混:**GitLab.com 共享 Runner** 时服务器零负担;若**自建 GitLab 服务端**,则必须另建 GitLab Runner —— 又回到"需要一台机器跑构建"。学习期直接用 GitLab.com 即可。

**结论**:云服务器**不需要也不应该**跑 Jenkins。它只需承担三件事:① 开放 SSH 供流水线推送制品;② 安装运行时(JDK 或 Docker);③ 执行接收制品的部署脚本。

---

## 6. AI 结合点

你提到希望"加 AI 去完成实现"。AI 在这条路线里能扮演三个角色:

| 场景 | 做法 |
|------|------|
| **生成实现** | 让 AI 生成 Dockerfile、流水线 YAML、部署脚本、Nginx 配置 —— 即本路线中各阶段的产出物。**关键是让 AI 结合本项目真实路径与依赖**(如 `settings.xml` 镜像、`D:/pm-upload`、MySQL/Redis 版本),而不是给通用模板 |
| **辅助质量** | 让 AI 为 `deriveStage` 这类纯函数批量生成测试用例;生成 commit message / PR 描述;在 CI 中接入 AI 代码审查(把 diff 交给模型输出评审意见,作为流水线中的一个可选阶段) |
| **辅助运维** | 生产出故障时,把日志贴给 AI 做根因分析;让 AI 解读 Prometheus 指标异常 |

> ⚠️ 提醒:AI 生成的流水线/脚本**必须在真实环境跑通才算数**。DevOps 的价值在于"真的能跑",不在于"配置文件看起来对"。每个阶段我都保留了验收标准,就是为了卡住这一点。

---

## 7. 待决策点(需要你确认)

1. **起始位置**:是严格从阶段 0 开始,还是先跳过阶段 0 直接做阶段 2 的容器化(满足"快速看到效果"的诉求)?
2. **是否有服务器**:阶段 3 需要一台可 SSH 的云主机。你目前有吗?还是想先在本地用 Docker 模拟?
3. **部署模型**:倾向 A(前后端分离容器)还是 B(合并单镜像)?
4. **目标深度**:是想走通到阶段 3(部署上线)为止,还是打算一路做到阶段 4/5?

---

## 8. 风险与注意事项

- **别跳级**:跳过容器直接上 K8s,会遇到一堆无法定位的问题。
- **密钥泄露**:任何密钥都不要写进仓库,一律走 CI 平台变量/环境变量。
- **多实例定时任务**:后端有 `@Scheduled` 全量对账,多实例部署会重复执行 —— 阶段 3/5 扩多实例前需加分布式锁或改为单实例。
- **上传目录是有状态的**:`pm-upload` 必须挂卷/持久化,否则重启丢文件。
- **CI 时长**:Maven 首次拉依赖很慢,Gitee Go 有免费额度限制,善用构建缓存。
- **先备份再自动化**:第一次做自动部署时,务必确认回滚路径可用。

---

## 9. 本地 Jenkins 体验环境

若仍想亲手体验 Jenkins(理解 Pipeline as Code、凭据管理、构建触发器等概念),**在没有服务器的前提下,本地是最合适的地方**。

本机实测:Docker 29.7.2 + Compose v5.5.1、Java 18(Corretto)、Git 2.55、Node 22。WSL 被本机安全策略禁用,故 **Docker 路线最稳**。

### 9.1 方式 A · Docker(推荐)

```bash
docker run -d --name jenkins \
  -p 9090:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  -e JAVA_OPTS="-Duser.timezone=Asia/Shanghai" \
  jenkins/jenkins:lts-jdk17
```

- **端口必须用 9090**:本项目后端占用 8080,直接映射会冲突。
- 解锁密码:`docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword`
- 数据落在具名卷 `jenkins_home`,删除容器不丢配置;访问 `http://localhost:9090`。

### 9.2 方式 B · WAR 包直跑

下载 `jenkins.war` 后执行 `java -jar jenkins.war --httpPort=9090`。本机 Java 18 满足要求(Jenkins 需 17+)。优点是轻;缺点是污染本机、无隔离、升级与卸载麻烦。

### 9.3 方式 C · 装在云服务器(不推荐)

Jenkins 常驻内存 1~2GB,会与 MySQL/Redis/应用争抢资源,2C2G 轻量服务器上极易 OOM。仅当服务器 ≥4GB 且确需长期运行时才考虑。

### 9.4 常见坑

| 现象 | 处理 |
|------|------|
| 插件下载卡住/超时 | 换国内镜像(清华 TUNA / 腾讯云),修改更新中心地址 |
| 构建时间差 8 小时 | 未设时区,加 `-Duser.timezone=Asia/Shanghai` |
| 中文界面不显示 | 安装 Locale 插件并设为 `zh_CN` |
| Docker Desktop 里跑不动 | 内存分配需 ≥4GB |
| 8080 端口被占用 | 改用 9090 映射 |

### 9.5 学习路径(由浅入深)

1. 启动 + 解锁 + 安装推荐插件(理解"插件化"设计)
2. 建 Freestyle Job 拉取本项目跑 `mvn -s settings.xml test`,理解 **Job / Workspace / Build** 三概念
3. 改写为 **Pipeline Job + Jenkinsfile**(重点:业界已以 Pipeline as Code 为主,Freestyle 点界面属过时技能)
4. 配置 Credentials 存放 Gitee 的 SSH 密钥或访问令牌
5. 配置触发器:Webhook 或轮询 SCM(体会 Push 与 Poll 的差异)
6. 加制品归档 `archiveArtifacts`,接住本项目 `target/*.jar`
7. 进阶:用 Jenkins 调用 Docker 构建镜像,体会 Jenkins 与容器的协作

> 学完 Jenkins 再回看 Gitee Go,会发现概念一一对应:Job↔流水线、Stage↔阶段、Workspace↔构建环境、Credentials↔凭据、Jenkinsfile↔`.workflow/*.yml`。**Jenkins 的价值在于帮你理解这些概念从何而来**,而不在于它本身是否要上生产。

---

## 10. 参考实现:Cloudland(已验证跑通的同构案例)

`D:\IDEA_Project\Cloudland` 是一套**已经在阿里云上跑通自动部署**的同类项目(Spring Boot + Vue)。它是本路线图最好的对照物 —— 不要照抄教程,照它的结构做。

### 10.1 它的实际配置

| 维度 | Cloudland 的做法 |
|------|-----------------|
| 远程仓库 | **双远程**:`origin`=Gitee(部署触发)、`github`=GitHub(质量门禁),代码双向镜像 |
| 部署流水线 | `.gitee/workflows/cloudland-deploy.yml` —— push `main` 触发,`step: shell@agent` + `hostGroupID`(主机组 `ali_cloudland`),脚本下发到主机执行 |
| 质量流水线 | `.github/workflows/ci.yml` —— 4 个 job:`api`(pytest+Allure)、`e2e`(Playwright)、`smoke`(线上只读巡检,每 6 小时定时)、`report`(汇总发布 gh-pages) |
| 部署脚本 | `scripts/deploy.sh` —— `set -Eeuo pipefail`、**flock 防并发**、git reset --hard、compose build/up、前端容器健康检查轮询、镜像清理 |
| 部署模型 | **分离部署**:前端 `nginx:alpine` 容器(占 80)+ 后端容器(9090)+ MySQL + Redis |
| 配置管理 | `application.yml` 全部 `${ENV_VAR:default}`;真实值放未跟踪的 `.env`;另有 `config/application-local.yml` 本地覆盖(经 `spring.config.import: optional:` 引入) |
| 镜像加速 | Docker Hub 走 `docker.1panel.live` 镜像;Maven 用阿里云镜像(写在 Dockerfile 内联);npm 用 `registry.npmmirror.com` |
| 测试体系 | `testing/api/`(pytest 接口测试 34 例 + 契约测试)、`vue/tests/`(Playwright E2E 62 例) |
| 数据持久化 | 具名卷 `mysql-data` / `redis-data` / `cloudland-files`;`cloudland.sql` 挂到 `/docker-entrypoint-initdb.d/` |

### 10.2 可直接复用到 pm-system 的 8 个模式

1. **`.env` + `${VAR:default}` 双向兼容** —— 本地零配置可跑,容器/CI 里用环境变量覆盖。
2. **`spring.config.import: optional:file:./config/application-local.yml`** —— 本地私密配置与仓库配置分离,不污染提交历史。
3. **多阶段 Dockerfile + 依赖层前置** —— 先 `COPY pom.xml` 再 `dependency:go-offline`,先 `COPY package*.json` 再 `npm install`,最大化利用层缓存。
4. **国内镜像源内联进 Dockerfile** —— 这是国内环境的必需项,否则构建极慢或超时。
5. **`deploy.sh` 的 flock 并发锁** —— 恰好解决 pm-system「多实例定时任务重复执行」的同类并发问题。
6. **部署后健康检查轮询 + 失败自动打日志** —— 比"起完就算成功"可靠得多。
7. **线上只读定时巡检** —— 不部署、不写库,只验前端可达 + 健康检查 + 公开接口。这是最低成本的"生产监控"。
8. **CI 与 CD 分离在两条流水线上** —— GitHub Actions 只做质量门禁,Gitee Go 只做部署。职责单一,互不干扰。

### 10.3 它存在的问题(迁移时不要一起带走)

| 问题 | 说明 | 处理建议 |
|------|------|---------|
| **真实密钥已公开泄露** | `src/main/resources/application.yml` 中邮件密码使用了**真实授权码作为默认值**,且 GitHub 仓库为公开仓库,该文件可被任何人读取 | **立即轮换该授权码**;把默认值改为占位符,真实值只放 `.env` |
| 流水线定义重复 | `.workflow/pipeline-20260602.yml` 与 `.gitee/workflows/cloudland-deploy.yml` 内容几乎相同,两个不同 name 的流水线都在 push main 时触发 | 删除旧文件,只保留 `.gitee/workflows/` 下的定义 |
| 生产环境端口暴露 | compose 中 MySQL 映射了 `3306:3306` 到宿主机 | 生产环境应去掉该映射,仅容器网络内可达 |
| 万能验证码默认开启 | `application.yml` 中 `code.master-enabled` 默认为 `true`(可用 `000000` 通过任意验证码) | 默认改为 `false`,显式开启才生效 |
| `.gitignore` 编码异常 | 中文注释显示为乱码,说明文件非 UTF-8 保存 | 转存为 UTF-8 |
| 数据库账号用 root | 应用直连 MySQL root 账号 | 建立最小权限的应用专用账号 |

---

## 附:阶段产出物一览

| 阶段 | 主要新增文件 |
|------|-------------|
| 0 | `.gitignore`(修订)、`application-local.yml.example`、`README.md` |
| 1 | `.workflow/MasterPipeline.yml`、`backend/src/test/java/**`(单测) |
| 2 | `backend/Dockerfile`、`frontend/Dockerfile`、`docker-compose.yml`、`.dockerignore`、`.env.example` |
| 3 | `.workflow/` 部署阶段、`deploy/deploy.sh`、`deploy/nginx.conf` |
| 4 | `docker-compose.monitoring.yml`、`deploy/prometheus.yml`、`deploy/grafana-dashboard.json` |
| 5 | `k8s/*.yaml`、`helm/pm-system/*`、`terraform/*.tf` |
