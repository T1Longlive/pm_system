# CI/CD 现状与补全计划

> **这份文档是给「新对话」用的交接材料。** 目的是让一个完全没有上下文的新会话（人或 AI）
> 读完这一份就能立刻上手，不必重新摸索。
>
> 覆盖 **pm-system** 与 **Cloudland** 两个项目。
>
> 最后更新：2026-09-15

---

## 0. 怎么用这份文档

| 你想做什么 | 看哪节 |
|---|---|
| 搞清楚两个项目现在什么样 | §1、§3 |
| 知道有哪些共享资源、值是多少 | **§2（最常查）** |
| 知道踩过哪些坑、别再踩 | **§7（最值钱）** |
| 知道要改哪个文件 | §8 |
| 找命令 | §9 |
| 接着往下做 | **§6（补全计划）** |

---

## 1. 两个项目一览

| | **pm-system** | **Cloudland** |
|---|---|---|
| 用途 | 内部研发项目管理系统 | 云用地 / 农产品平台 |
| 后端 | Spring Boot **3.2.5** / Java **17** / MyBatis-Plus | Spring Boot 2.x / Java **8** |
| 前端 | Vue 3 + TS + Vite 5 + Element Plus | Vue 2/3 + 原生 CSS |
| 前端目录 | `frontend/` | **`vue/`**（注意是子目录） |
| 后端端口 | 8080 | **9090** |
| 对外端口 | **8081** | **80** |
| 部署目录 | `/root/pm-system` | `/root/cloudland` |
| Gitee 仓库 | `gitee.com/t1longlive/pm-system` | `gitee.com/t1longlive/cloudland` |
| 其它远程 | — | 另有 `github`（公开仓库，**部署不用它**） |
| 初始管理员 | `admin` / `Pm@123456` | 见项目文档 |

**两个项目共用同一台服务器、同一个 ACR、同一个主机组。**

---

## 2. 共享基础设施（查值看这里）

### 2.1 服务器

| 项 | 值 |
|---|---|
| 主机 | 阿里云 ECS，`8.137.114.176` |
| 规格 | **1.8 GiB 内存** / 40G 磁盘 —— **内存是硬约束** |
| Docker | 已装，daemon 配了 `registry-mirrors`（含 `docker.1panel.live`） |
| 面板 | 1Panel（`/root/install_panel.sh`） |
| Gitee runner | 已注册，`/root/gitee-go-runner` |

### 2.2 容器镜像服务 ACR

| 项 | 值 |
|---|---|
| 端点 | `crpi-jzqyelh9p5zb0dmu.cn-chengdu.personal.cr.aliyuncs.com` |
| 命名空间 | `t1longlive`（**必须与 Gitee 个人空间地址一致**） |
| 用户名 | `T1LONGLIVE` |
| 凭证 ID | `18140213287`（Gitee「凭证管理」里那条，**账号级，跨仓库可用** ✓） |
| 服务器登录 | 已 `docker login`，凭据在 root 的 `~/.docker/config.json` |

> ⚠️ 端点格式是**新版个人版实例** `<实例ID>.<地域>.personal.cr.aliyuncs.com`，
> 不是老版 `registry.cn-<地域>.aliyuncs.com`。

### 2.3 已转存到 ACR 的基础镜像（**新项目可直接复用，无需再转存**）

```
t1longlive/maven:3.9-eclipse-temurin-17      t1longlive/eclipse-temurin:17-jre
t1longlive/maven:3.8.6-openjdk-8             t1longlive/openjdk:8-jre-slim
t1longlive/node:20-alpine                    t1longlive/node:16-alpine
t1longlive/nginx:alpine
```

### 2.4 应用镜像

```
t1longlive/pm-backend:latest          t1longlive/pm-frontend:latest
t1longlive/cloudland-backend:latest   t1longlive/cloudland-frontend:latest
```

### 2.5 Gitee 主机组

| 项 | 值 |
|---|---|
| 主机组 ID | `ali_cloudland` |
| 主机 UUID | `844d9abd-3fd6-4e32-99ee-3339f2497a55` |

> ⚠️ `hostID` **不能留空数组** —— 留空 = 未选择任何主机，会报
> `No matching host instance was found under host group`。

---

## 3. 当前架构（已跑通）

### 3.1 链路

```
① 本地 git push
      ↓
② Gitee 云端流水线（build@docker / buildkit）
     ├─ 从 ACR 拉基础镜像
     ├─ 构建 pm-backend / pm-frontend 镜像
     └─ 推送到 ACR
      ↓
③ 派发到阿里云主机（shell@agent + Gitee Go runner）
      ↓
④ 服务器执行 scripts/deploy.sh
     ├─ git reset --hard（同步代码）
     ├─ docker compose pull（只拉镜像，不构建）
     └─ docker compose up -d
      ↓
⑤ 健康检查 → 用户浏览器访问
```

### 3.2 镜像流向（容易搞混，单列出来）

```
你的阿里云主机 ──push──→ ACR ←──pull── Gitee 云端构建机
   能连 Docker Hub              连不上 Docker Hub
```

| 镜像类型 | 谁生产 | 谁消费 | 频次 |
|---|---|---|---|
| **基础镜像**（maven/openjdk/node/nginx） | 别处做的，**主机负责搬运** | **Gitee 云端** | 主机搬运一次性；云端每次构建都取 |
| **应用镜像**（pm-* / cloudland-*） | **Gitee 云端构建** | **主机** | 每次部署 |

> **关键：主机从不拉基础镜像**（它不构建，用不上 maven/node）。
> **基础镜像搬进 ACR 是为了喂给云端**，不是给自己用。

### 3.3 为什么必须这么绕

| | 能连 Docker Hub | 能连 ACR |
|---|---|---|
| 阿里云主机 | ✅（靠 1Panel 镜像加速） | ✅ |
| Gitee 云端构建机 | ❌ **i/o timeout** | ✅ |

**唯一交集是 ACR。** 所以由"能连 Docker Hub 的一方"搬一次，之后全部走 ACR。

### 3.4 关键设计决策与理由

| 决策 | 理由 |
|---|---|
| **只映射前端端口**（后端/MySQL/Redis 不映射） | Docker 发布端口会**绕过宿主防火墙**，所以"不映射"才是可靠的安全边界 |
| 后端/MySQL/Redis 走 compose 服务名互访 | `mysql:3306` 由 Docker 内置 DNS 在项目网络内解析，无需暴露 |
| 镜像在云端构建，服务器只 pull | 服务器仅 1.8G，本地编译会被 **OOM Killer 杀掉**（日志无报错直接中断） |
| 基础镜像转存自己的 ACR | 云端连不上 Docker Hub；顺带固定基础镜像来源（供应链可控） |
| `dependsOn` 做质量门禁 | 构建失败就不部署，**线上不受影响** |

---

## 4. 已完成的工作（时间线）

### 2026-09-14 —— pm-system 打通

1. **修掉 8 个问题**让「云端编译 + 服务器构建」首次闭环：
   `settings.xml` 相对路径、误改的工具链版本、测试依赖真实 DB、
   `hostID` 空数组、CORS 自我引用、建表脚本未挂载、Docker 镜像源停服、
   nginx `$host` 丢端口导致 403
2. **识别架构缺陷**：云端产物无人消费，服务器重复编译（名义 Push、实际 Pull）
3. **改造为方案 B**（云端构建镜像推 ACR），又趟过 4 个新坑：
   凭证模板形式、Docker Hub 不可达、凭证地址缺命名空间、密码误提交
4. **沉淀文档**：`docs/部署改造完整指南.md`（1096 行）
5. **验证**：改一行 CSS → push → 页面变白 ✓

### 2026-09-15 —— Cloudland 跟进 + 端到端验证

1. 照搬方案 B（8 个文件），**复用 pm-system 的 ACR 凭证与命名空间**
2. 转存 Cloudland 的 4 个基础镜像
3. **验证**：改首页文案为「你好世界」→ push → 浏览器看到 ✓，流水线全绿 ✓

---

## 5. 现状评估：**CD 完整，CI 残缺**

| 环节 | 属于 | 有吗 |
|---|---|---|
| 提交触发流水线 | CI | ✅ |
| 自动构建 | CI | ✅ |
| **自动测试** | **CI 核心** | ❌ **没有** |
| 产出镜像 | CI | ✅ |
| 推送镜像仓库 | CD | ✅ |
| 自动部署到生产 | CD | ✅ |
| 健康检查 | CD | ⚠️ 只盯前端 |
| 回滚 | CD | ❌ 没有 |

**当前形态是 Continuous Deployment（推到 main 直接上生产，无人为确认）。**

**核心问题**：CI 只验证「能编译」，不验证「功能正确」。
一个逻辑写错但能编译的提交，**照样全绿上线**。

---

## 6. 补全计划

### 阶段一：回滚能力（**优先级最高**）

**目标**：能一键退回任意历史版本。

**为什么优先**：现在线上出问题**没有退路**，只能改代码重新推。
回滚能力是「能部署」与「可运维」的分界线。

#### 要做的事

1. **镜像 tag 版本化**（流水线里两个 `build@docker`）
   ```yaml
   tag: pm-backend:${GITEE_COMMIT}      # 用提交 SHA 作 tag
   ```
   > ⚠️ **动手前必须验证**：`${GITEE_COMMIT}` 是否为**完整 40 位 SHA**，
   > 且与部署脚本里的 `git rev-parse HEAD` 一致。
   > 若不一致（或该变量在 `shell@agent` 里不可用），改用
   > `${GITEE_PIPELINE_BUILD_NUMBER}` 并把该值通过其它方式传给部署脚本。
   > **不要凭猜测写入** —— 先看一次真实构建日志。
   >
   > 注意：`shell@agent` 里**不保证能读到 Gitee 系统参数**，
   > 所以部署侧最可靠的做法是 `git rev-parse HEAD`。

2. **compose 改为按变量取 tag**
   ```yaml
   image: crpi-.../t1longlive/pm-backend:${IMAGE_TAG:?IMAGE_TAG must be set}
   ```
   （`:?` 让变量缺失时直接报错，避免静默拉 `latest`）

3. **deploy.sh 里计算并导出 tag**
   ```bash
   git reset --hard "origin/$BRANCH"
   export IMAGE_TAG="$(git rev-parse HEAD)"
   $DC pull backend frontend
   ```

4. **回滚流程**（写进文档）
   ```bash
   # 方式 A：回退代码再部署（会重新构建一个旧版本的镜像）
   git revert <bad-commit> && git push
   # 方式 B：直接部署指定 tag（需先把 compose 的 tag 参数化到位）
   ```

**验收标准**：能部署上一版镜像并确认服务正常。

---

### 阶段二：补上 CI（单元测试）

**目标**：让流水线能拦住「功能错误的提交」。

#### 要做的事

1. **写不依赖 Spring 上下文的纯单元测试**
   - 现状：唯一的 `PmApplicationTests` 是 `@SpringBootTest`，**要连真实 MySQL**，
     云端跑不了 → 这正是当初被迫加 `-DskipTests` 的原因
   - 目标：针对 Service 层纯逻辑、工具类、日期计算等写纯 JUnit 测试

2. **把测试放进流水线**（**推荐做法：在 Dockerfile 里跑**）
   ```dockerfile
   # 原：RUN mvn -s ./settings.xml -B clean package -DskipTests
   # 改：RUN mvn -s ./settings.xml -B clean package
   ```
   **优点**：不用新增流水线步骤；**测试失败 → 镜像构建失败 → 部署被 `dependsOn` 挡住** ✓

   > ⚠️ 同时要把 `@SpringBootTest` 那个测试改用 `@Disabled` 或 profile 隔离，
   > 否则它仍会拖垮构建。

3. **（可选）恢复独立的测试门禁**
   若希望「测试失败比镜像构建失败报得更早更清楚」，可加回 `build@maven` 步骤
   只跑 `mvn test`（不打包）。代价是云端构建时间变长。

**验收标准**：故意改坏一个业务逻辑 → 流水线红了 → 线上未受影响。

---

### 阶段三：健康检查补上后端

**目标**：后端崩溃不再被漏报成「部署成功」。

**现状**：两个项目的 `deploy.sh` 都只有 `wait_for_frontend()`，
**只 `docker inspect` 前端容器**。

**改法**（两项目同样处理）：

```bash
wait_for_container() {
  local name="$1" retries=12 delay=5 status=""
  for _ in $(seq 1 "$retries"); do
    status="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || true)"
    if [ "$status" = "running" ]; then log "Container $name is running"; return 0; fi
    sleep "$delay"
  done
  log "Container $name failed to reach running state, status: ${status:-missing}"
  $DC ps | tee -a "$LOG_FILE" >/dev/null || true
  $DC logs --tail=200 backend  | tee -a "$LOG_FILE" >/dev/null || true
  $DC logs --tail=200 frontend | tee -a "$LOG_FILE" >/dev/null || true
  exit 1
}

wait_for_container "pm-backend"      # ← 先后端（更可能失败）
wait_for_container "pm-frontend"
```

**验收标准**：手动让后端启动失败（如临时改坏 DB_URL）→ 部署应报失败并打印日志。

---

### 阶段四：内存与稳定性（可选，按需）

| 项 | 做法 | 何时做 |
|---|---|---|
| JVM 堆上限 | 加环境变量 `JAVA_TOOL_OPTIONS: "-Xmx256m"` | 若观察到可用内存持续下降 |
| **注意** | 两个 Dockerfile 都是 exec 形式 `ENTRYPOINT ["java","-jar",...]`，**不吃 `JAVA_OPTS`**，必须用 `JAVA_TOOL_OPTIONS` | |
| 加 swap | `fallocate -l 2G /swapfile2` + `mkswap` + `swapon` | 想要安全网时 |
| 升级 ECS 到 4G | 控制台操作 | 想让两套应用跑得舒服 |

**当前内存实测（两套 + 1Panel + runner 全跑）**：
```
Mem:  1.8Gi total / 1.7Gi used / available 175Mi
Swap: 1.0Gi / 356Mi used
```
**可用仅 175Mi —— 能跑，但余量薄。**
好消息：方案 B 之后**部署不再产生编译峰值**，压力只来自「运行」。

---

## 7. 已知坑与铁律（**最值钱的一节**）

### 7.1 🔴 Gitee 可视化编辑器会重写流水线文件（已踩 **4 次**）

**它会**：删掉全部注释、重排格式、把 stage `name` 改成中文、
**并删除 `dependsOn`**（后果：构建失败也照常部署，质量门禁失效）。

**应对**：
> **改流水线优先改仓库 YAML 并 push。**
> 万不得已用网页编辑器，**保存后必须回仓库核对**：
> ```bash
> git fetch origin main && git show FETCH_HEAD:.workflow/<文件>.yml | grep -A2 dependsOn
> ```

**根治办法（待验证）**：在**图形视图的阶段设置**里用界面配置依赖 ——
编辑器只保留它能识别的字段，手写的会被当"多余字段"清掉。

**累计次数**：pm-system 3 次 + Cloudland 1 次。

### 7.2 🔴 构建上下文固定 = 仓库根目录

`build@docker` 的上下文是**代码库根目录**（已在构建日志中确证 `--local context=<仓库根>`）。

- 后端 Dockerfile（在根）→ `COPY pom.xml .` ✓ 正常
- **前端 Dockerfile 在子目录时** → COPY 路径必须带前缀：
  `COPY vue/package*.json ./`（不能写 `COPY package*.json ./`）
- 副作用：本地构建要用 `docker build -f vue/Dockerfile .`

### 7.3 🔴 凭证的「docker仓库地址」必须包含命名空间

凭证表单只有 4 个字段（凭证名称 / docker仓库地址 / 用户名 / 密码），**没有命名空间字段**。

- 地址要写成 **`<端点>/t1longlive`**，不是只写端点
- 否则推送报：`push access denied ... insufficient_scope: authorization failed`

**判别口诀**：同一仓库同一凭证，**基础镜像拉得动、成品镜像推不动** ⇒ 就是缺命名空间。

### 7.4 🔴 `repository`/`username`/`password` 必须是凭证模板

```yaml
certificate: "18140213287"
repository: "{{CERTIFICATION.18140213287.server}}"   # 不是字面地址，也不是 ${CERTIFICATE}
username:   "{{CERTIFICATION.18140213287.username}}"
password:   "{{CERTIFICATION.18140213287.password}}"
```

- 写死字面值 → 不生效
- 用 `{{CERTIFICATION.${CERTIFICATE}.server}}`（未代入）→ 同样不生效
- **编辑器对不同步骤可能行为不一致**（曾出现"后端代入、前端未代入"）→ 保存后逐个核对

### 7.5 🔴 字段不被识别会被**静默忽略**

**通用判别法**：凡「照文档写了却行为不符」，**先去看插件打印的原始参数**。
出现未解析模板或空值 = 字段没被读到。

已踩：`goals:`（应为 `commands:`）、`repository/username/password`。

### 7.6 🔴 配置属性名与占位符同名 = 自我引用

```yaml
# ❌ 启动直接崩：Circular placeholder reference
CORS_ALLOWED_ORIGINS: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}
```

默认值写在 Java 的 `@Value` 表达式里，**不要在 yml 里定义同名键**。
其它变量安全是因为「配置路径」与「环境变量名」不同名（如 `spring.datasource.url` ← `${DB_URL}`）。

### 7.7 🔴 nginx 必须用 `$http_host`，不能用 `$host`

`$host` **会剥掉端口** → 后端反推出的 origin 与浏览器不一致 → 同源请求被误判为跨域 → **POST 全部 403**。

```nginx
proxy_set_header Host $http_host;   # ✅ 含端口
```

**反直觉**：「经 nginx 同源反向代理就无需配 CORS」**是错的** ——
浏览器对非 GET/HEAD（含同源 POST）也会带 `Origin` 头。

### 7.8 其它必须知道的

| 坑 | 要点 |
|---|---|
| 建表脚本 | 必须挂进 `/docker-entrypoint-initdb.d/`，**只在数据目录为空时执行一次** |
| MySQL 密码 | **只在首次初始化读一次**；改密码需清卷（`docker volume rm <项目>_pm-mysql-data`，**删卷=删库**） |
| `.env` 密码特殊字符 | `#` 会被当注释截断、`$` 会触发插值 → 用 `openssl rand -hex 16` |
| 换行符 | `.gitattributes` 必须钉死 `*.sh eol=lf`，否则 CRLF 会让 Linux 报 `bash\r: No such file` |
| 安全组 | 阿里云安全组必须放行对外端口；**Docker 发布端口会绕过宿主防火墙** |
| `hostID` | 不能留空数组 |
| 已下线的镜像 | `openjdk:8-jre-slim` 在 Docker Hub 已下线 → 靠**复用服务器本地镜像**取得 |
| 编辑器填密码 | 会把密码**原样写回仓库** → 密钥一律走「凭证管理」，绝不填进流水线 YAML |

### 7.9 排查方法论（比具体知识更重要）

1. **只改被证实坏掉的地方。** 实测能用的值不要因为"看起来不合理"就动它。
2. **空输出不是证据。** 想区分「从未发生」和「刚被停止」，要找带时间戳/计数的直接证据。
3. **外部服务的规则必须实测。** 二手文档会过时（本项目的工具链版本、凭证机制、命名空间规则全部与文档不符）。
4. **`mvn compile` 通过 ≠ 应用能启动。** 配置类改动至少真正启动一次。
5. **环境不对称最贵。** 同一个 bug 可能「容器里正常、本地崩」或反之 —— 只看一边查不出来。

---

## 8. 关键文件索引

### 8.1 pm-system

| 文件 | 作用 |
|---|---|
| `.workflow/流水线-202609111651.yml` | 流水线：`build@docker`×2 + `shell@agent` 部署，`dependsOn` 门禁 |
| `docker-compose.yml` | 4 服务；backend/frontend 用 ACR 镜像；mysql 挂了 `init.sql` |
| `backend/Dockerfile` | 多阶段：`maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre`，FROM 均为 ACR |
| `frontend/Dockerfile` | `node:20-alpine` → `nginx:alpine`，COPY 路径带 `frontend/` 前缀 |
| `frontend/nginx.conf` | 静态托管 + `/api` 反代，**`$http_host`** |
| `scripts/deploy.sh` | 服务器部署：flock、`pull`、`up -d`、健康检查 |
| `scripts/mirror-base-images.sh` | 基础镜像转存 ACR |
| `.env.example` | 环境变量模板（13 项） |
| `.gitattributes` | `eol=lf` |
| `docs/部署改造完整指南.md` | 从零改造的完整教程（含 9 条坑） |

### 8.2 Cloudland

| 文件 | 作用 |
|---|---|
| `.workflow/流水线-202609111434.yml` | 同上；`Build Images` + `Deploy To Aliyun` |
| `docker-compose.yml` | backend/frontend 用 ACR 镜像；已有 `cloudland.sql` 挂载 |
| `Dockerfile` | `maven:3.8.6-openjdk-8` → `openjdk:8-jre-slim` |
| `vue/Dockerfile` | `node:16-alpine` → `nginx:alpine`，**COPY 带 `vue/` 前缀** |
| `scripts/deploy.sh` | 同结构，另有 `ensure_host_nginx_stopped`（80 端口被宿主机 nginx 占用时停它） |
| `scripts/mirror-base-images.sh` | 基础镜像转存（含"已下线镜像复用本地"逻辑） |
| `.gitattributes` / `.dockerignore` | 本次新增（此前缺失） |

---

## 9. 常用命令速查

### 查看部署情况

```bash
# 服务器端部署日志
tail -100 /var/log/pm-system-deploy.log
tail -100 /var/log/cloudland-deploy.log

# 容器状态
cd /root/pm-system  && docker compose ps
cd /root/cloudland  && docker compose ps
```

### 验证「镜像确实来自 ACR」

```bash
docker inspect pm-backend        --format '{{.Config.Image}}'
docker inspect cloudland-backend --format '{{.Config.Image}}'
# 应显示 crpi-.../t1longlive/xxx:latest
```

### 排错三板斧（区分应用问题与网络问题）

```bash
curl -I http://127.0.0.1:8081     # 本机测，绕过所有防火墙
ss -ltnp | grep 8081              # 确认端口在监听
# 再从自己电脑浏览器访问 http://8.137.114.176:8081
```

### 看内存

```bash
free -h
docker stats --no-stream
```

### 手动执行一次部署（不用等流水线）

```bash
cd /root/pm-system && /bin/bash scripts/deploy.sh main
```

### 核对流水线是否被编辑器改坏

```bash
git fetch origin main && git show FETCH_HEAD:.workflow/<文件>.yml | grep -A2 dependsOn
```

---

## 10. 待办清单

**优先级从高到低：**

- [ ] **阶段一**：镜像 tag 版本化 + 回滚能力（§6.1）
- [ ] **阶段二**：补纯单元测试，测试跑在 Dockerfile 里（§6.2）
- [ ] **阶段三**：`deploy.sh` 健康检查补上后端（§6.3）
- [ ] 确认 Gitee 图形视图能否用界面配置 `dependsOn`（根治编辑器删字段问题）
- [ ] 清理 `backend/Dockerfile` 第 27 行过时注释（写着"测试在 CI 跑"，实际没有）
- [ ] 按需：JVM 堆上限 / 加 swap / 升级 ECS 到 4G（§6.4）
- [ ] 上线后立即修改初始管理员密码（`admin` / `Pm@123456` 是公开的）
- [ ] Cloudland 首页标语统一：`HomeView.vue` 第 13 行英文版、`LoginForm.vue` 第 10/45 行仍是旧文案

---

## 附：术语表

| 术语 | 含义 |
|---|---|
| **CI**（持续集成） | 每次提交自动构建**并测试**，尽早发现破坏 |
| **CD**（持续交付/部署） | 自动把产物发布出去。Delivery=需人工确认；Deployment=全自动 |
| **Push 模型** | 云端构建产物，推给生产；服务器只运行 |
| **Pull 模型** | 服务器自己拉源码并在本地构建 |
| **基础镜像** | 别人做好的运行环境（maven/node/nginx…），不是你产出的 |
| **应用镜像** | 由你的源码构建出来的镜像 |
| **暂存制品（artifacts）** | Gitee 内部的临时缓存，7 天清除；**不等于制品库** |
| **ACR** | 阿里云容器镜像服务，本项目作**中转站**用（只存，不构建不运行） |
