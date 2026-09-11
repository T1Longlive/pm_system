# 08 · 运行与部署

## 1. 环境前置要求

| 软件 | 版本/要求 | 说明 |
|------|----------|------|
| JDK | 17+（编译目标 `java.version=17`） | 后端运行 |
| Maven | 仓库自带（`backend/mvnw` / `mvnw.cmd` Wrapper，另备 `.tools/apache-maven-3.9.16/`） | 或系统 Maven，配合根目录 `settings.xml` 阿里云镜像 |
| Node.js + npm | 18+ | 前端开发/构建 |
| MySQL | 8.x | 数据持久化 |
| Redis | 5+ | 需求编号流水号 |

## 2. 数据库初始化

脚本：`backend/src/main/resources/db/init.sql`（建库 `pm_system` utf8mb4 + 13 张表 + 种子管理员，可重复执行部分以 `IF NOT EXISTS` 保护）。

```bash
mysql -uroot -p < "D:\IDEA_Project\pm-system\backend\src\main\resources\db\init.sql"
```

种子管理员：

```
账号：admin
密码：Pm@123456   （BCrypt 哈希存储，force_change_pwd=0，无需强制改密）
```

> 也可用 Navicat / DataGrip 等工具直接执行该 SQL 文件。

## 3. 配置核对（`backend/src/main/resources/application.yml`）

启动前确认以下配置与本机环境一致，否则修改：

```yaml
server:
  port: 8080                        # 后端端口（与前端代理目标一致）
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/pm_system?...&serverTimezone=Asia/Shanghai...
    username: root
    password: 123456
  data:
    redis:
      host: localhost
      port: 6379
      password: 123456              # 本机 Redis 已开启密码认证
jwt:
  secret: pm-system-jwt-secret-...  # 生产环境务必更换（HS256 ≥32 字符）
  expire: 7200                      # token 有效期（分钟）= 5 天
pm:
  upload-dir: D:/pm-upload          # 附件存储根目录（不存在会自动创建）
```

其他：multipart 上限 60MB/65MB（业务白名单图片 10MB / 文档 50MB）；MyBatis-Plus 开发期打印 SQL（`log-impl: StdOutImpl`，**生产请移除**）。

## 4. 启动后端

**方式 A —— Maven Wrapper（推荐）：**

```powershell
cd D:\IDEA_Project\pm-system\backend
.\mvnw.cmd spring-boot:run
# 网络受限（中央仓库拉不动）时指定镜像：
.\mvnw.cmd -s ..\settings.xml spring-boot:run
```

**方式 B —— 系统 Maven：**

```powershell
mvn -s "D:\IDEA_Project\pm-system\settings.xml" spring-boot:run
```

**方式 C —— IDEA：** 打开 `backend` 为 Maven 项目，运行 `PmApplication.main()`。

启动成功标志：
- 日志输出 `Tomcat started on port 8080`；
- `CalendarServiceImpl`（ApplicationRunner）自动预生成未来 12 个月工作日历并打印补生成天数；
- 健康检查：`POST http://localhost:8080/api/auth/login` 返回 `{"code":200,...}`。

## 5. 启动前端

```powershell
cd D:\IDEA_Project\pm-system\frontend
npm install
npm run dev
```

- 开发服务 `http://localhost:5173`；Vite 代理 `/api`、`/upload` → `http://localhost:8080`（`vite.config.ts`）。
- 浏览器访问 `http://localhost:5173`，用 `admin / Pm@123456` 登录。
- 构建会先跑 `vue-tsc --noEmit` 全量类型检查，再产出静态文件。

## 6. 建议的首次走查路径

1. admin 登录 → 首页看板（Dashboard）确认统计卡与状态分布图正常渲染（图表依赖 ECharts）。
2. 人员管理：新增几个成员（初始密码留空 = `Pm@123456`）。
3. 日历管理：把某天设为节假日/调休，观察排期页灰显变化。
4. 创建项目（缩写 2-6 位大写字母）→ 项目详情添加成员并勾选工种。
5. 需求管理：新建需求（选项目、富文本、参与人按工种指定）。
6. 排期管理：生成下月排期表 → 从需求池添加需求（自动按参与人生成行）→ 在工作日格子里填 0.1~1 的量。
7. **观察状态派生**：把某需求**全部排期组**的工作日格子填满 → 保存后状态自动变 `开发中`；把开发工种格子日期全部排到过去（可把日历日期改早或等次日）→ 每小时对账后自动进 `测试中` → `待归档`。**无需手点「完成」**——这是状态派生化后的新行为。
8. 待归档后 PM 手动归档；顶部铃铛查看通知流转。
9. Bug 管理：选项目 → 「+ 新增 Bug」（编号自动生成）→ 单元格内填写功能模块/优先级/负责人等 → 上传问题截图或视频 → PM/管理员归档到归档表 → 重新打开观察进度清空。
10. 操作日志页：以 admin 登录看全量日志，以某项目 PM 登录确认只看自己项目；日志中应能看到 `STAGE_SYNC`（状态派生）记录。
11. 报表页：切换 `months` 区间查看工作量柱图变化。
12. 全局搜索：主页搜索框输入需求/Bug/项目/人名关键词，确认四类分组各返回结果。
13. 新建一个用户（初始密码 `Pm@123456`）→ 用它登录 → 验证强制跳转改密页，改密后才能进入系统。

## 7. 打包与生产部署

**后端打包：**

```powershell
cd D:\IDEA_Project\pm-system\backend
.\mvnw.cmd clean package          # 产物 target/pm-backend-1.0.0-SNAPSHOT.jar
java -jar target/pm-backend-1.0.0-SNAPSHOT.jar
```

**前端构建：**

```powershell
cd D:\IDEA_Project\pm-system\frontend
npm run build                     # 产物 dist/（需通过 vue-tsc 类型检查）
```

**Nginx 参考配置：**

```nginx
server {
    listen 80;
    root /var/www/pm-system;          # frontend/dist

    location / {
        try_files $uri $uri/ /index.html;   # SPA history 路由 fallback
    }
    location /api {
        proxy_pass http://127.0.0.1:8080;
    }
    location /upload {
        proxy_pass http://127.0.0.1:8080;   # 附件静态资源
    }
}
```

**生产检查清单：**

1.更换 `jwt.secret` 与数据库/Redis 密码（勿用仓库示例值）。
2. 移除 `log-impl: StdOutImpl` SQL 日志。
3. CORS 收敛 `allowedOriginPatterns`（当前为 `*` + allowCredentials）。
4. `/upload/**` 静态资源无鉴权（拿到 URL 即可访问），敏感附件需另行加固；`pm.upload-dir` 指向持久化磁盘并纳入备份。
5. 定时任务（每小时 **全量状态对账** `syncAllStages`）随进程运行，**多实例部署会重复执行**，建议单实例开启或加分布式锁。注意启动时也会对账一次（`ApplicationReadyEvent`）。
6. 通知表注释约定保留 90 天（清理任务未实现），需人工或自行实现清理。

## 8. 常见问题（FAQ）

| 现象 | 原因与处理 |
|------|------|
| Maven 依赖下载失败/超时 | 未走镜像：加 `-s ..\settings.xml`（阿里云镜像），或直接用仓库内 `.tools/apache-maven-3.9.16` |
| 后端启动报 Redis 连接失败/认证错误 | `application.yml` 中 Redis 密码/端口与本机不一致 |
| 后端启动报 `Unknown database 'pm_system'` | 未执行 `init.sql`，见第 2 节 |
| 登录提示「用户名或密码错误」 | 种子管理员 `admin/Pm@123456`；或该账号被新建/重置密码（初始密码 `Pm@123456`） |
| 登录后跳到改密页无法进入系统 | 设计行为：新建/重置密码用户 `force_change_pwd=1`，改密成功后自动放行；也可「暂不修改，退出登录」 |
| 登录后接口全部 401（「登录已过期」循环跳转） | 前后端 `jwt.secret` 不一致（改过配置）或 token 过期（5 天） |
| 前端页面空白且控制台 404 | 直接访问了未定义路由（无 404 catch-all）；或未从 `/` 进入 |
| 排期格子无法编辑 | 三条件同时满足才可编辑：行状态 TODO、本人是该需求项目 PM、当天为可排班日（WORKDAY/ADJUSTED） |
| 保存排期提示「数据已被他人修改」 | 乐观锁 409，整表刷新后重试（设计行为） |
| 需求状态「自己变了」 | 设计行为：状态由排期数据 + 日期**派生**，每小时定时对账刷新。查 `biz_operation_log` 中的 `STAGE_SYNC` 记录可见变更轨迹 |
| 需求状态改了但没通知 | `STAGE_SYNC` 属于系统对账不通知；只有用户触发（如保存排期导致的进 DEV）才发通知 |
| 看板/报表图表空白 | ECharts 未正确按需引入或容器无高度；检查 `Dashboard.vue` / `Reports.vue` 的图表容器样式 |
| 退出排期页被拦「有未排满的需求」 | 设计行为：后端 `/schedule/incomplete` 只对**当前用户为 PM** 的需求拦截，普通成员不拦 |
| Bug 页面列表空白/提示「请先选择项目」 | 设计行为：进入 Bug 管理先在顶部选择项目，再加载总表/归档表 |
| Bug 单元格无法编辑 | 该行已归档（归档表只读，仅 PM/管理员可重新打开回总表） |
| 上传报「不支持的文件类型」/「上传文件过大」 | 白名单：图片 png/jpg/jpeg/gif/webp ≤10MB；文档 pdf/doc/docx/xlsx/zip ≤50MB |
| 富文本图片不显示 | 图片经 `/upload/...` 访问，确认 Vite 代理或 Nginx `/upload` 转发正常、`pm.upload-dir` 未变 |

## 9. 快速自检清单

- [ ] MySQL 已启动、`init.sql` 已执行、admin 可登录
- [ ] Redis 已启动且 `application.yml` 密码/端口一致
- [ ] 后端 8080 启动成功（日历预生成日志可见 + 启动对账日志）
- [ ] 前端 5173 起服，登录后菜单完整（管理员多出「日历管理」；PM/管理员可见「操作日志」）
- [ ] 首页看板统计卡与 ECharts 状态分布图正常渲染
- [ ] 创建项目 → 添加成员 → 创建需求 → 排期填满 → **状态自动进「开发中」** 全流程走通
- [ ] 报表页三块图表可切换 `months` 区间刷新
- [ ] 全局搜索四类分组均返回结果
- [ ] 操作日志：admin 见全量、PM 只见自己项目；可见 `STAGE_SYNC` 记录
- [ ] Bug 管理：选项目 → 新增 → 单元格编辑 → 归档/重新打开 走通；日志页可见 Bug 操作记录
- [ ] 新用户首登被强制改密，改密后正常进入
- [ ] 顶栏铃铛未读数轮询正常（60s）
