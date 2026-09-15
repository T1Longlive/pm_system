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
| **知道补全做到哪了、还差什么** | **§5（进度总览）** |
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

**tag 规则：`<commit SHA>`（完整 40 位），不再用 `latest`。**

```
t1longlive/pm-backend:<commit-sha>        t1longlive/pm-frontend:<commit-sha>
t1longlive/cloudland-backend:<commit-sha> t1longlive/cloudland-frontend:<commit-sha>
```

- 构建侧（Gitee 云端 build@docker）：`tag: pm-backend:${GITEE_COMMIT}`
- 部署侧（服务器 deploy.sh）：`IMAGE_TAG="$(git rev-parse HEAD)"` —— 同一提交产生的同一值
- ⚠️ **`:latest` 已废弃、不会再被推送。** 它现在指向的是改造前那个旧镜像，
  谁再去 `docker pull ...:latest` 都会拿到过时的东西。建议在 ACR 控制台把它删掉，
  免得将来有人误用。

  **为什么它不会被"下次提交覆盖"**：`docker push` 只推你明确指定的 ref，
  而流水线的 tag 字段已经是 `${GITEE_COMMIT}` —— 没有任何环节再写 `latest`。
  它是一条**冻结的指针**，不会自动更新。

  **验证方法（别只信推断，一眼就能证实）**：ACR 控制台 → `t1longlive/pm-backend`
  → 版本列表，看 `latest` 的**更新时间**：

  | 看到的 | 说明 |
  |---|---|
  | 停在改造那天之前（2026-09-15 11:45 前） | ✅ 已冻结，符合预期 |
  | 时间是最近某次推送 | ❌ 还有东西在写它 → 去查构建日志里 buildctl 的 `-t` 参数 |

  这个检查顺带让你看到 ACR 上累积了多少 SHA tag（见下一条）。

  **要删的话，务必走对路径 —— 这里踩过一次事故（2026-09-15）**：

  > 🔴 **必须先点右侧的「管理」进入仓库，再在「镜像版本」列表里只勾 `latest` 那一行删除。**
  > **绝对不要点仓库列表右侧的「删除」** —— 那是删**整个仓库**，
  > 仓库内**所有 SHA tag 一并消失**，而 **ACR 没有回收站，删了不可恢复**。
  >
  > 实际后果：四个应用仓库被整个删掉，`faa9821` / `393f8bd` / `442178c` / `87c63f7`
  > 等全部历史镜像丢失，**回滚能力一度归零**。
  >
  > 教训：**"清理一个 tag"和"删除一个仓库"在同一个页面上只差一次点击。**
  > 涉及删除时的指令必须写清"点到哪一层"，而不是"去控制台删掉"。

  理论上也能用 Registry v2 API（`DELETE /v2/<name>/manifests/<digest>`）脚本化，
  但要先取 digest、处理鉴权，为 4 个 tag 不值得 —— 也正因为 API 不好用才更容易误点。

  **如果误删了，唯一可能的补救**：服务器本地可能还留着带历史 tag 的镜像，
  `docker push` 回 ACR 即可重建（push 会自动创建仓库）：

  ```bash
  docker images --format '{{.Repository}}:{{.Tag}}' \
    | grep '^crpi-.*/t1longlive/' | grep -v '<none>' | sort
  # 找到的逐个推回
  docker push crpi-.../t1longlive/pm-backend:<sha>
  ```

  本地镜像一旦被 `docker image prune` 清掉，就**彻底找不回来了**。
- ⚠️ ACR 上的 tag 会随每次 push 累积。个人版实例有配额，定期在控制台清理旧 tag。

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
     ├─ 构建 pm-backend / pm-frontend 镜像，tag = ${GITEE_COMMIT}
     └─ 推送到 ACR
      ↓
③ 派发到阿里云主机（shell@agent + Gitee Go runner）
      ↓
④ 服务器执行 scripts/deploy.sh
     ├─ git reset --hard（同步代码）
     ├─ IMAGE_TAG = git rev-parse HEAD   ← 与 ② 的 ${GITEE_COMMIT} 必须是同一个值
     ├─ docker compose pull（只拉镜像，不构建）
     └─ docker compose up -d
      ↓
⑤ 健康检查 → 用户浏览器访问
```

> **②的 tag 与 ④的 IMAGE_TAG 必须相等，这是整条链路唯一的隐性契约。**
> 相等时一切正常；不相等时 `pull` 会失败并退出 —— **宁可报错，不部署错版本**。
> 为什么会不相等？部署阶段的 `reset --hard origin/main` 取的是"此刻"的分支最新提交，
> 若构建期间又有新 push，它就不是构建时的那个 commit 了（并发 push 竞态）。见 §7.10。
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
| **应用镜像 tag = commit SHA**（非 `latest`） | 镜像不可变 ⇒ 能回滚、能审计"线上跑的是哪次提交" |
| **compose 用 `${IMAGE_TAG:?...}` 而非 `:-latest`** | 变量缺失时**直接报错**，杜绝"静默拉 latest"这类查不出来的错 |
| **`pull` 失败即退出，排在 `up -d` 之前** | 拉不到镜像时线上完全没被碰过 —— 这是回滚能安全生效的前提 |

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

## 5. 现状评估与补全进度

### 5.1 八环节对照（原始评估 → 现在）

| 环节 | 属于 | 改造前 | **现在** | 备注 |
|---|---|---|---|---|
| 提交触发流水线 | CI | ✅ | ✅ | |
| 自动构建 | CI | ✅ | ✅ | |
| **自动测试** | **CI 核心** | ❌ | ✅ **已具备** | 23 个纯单元测试跑在 Dockerfile 里（§6.2） |
| 产出镜像 | CI | ✅ | ✅ | tag 已改为 commit SHA |
| 推送镜像仓库 | CD | ✅ | ✅ | ⚠️ 仓库被误删过，见 §6.1 |
| 自动部署到生产 | CD | ✅ | ✅ | 加了「过期即跳过」防竞态（§7.10） |
| 健康检查 | CD | ⚠️ 只盯前端 | ✅ **前后端都查** | 后端还要求「没崩过 + 撑过启动期」（§6.3） |
| 回滚 | CD | ❌ | ✅ 机制已验证 | ⚠️ 当前无可回滚目标（§6.1 事故） |

**当前形态仍是 Continuous Deployment（推到 main 直接上生产，无人为确认）。**

### 5.2 补全计划进度

| 阶段 | 内容 | 进度 | 状态 |
|---|---|---|---|
| **阶段一** | 回滚能力（镜像 tag 版本化） | **~95%** | 机制已验证通过；待 ACR 重建 + 收尾 |
| **阶段二** | 补上 CI（单元测试） | **100%** | 已实现并本地验证（24 用例 / 改坏逻辑 → 9 失败 + 构建失败）；**待云端验证** |
| 阶段三 | 健康检查补上后端 | **100%** | 已实现；逻辑层用模拟时钟验证过；**待服务器验收** |
| 阶段四 | 内存与稳定性 | 0% | 可选，按需 |

### 5.3 核心问题：原问题已解决，但边界要说清

**原问题**：「CI 只验证『能编译』，不验证『功能正确』。一个逻辑写错但能编译的提交，照样全绿上线。」

**现状**：已解决 —— 纯单元测试跑在镜像构建里，测试失败即构建失败即部署被挡。
本地已证明：改坏一处判据 → 9 个用例失败 + BUILD FAILURE。

**但别高估它**：

| 它能拦 | 它拦不住 |
|---|---|
| 被测试覆盖到的纯逻辑被改错 | 没写测试的分支（**覆盖上限 = 测试质量上限**） |
| 安全性质被破坏（JWT 校验被绕过之类） | 跨模块/跨服务/真实数据库行为 |
| —— | 前端行为、样式、交互 |
| —— | 写的断言本身就是错的 |

**结论**：门禁从"能编译"提升到"核心规则没被改坏"。
再往上要的是集成测试与端到端测试，那是另一个量级的投入。

### 5.4 零散技术债

| 项 | 位置 | 性质 |
|---|---|---|
| 🔴 **初始管理员密码未改**（`admin` / `Pm@123456` 是公开的） | 服务器 | **安全问题，公网可访问** |
| `deploy.sh` 健康检查只盯前端 | 两项目 | 阶段三 |
| Gitee 图形视图能否用界面配置 `dependsOn`（根治编辑器删字段） | 流水线 | 待验证 |
| `backend/Dockerfile` 第 27 行过时注释（写着"测试在 CI 跑"，实际没有） | pm-system | 顺手清理 |
| Cloudland 首页标语不统一 | `HomeView.vue` / `LoginForm.vue` | 低优先 |

---

## 6. 补全计划

### 阶段一：回滚能力 —— ✅ 机制已实现并验证（2026-09-15，两个项目）

**状态**：tag 版本化**已验证生效**；pm-system 回滚机制**已实测通过**。
⚠️ **但 ACR 上的历史镜像因误删全部丢失，当前没有可回滚的目标**——
需靠后续推送重新积累（§6.1 事故记录、§7.11）。

**目标**：能退回任意历史版本。

**为什么优先**：之前线上出问题**没有退路**，只能改代码重新推。
回滚能力是「能部署」与「可运维」的分界线 —— 这一条已经跨过去了。

#### 实现方式

镜像 tag 从 `latest` 改成 **commit SHA**。`latest` 是"移动靶"，无法指认版本；
SHA 是不可变的，天然具备回滚与审计能力。

| 位置 | 改动 |
|---|---|
| `.workflow/流水线-202609111651.yml` | 两个 `build@docker` 的 `tag:` 改为 `pm-backend:${GITEE_COMMIT}` / `pm-frontend:${GITEE_COMMIT}` |
| `docker-compose.yml` | 两个 `image:` 的 `:latest` 改为 `:${IMAGE_TAG:?IMAGE_TAG is not set - deploy via scripts/deploy.sh}` |
| `scripts/deploy.sh` | 计算 `IMAGE_TAG`、诊断 `GITEE_COMMIT`、`pull` 失败显式报错退出、部署后记录当前 tag |
| `scripts/rollback.sh` | **新增**，回滚入口 |
| `.env.example` | 加警告：**不要**在里面定义 `IMAGE_TAG` |

#### 为什么用 `${GITEE_COMMIT}` 而不用 `${GITEE_PIPELINE_BUILD_NUMBER}`

| | `GITEE_COMMIT` | `GITEE_PIPELINE_BUILD_NUMBER` |
|---|---|---|
| 部署侧能否独立算出来 | ✅ `git rev-parse HEAD` | ❌ 流水线内部序号，服务器上无从得知 |
| 跨阶段传递 | 不需要（两边各自算，值相同） | 必须显式传，而 shell@agent 未必读得到 Gitee 变量 |

结论：**能用"同一事实两处独立推导"的地方，就别引入"跨阶段传值"**。
后者多一个可能失效的环节。

> **依据（非猜测）**：Gitee 官方《系统级变量》写明 `GITEE_COMMIT` = Push 的完整 commit
> （另设 `GITEE_SHORT_COMMIT` 为 7 位），且"可在**任何任务**内使用"；
> 《镜像构建》插件文档写明「镜像 Tag 支持引用流水线全局变量，示例 `app:v1.${GITEE_PIPELINE_BUILD_NUMBER}`」。

#### 回滚怎么用

```bash
cd /root/pm-system
/bin/bash scripts/rollback.sh --show            # 先看当前版本 + 最近 10 条提交
/bin/bash scripts/rollback.sh <commit-sha>      # 回滚（交互确认）
/bin/bash scripts/rollback.sh <commit-sha> --yes
```

`rollback.sh` 会依次做三件事，任一步失败都在**碰到线上之前**退出：

1. 用 `git rev-parse --verify <sha>^{commit}` 把短 SHA 解析成完整 SHA
2. 校验该提交的 `docker-compose.yml` 引用 `IMAGE_TAG` —— **拦掉改造前的提交**
   （那些提交的 compose 写死 `:latest`，回滚过去会拉到错的镜像）
3. `docker pull` 两个目标镜像，确认 ACR 上真的存在

然后交给 `deploy.sh`：`git checkout --detach <sha>` + `IMAGE_TAG=<sha>` 重新部署。
**代码与镜像退到同一个提交**，两者严格一致。

#### 三条必须知道的边界

1. **只能回滚到"阶段一改造之后"的提交。** 更早的提交没有 SHA tag 镜像，
   脚本会拦下来。之前那些镜像就是不存在，这是客观事实。
2. **回滚是临时止血，不是永久倒退。** 回滚后线上处于 detached HEAD，
   下一次流水线部署会 `checkout main` + `reset --hard origin/main`，自动回到最新。
   要永久倒退就用 `git revert <bad-commit> && git push`（方式 A）。
3. **`pull` 失败 = 线上没被碰过。** 因为 `pull` 排在 `up -d` 之前且失败即退出，
   拉不到镜像时旧容器照常运行。这是刻意的顺序设计，别调换。

#### 验收标准（怎么证明它真的能用）

```bash
# 1) 正常部署一次，确认日志里 tag 是完整 SHA，且容器用的是它
tail -50 /var/log/pm-system-deploy.log
cat /var/log/pm-system-current-tag
docker inspect -f '{{.Name}} -> {{.Config.Image}}' pm-backend pm-frontend

# 2) 故意改坏前端一行文案并 push，等它上线

# 3) 回滚到上一条提交
cd /root/pm-system && /bin/bash scripts/rollback.sh --show
/bin/bash scripts/rollback.sh <上一条提交的SHA>

# 4) 确认页面回到改坏之前的样子
curl -I http://127.0.0.1:8081
```

#### 验证结果：✅ 机制已证实（2026-09-15 12:29）

**证据**：推送后 Gitee 流水线全绿，构建与部署两个阶段都通过。

**但"绿"本身不是证据** —— 要看清为什么绿能证明 `${GITEE_COMMIT}` 生效。
下面是一条**闭合的推理链**，每一环都由代码结构强制：

| 环 | 事实（来自代码） | 若变量没生效会怎样 |
|---|---|---|
| ① | compose 的 image 是 `${IMAGE_TAG:?...}`，变量缺失时 compose 直接报错 | `IMAGE_TAG` 没传到 → compose 报错 → **流水线红** |
| ② | `$DC pull backend frontend` 排在 `up -d` **之前**，失败即 `exit 1` | tag 在 ACR 上不存在 → pull 失败 → **流水线红** |
| ③ | `docker compose pull` 与 `up` 不同 —— 它**必然访问远端**，不吃本地镜像缓存 | 本地有同名镜像也救不了，必须远端真的存在 |
| ④ | 部署侧拉的是 `...pm-backend:$(git rev-parse HEAD)` | —— |
| ⑤ | 该 tag 的镜像是构建阶段产的，而其 tag 字段是 `pm-backend:${GITEE_COMMIT}` | —— |

**结论**：流水线绿 ⇒ ②③ 成立 ⇒ ACR 上确实存在以「本次推送的 commit SHA」命名的镜像
⇒ 而它只能由构建阶段产出 ⇒ **`${GITEE_COMMIT}` 被替换成的正是该 commit SHA**。
即构建侧的 `${GITEE_COMMIT}` 与部署侧的 `git rev-parse HEAD` **取值完全一致**，
整条链路唯一的隐性契约成立。

> ⚠️ **注意这个推论只在 pull-fail-fast 的设计下成立。**
> 如果当初把 pull 写成 `|| true`、或让它排在 `up -d` 后面，
> 那么 tag 错了也会"绿着部署成功"，这个证据链就断了。
> —— 顺带说明为什么"让失败暴露"比"让失败被容忍"更值钱。

**DIAG 证据已收集（2026-09-15）**：`GITEE_COMMIT` 在 `shell@agent` 里**可读**，
且等于当次部署的 tag。据此已把并发 push 竞态改为「过期即跳过」策略
（**不是**简单地改用 `GITEE_COMMIT` 锚定 —— 那会导致顺序倒退）。详见 §7.10。

```bash
# 随时可复查：这行现在也兼作审计线索（哪次构建把版本推上线的）
grep 'DIAG GITEE_COMMIT' /var/log/pm-system-deploy.log | tail -3
```

#### 回滚验收：✅ 机制已通过（2026-09-15 14:34，pm-system）

> 🔴 **但当前没有可回滚的目标 —— 见下方「15:05 误删事故」。**
> 验收结论在「回滚机制正确」这一层成立，**不等于现在能回滚**。
> 需要靠后续推送重新积累 SHA tag 才能恢复。

执行 `rollback.sh faa9821`（从 `393f8bd` 退回），日志逐步印证了整条回滚路径：

| 日志行 | 证明了什么 |
|---|---|
| `Verifying images exist in ACR` → 两张镜像拉取成功 | ACR 上**保留了历史 tag 镜像** |
| `Rollback target : faa9821...` / `Current HEAD : 393f8bd...` | 短 SHA 解析成完整 SHA，两个版本被正确区分 |
| 要求输 `yes` → 输入后继续 | 交互确认生效（没有非交互静默退出） |
| `Rollback mode: checking out faa9821...` | **回滚专属代码路径**被走到（平时部署不走这里） |
| `HEAD is now at faa9821` | 代码真的退到目标提交 |
| `Image tag : faa9821cde4c690b4def0a9833b2f7f153e66fb8 (source: git rev-parse HEAD)` | tag 从**新 HEAD** 推导，与目标一致 |
| `docker compose pull` → backend/frontend 均 Pulled | 旧 tag 从 ACR 拉成功 |
| 4 个容器 `Started` / mysql `Healthy` | 用旧镜像重建成功 |
| `Frontend container is running` | 健康检查通过 |
| `docker compose ps` 显示两个容器镜像 = `:faa9821...` | **最终状态正确**（这是最硬的证据） |
| `http://8.137.114.176:8081` 返回 200 | 回滚后服务正常 |

**结论：阶段一验收标准「能部署上一版镜像并确认服务正常」已满足。**

##### 🔴 15:05 误删事故：回滚能力归零

验收通过仅 30 分钟后，清理废弃的 `:latest` 时**误点了仓库列表的「删除」**，
四个应用仓库（`pm-backend` / `pm-frontend` / `cloudland-backend` / `cloudland-frontend`）
连同**全部 SHA tag** 一并消失。

| | |
|---|---|
| 生产 | ✅ 未受影响（容器在跑，用服务器本地镜像） |
| 基础镜像（maven/node/nginx/openjdk/eclipse-temurin） | ✅ 完好 —— **构建不受影响** |
| 应用镜像历史 tag | ❌ 全丢：`faa9821` `039ec3c` `393f8bd` `467affe` `442178c` `87c63f7` `dd0ea40` |
| 可恢复性 | ❌ ACR 无回收站，官方明确不支持恢复 |

**处置决定（2026-09-15 15:09）**：**不恢复，接受损失，往前走。**
理由：丢掉的版本与当前线上版本在**功能上几乎一致**（差异仅文档与部署脚本），
救回来只恢复"退回一个永远不会退的版本"的能力，无实际价值。
本地镜像若还在本可 `docker push` 回 ACR 重建，但同样不值得投入。

**由此产生的两条实际后果**：

1. **回滚能力暂时为零** —— 需靠后续推送重新积累。
   **恢复到"可回滚"的条件是：再推 2 次**（第 1 次产生唯一版本，第 2 次才有可退的目标）。
   对应 §7.11「验收回滚前必须先有两个版本」。
2. **本次修复失败是安全的**：`rollback.sh` 会在「预拉目标 tag」阶段发现 ACR 上没有，
   **在碰线上之前退出**。这条防线从纸面设计变成了实战验证过的真防线。


##### 这次验收顺带证明的两件事（容易被当成理所当然）

1. **ACR 确实保留了历史 tag 镜像。**
   整条回滚链路的前提是「旧版本的镜像还在」。如果 ACR 会被清理、
   或者部署时顺手 `docker rmi` 掉旧镜像并推到远端，回滚就是空谈。
   **这条前提必须被验证过，不能假设。**

2. **旧提交的 compose 与当前的 `.env` 仍然兼容。**
   见下面的「隐性依赖」—— 这是回滚最容易被忽略的风险点。

##### ⚠️ 回滚的隐性依赖：`.env` 不会跟着回滚

`.env` 在 `.gitignore` 里，**它不参与版本控制**，所以：

```
回滚时：代码退回旧提交 ✔    但 .env 仍是当前的 ✘
```

于是「旧 compose / 旧代码」会去读「新的 .env」。这次没事，因为环境变量 schema 没变。
但若将来发生过这些改动，回滚就会失败或被静默降级：

| 场景 | 后果 |
|---|---|
| 新版本**新增**了一个必需环境变量，回滚到添加之前 | 旧代码读不到该变量 → 启动失败或走错默认值 |
| 新版本**删除/改名**了变量，回滚到使用旧名的版本 | 同上 |
| 新版本改过 `DB_URL` 之类的连接串格式 | 旧代码解析不了 |

**所以回滚验证要在"改过 .env schema"之后重做一次**，
或者至少在回滚前 `diff` 一下两个版本对 `.env` 的要求：

```bash
# 看两个版本之间 .env.example / compose 的环境变量差异
git diff faa9821 393f8bd -- .env.example docker-compose.yml
```

`.env` 本身建议**在服务器上留一份带日期的备份**，回滚前手动切换。

##### 还没做的：Cloudland 的回滚验收

脚本结构完全同源，`ensure_host_nginx_stopped` 也在每次正常部署里持续验证着。
想稳妥就跑一次 `rollback.sh 442178c`（Cloudland 可回滚的最早边界）。

**可回滚边界**（已用 git 逐提交核对）：
- pm-system：`393f8bd` / `039ec3c` / `faa9821`；`0b5bb18` 及更早**不可回滚**
- Cloudland：`87c63f7` / `442178c`；`92c681c` 及更早**不可回滚**

---

### 阶段二：补上 CI（单元测试）—— ✅ 已实现并本地验证（2026-09-15），待云端验证

**目标**：让流水线能拦住「功能错误的提交」。

#### 核心思路：让测试门禁直接复用已有的构建门禁

```
测试失败 → Dockerfile 里的 mvn package 失败 → 镜像构建失败
        → 部署被流水的 dependsOn 挡住 → 线上不受影响
```

**不需要新增任何流水线步骤** —— 阶段一建立的 `dependsOn` 门禁继续用。
代价是「测试失败」会表现为「镜像构建失败」，报错位置在构建阶段而非独立 job
（要更早更清楚的报错，才考虑加回 `build@maven` 步骤单独跑 `mvn test`）。

#### 做了三件事

**1. 补上真正能跑在云端的纯单元测试**

| 测试类 | 覆盖 | 用例数 |
|---|---|---|
| `RequirementStageServiceImplTest` | **需求阶段派生**（`deriveStage`）—— 系统的核心业务规则：排期数据 + 日期 = 唯一真相源 | 17 |
| `JwtUtilTest` | JWT 签发/校验/**防篡改/防伪造/过期** —— 安全性质 | 6 |
| `PmApplicationTests` | 上下文冒烟 —— **`@Disabled`**（要真实 MySQL，云跑不了） | 1（跳过） |

这两个类都**不启动 Spring 上下文、不连数据库**：
- `deriveStage` 是无副作用纯函数，五个注入依赖在它里面完全用不到 → 构造时给 `mock()` 即可
- `JwtUtil` 的构造器只是普通参数（Spring 用 `@Value` 注入，但测试里直接 `new` 就行）

**2. 处理掉那个卡住门禁的 `@SpringBootTest`**

`PmApplicationTests` 加 `@Disabled` 并写清原因与替代做法。
**它才是当初被迫加 `-DskipTests` 的根源** —— 不处理它，摘掉 `-DskipTests` 就必然构建失败。

想让它也能上云，需要 Testcontainers 或 H2 内存库 + 测试建表脚本 ——
那是一件事，不是顺手能做的，等真需要端到端冒烟时再单独做。

**3. 摘掉 `-DskipTests`，并修掉那句过时注释**

```dockerfile
# 改前：RUN mvn -s ./settings.xml -B clean package -DskipTests
#       注释写着"测试在 CI 的独立 job 里跑"—— 那个 job 从来不存在
# 改后：RUN mvn -s ./settings.xml -B clean package
```

#### 验收：本地已跑通，且证明门禁真的能拦错

**① 正常状态**：`mvn -B clean test` → **24 个测试，0 失败，1 跳过，BUILD SUCCESS**（17.9 秒）

**② 故意改坏业务逻辑**：把 `deriveStage` 里的一处判据从

```java
g.getTotal().compareTo(g.getPlannedEffort()) < 0    // 排满判据
```

改成 `<= 0`（一个字符的改动），重跑：

```
[ERROR] Tests run: 24, Failures: 9, Errors: 0, Skipped: 1
[ERROR] BUILD FAILURE
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.1.2:test
```

**9 个用例失败、构建失败。** 其中 `totalEqualsPlannedIsFull` 精确地抓住了它 ——
这正是**特意为这个边界写的那条用例**（`<` 与 `<=` 的差别）。
这就是阶段二要的效果：**能编译但逻辑写错的提交，现在上不了线。**

**③ 还原**：改回 `< 0`，重跑 → 24 个测试全绿，`git diff backend/src/main` 无残留。

#### 云端验收（还没做）

推一次，然后到 Gitee 流水线页面确认：

- 「构建后端镜像」阶段日志里能看到 **`T E S T S`** 与 `Tests run: 24`；
- 想验证门禁真的生效，就**推一个故意改坏逻辑的提交** → 构建阶段红 → 部署阶段未执行 → 线上不动。

**验收标准**：故意改坏一个业务逻辑 → 流水线红了 → 线上未受影响。

#### 这个方案保护不了什么（说清楚边界）

- 测试是**开发自己写的**。写漏的分支、写错的断言，门禁一样放行。
  测试的价值上限 = 测试的质量上限。
- 只覆盖**纯逻辑**。跨模块、跨服务、真实数据库行为、前端行为都不在其中。
- 云端构建时间会变长（多跑一遍测试，约十几秒），可接受。


---

### 阶段三：健康检查补上后端 —— ✅ 已实现（2026-09-15），待服务器验收

**目标**：后端崩溃不再被漏报成「部署成功」。

**改动前**：两个项目的 `deploy.sh` 都只有 `wait_for_frontend()`，**只 `docker inspect` 前端容器**。

#### ⚠️ 原方案有漏洞 —— 照抄会验收不过自己的标准

本文档原先给的改法是「`wait_for_container` 检查 `status == running`」。**这不够**：

1. `docker compose up -d` 返回时，**Spring Boot 还在 booting**（实测启动 ~12s），
   此刻 `status` 必然是 `running` —— 什么都不证明。
2. 启动即崩的容器（如 DB 连不上）在 `restart: unless-stopped` 下会**反复重启**，
   而每轮 JVM 要 boot 十几秒才崩，**所以容器绝大部分时间都处于 running**。
   于是「查到 running 就通过」会在崩溃发生**之前**就放行。

**结果就是：改坏 DB_URL 的验收测试照样会「部署成功」。**

#### 实际采用的条件：三个，缺一不可

```bash
wait_for_container() {
  local name="$1" min_stable="${2:-0}" retries=18 delay=5
  local status="" restarts=0 first_running=0 up=0
  for _ in $(seq 1 "$retries"); do
    status="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || true)"
    restarts="$(docker inspect -f '{{.RestartCount}}' "$name" 2>/dev/null || echo 0)"
    if [ "$status" = "running" ]; then
      if [ "$first_running" -eq 0 ]; then first_running="$(date +%s)"; fi
      up=$(( $(date +%s) - first_running ))
      if [ "$restarts" -eq 0 ] && [ "$up" -ge "$min_stable" ]; then
        log "Container $name healthy: stable ${up}s (need >= ${min_stable}s), restarts=0"
        return 0
      fi
    else
      first_running=0; up=0
    fi
    sleep "$delay"
  done
  # ... 打印状态与日志后 exit 1
}

wait_for_container "$BACKEND_CONTAINER" 25   # 后端：要撑过启动期
wait_for_container "$FRONTEND_CONTAINER" 0   # 前端是 nginx，起来即就绪
```

| 条件 | 作用 |
|---|---|
| `status=running` | 容器活着 |
| **`RestartCount=0`** | **从创建起没崩过 —— 抓住 crash-loop 的关键**（`up -d --force-recreate` 保证容器是新建的，计数从 0 起） |
| **`up >= min_stable`** | **撑过了应用自身的启动期**，排除「刚起来还没崩」的假阳性 |

**已知局限（有意不补）**：应用「卡死但不退出」查不出来，那需要 HTTP 探活。
等真的出现过这类故障再加 —— 别为没发生过的故障加复杂度。

#### 验证：逻辑层已用模拟时钟跑过（本地）

本地无 Docker 守护进程，但需要验证的是**判断逻辑**（三个条件的组合），
不是 `docker inspect` 那一行调用。用可控的 `docker` / `sleep` / `date` 替身，
精确控制"每一时刻容器是什么状态"：

| 用例 | 场景 | 结果 | 关键证据 |
|---|---|---|---|
| A | 健康容器（min_stable=3） | **PASS** | `stable 4s (need >= 3s), restarts=0` |
| B | 5 秒后崩溃（min_stable=8） | **FAIL** | `status=running, restarts=2, **stable=8s (need >= 8s)**` |
| C | **旧逻辑**（只查 status）+ 同一场景 | **PASS** ← 漏报 | 首次轮询即放行 |

**B 的日志是决定性的**：`stable=8s` 已满足 `min_stable`，**拦住它的只有 `restarts=2`**
—— 证明 RestartCount 条件是承重的，不是装饰。
**C 用同一场景跑原方案直接放行 —— 漏洞是实测出来的，不是推演出来的。**

⚠️ 模拟刻意采用**对旧方案最有利**的假设（`status` 永远返回 `running`），
这正是 Spring Boot 启动期崩溃的真实形态。

#### 服务器端验收（还没做）

```bash
# 临时把 DB_URL 改坏，然后手动部署一次
cd /root/pm-system
cp .env .env.bak
sed -i 's#^DB_URL=.*#DB_URL=jdbc:mysql://mysql:9999/nope#' .env
/bin/bash scripts/deploy.sh main          # 期望：健康检查 FAILED，打印后端日志，退出非 0
cp .env.bak .env                          # 恢复
/bin/bash scripts/deploy.sh main          # 期望：恢复正常
```

**验收标准**：部署报失败并打印日志，且**线上旧版本未被搞停**（失败发生在 `up -d` 之后，
容器会被重建 —— 所以这次验收**会短暂影响线上**，选个低峰时段做）。

> 注意：健康检查失败是在 `up -d` **之后**触发的，此时新容器已经起来了。
> 也就是说**这一层检查保护的是"不会被误报成功"，不是"线上不会被搞坏"**。
> 真正阻止坏版本上线的还是 `pull` 前置 + 镜像构建门禁（阶段二会再补测试门禁）。

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
**还可能改掉 `tag:` 里的 `${GITEE_COMMIT}` 变量**（后果更隐蔽：镜像 tag 变成错的，
但流水线照样"绿"，直到部署时 pull 失败才暴露）。

> **阶段一的改动手写进了 YAML。改完之后千万不要用网页编辑器保存这个文件。**

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
6. **让失败"吵"，不要让它"静" —— 这本身就是可验证性。**
   这次只凭"流水线绿了"就能证明 `${GITEE_COMMIT}` 生效，唯一原因是
   `pull` 排在 `up -d` 之前且失败即 `exit 1`（§6.1 证据链的 ②③ 环）。
   若当初写成 `$DC pull ... || true`，tag 错了也会"静默部署成功"，
   验证就只能靠人肉翻日志比对。
   **设计每一步时都问一句：这一步坏了，我怎么知道？** 答不上来就说明它会静默失败。

### 7.10 ✅ 并发 push 的竞态：已实测 + 已用「跳过策略」解决

改成 SHA tag 之后**新引入**的失效模式，`latest` 时代不存在（因为两边都是"最新"）。

**机制**：

```
T0  push C1  → 流水线开始构建 tag=C1
T1  push C2  → origin/main 立刻变成 C2
T2  流水线（C1 那条）的部署阶段 reset --hard origin/main  →  HEAD=C2
                IMAGE_TAG=C2，但 ACR 上可能还没有 C2 的镜像
                → pull 失败 → 流水线红，线上不受影响
```

#### 实测结果：`GITEE_COMMIT` 在 shell@agent 里**能读到**

```
14:26:24  DIAG GITEE_COMMIT=393f8bd...  GITEE_BRANCH=main  GITEE_PIPELINE_BUILD_NUMBER=36   ← 流水线触发
14:34:06  DIAG GITEE_COMMIT=<unset>                                                          ← 手工回滚
```

**所以本文档早先"shell@agent 不保证能读到 Gitee 系统变量"的判断是错的。**
实测依据：Gitee《系统级变量》说 `GITEE_*`「可在任何任务内使用」，
且 `shell@agent` 这条**确实**收到了逐提交的值 —— 以日志为准（§7.9.3）。

#### 但别急着"改用 GITEE_COMMIT 当 tag" —— 那会引入更危险的 bug

一个自然的想法是：部署时锚定到 `GITEE_COMMIT`，代码和镜像都用它。**这是错的**：

```
流水线 1（C1）跑得慢，流水线 2（C2）先部署成功
流水线 1 随后才执行部署 → 锚定 C1 → 把线上从 C2 退回 C1
```

**顺序倒退，而且完全静默。** 比原来那个"拉不到镜像就报错"危险得多。
（原来那种失败至少是**吵**的，线上也不会变。）

#### 采用的对策：检测到过期就跳过

```bash
if [ -n "${GITEE_COMMIT:-}" ] && [ "$GITEE_COMMIT" != "$(git rev-parse "origin/$BRANCH")" ]; then
  log "Skipping deploy: built commit ... is no longer the tip of main"
  log "  本次不动线上 —— 那条更新的流水线会负责部署最新版本。"
  exit 0
fi
```

三种坏结果一个都不会发生：

| 方案 | 并发 push 时 |
|---|---|
| 照旧 `reset --hard origin/main` | 新代码 + 旧提交镜像 → `pull` 报 manifest unknown → 白跑（吵、安全） |
| 锚定 `GITEE_COMMIT` | 可能在新版本之后落地 → **顺序倒退**（静、危险） |
| **过期即跳过（当前）** | 线上不动、流水线绿、日志说明原因；更新的那条负责上线 ✓ |

`TARGET_COMMIT`（回滚）**不参与**这个判断 —— 回滚本来就是要倒退，是有意为之。

> 一句话：**"部署哪个版本"这件事，只有在"它就是当前最新"时才成立。**
> 一旦构建与部署之间隔了时间，就必须重新确认这个前提，而不是假定它还成立。

`DIAG` 那行现在不是临时诊断，而是**永久保留的审计线索**：
出问题时能直接对上是"哪次构建"把版本推上线的。
手工执行时必然为 `<unset>`，属正常。


---

### 7.11 🟡 验收回滚之前，必须先存在「两个」版本

刚改造完、只推了一次时，ACR 上**只有一个** SHA tag，而它正是当前线上版本。
此时去执行回滚，脚本会正确提示「目标提交与当前 HEAD 相同，无需回滚」并退出 ——
**这不算验收通过，因为回滚路径根本没被走到。**

所以验收的顺序不能是"改造完立刻测回滚"，而是：

```
1. 再推一次（哪怕只改一行文档）→ 产生第二个 SHA tag 版本
2. 等它上线，确认线上是新版本
3. 回滚到第一个 SHA 版本
4. 确认线上确实退回去了
```

**同一条规则也决定了"事故后多久能恢复回滚能力"**：
ACR 被清空后，第 1 次 push 只产生唯一版本，**第 2 次 push 才重新具备可回滚性**。

> 推广：**任何"回退类"能力的验收，都需要至少两个状态**。
> 只有一个状态时，你验证的是"无事可做"，不是"能退回去"。

---

## 8. 关键文件索引

### 8.1 pm-system

| 文件 | 作用 |
|---|---|
| `.workflow/流水线-202609111651.yml` | 流水线：`build@docker`×2（tag=`${GITEE_COMMIT}`）+ `shell@agent` 部署，`dependsOn` 门禁 |
| `docker-compose.yml` | 4 服务；backend/frontend 用 ACR 镜像 + `${IMAGE_TAG:?}`；mysql 挂了 `init.sql` |
| `backend/Dockerfile` | 多阶段：`maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre`，FROM 均为 ACR；**`mvn clean package` 不带 `-DskipTests`**（测试即门禁） |
| `backend/src/test/.../RequirementStageServiceImplTest.java` | **需求阶段派生纯单测**（17 用例）—— 核心业务规则 |
| `backend/src/test/.../JwtUtilTest.java` | **JWT 安全性质单测**（6 用例）—— 防篡改/防伪造/过期 |
| `backend/src/test/.../PmApplicationTests.java` | 上下文冒烟，**`@Disabled`**（要真实 MySQL） |
| `frontend/Dockerfile` | `node:20-alpine` → `nginx:alpine`，COPY 路径带 `frontend/` 前缀 |
| `frontend/nginx.conf` | 静态托管 + `/api` 反代，**`$http_host`** |
| `scripts/deploy.sh` | 服务器部署：flock、解析 `IMAGE_TAG`、`pull`、`up -d`、健康检查、记录当前 tag |
| `scripts/rollback.sh` | **回滚**：解析 SHA → 校验目标提交可用 → 预拉镜像 → 交给 deploy.sh |
| `scripts/mirror-base-images.sh` | 基础镜像转存 ACR |
| `.env.example` | 环境变量模板（13 项 + `IMAGE_TAG` 禁令说明） |
| `.gitattributes` | `eol=lf` |
| `docs/部署改造完整指南.md` | 从零改造的完整教程（含 9 条坑） |

### 8.2 Cloudland

| 文件 | 作用 |
|---|---|
| `.workflow/流水线-202609111434.yml` | 同上；`Build Images` + `Deploy To Aliyun` |
| `docker-compose.yml` | backend/frontend 用 ACR 镜像；已有 `cloudland.sql` 挂载 |
| `Dockerfile` | `maven:3.8.6-openjdk-8` → `openjdk:8-jre-slim` |
| `vue/Dockerfile` | `node:16-alpine` → `nginx:alpine`，**COPY 带 `vue/` 前缀** |
| `scripts/deploy.sh` | 同结构（另有 `ensure_host_nginx_stopped`：80 端口被宿主机 nginx 占用时停它）+ `IMAGE_TAG` 解析 |
| `scripts/rollback.sh` | **回滚**：同 pm-system，容器名/端口按 Cloudland 调整 |
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
docker inspect pm-backend        --format '{{.Name}} -> {{.Config.Image}}'
docker inspect cloudland-backend --format '{{.Name}} -> {{.Config.Image}}'
# 应显示 crpi-.../t1longlive/xxx:<40位commit-sha>   ← 不再是 latest
```

### 回滚

```bash
cd /root/pm-system
/bin/bash scripts/rollback.sh --show           # 看当前版本 + 最近 10 条提交
/bin/bash scripts/rollback.sh <commit-sha>     # 回滚（--yes 跳过确认）
```

```bash
# 被 IMAGE_TAG 挡住时（手工敲 docker compose 命令）
cd /root/pm-system && IMAGE_TAG=$(git rev-parse HEAD) docker compose ps
```

### 查"线上到底是哪次提交"

```bash
cat /var/log/pm-system-current-tag             # deploy.sh 部署成功后写入
docker inspect -f '{{.Config.Image}}' pm-backend
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

- [x] **阶段一**：镜像 tag 版本化 + 回滚能力 —— 代码已上线；tag 机制已验证生效；pm-system 回滚机制已验证通过（§6.1）
- [ ] **阶段一收尾 A**：⚠️ **确认 Cloudland 服务器当前停在哪个版本** —— 14:57 发起过回滚（目标是 `442178c`），若确认了 `yes`，现在线上就在回滚点。查：`cat /var/log/cloudland-current-tag`，若是回滚点则用 `cd /root/cloudland && /bin/bash scripts/deploy.sh main` 带回最新
- [ ] **阶段一收尾 B**：清理废弃 `:latest` —— 🔴 **注意 15:05 的事故：必须先点「管理」进入仓库，只删镜像版本列表里 `latest` 那一行，绝不点仓库列表的「删除」**。若已误删（当前状态），跳过此项
- [ ] **阶段一收尾 C**：**重新积累回滚点** —— 每次 push 产生一个 SHA tag，**再推 2 次**才能重新具备"可回滚"能力（§7.11）
- [ ] **阶段一收尾 D**：确认下一次构建能正常推送 —— ACR 仓库被删过，若构建阶段报"仓库不存在"，先在控制台建 4 个同名**空**仓库
- [ ] **阶段一收尾 E**：`grep 'DIAG GITEE_COMMIT' /var/log/pm-system-deploy.log` —— 已实测有值并据此改掉竞态（§7.10），这行现为常驻审计线索
- [x] **阶段二**：补上 CI（单元测试）—— 23 个纯单元测试 + 摘掉 `-DskipTests` + `@Disabled` 掉 `@SpringBootTest`（§6.2）
- [ ] **阶段二收尾**：云端验证 —— 推一次，确认构建阶段日志里有 `Tests run: 24`；再推一个故意改坏逻辑的提交，确认构建红、部署未执行、线上不动
- [ ] **阶段二延伸（可选）**：`@SpringBootTest` 用 Testcontainers/H2 救活，让它也能上云；或加回 `build@maven` 独立测试 job 让报错更早更清楚
- [ ] 后续写新功能时**同步补测试** —— 门禁的价值随覆盖度增长，不补就等于没加
- [ ] **Cloudland 的阶段二**：它的 `src/test/` **一个测试都没有**，Dockerfile 也带 `-DskipTests`（门禁完全关闭）。要照搬这套：先摸它的业务代码找出纯逻辑，写测试，再摘掉 `-DskipTests`
- [ ] **阶段三收尾**：服务器上做一次验收 —— 改坏 `DB_URL` → 部署应报失败并打印日志。⚠️ 失败发生在 `up -d` 之后，**会短暂影响线上**，选低峰时段（§6.3）
- [ ] 记一笔：`.env` 不参与版本控制，**改过环境变量 schema 之后要重做一次回滚验收**（§6.1 隐性依赖）
- [ ] 确认 Gitee 图形视图能否用界面配置 `dependsOn`（根治编辑器删字段问题）
- [x] ~~清理 `backend/Dockerfile` 第 27 行过时注释（写着"测试在 CI 跑"，实际没有）~~ —— 阶段二已顺手修掉，并把测试真的接上了
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
