# 04 · 认证鉴权、统一响应与异常处理

## 1. JWT 认证机制

系统采用**无状态 JWT**，未引入完整 Spring Security，仅用 `spring-security-crypto` 的 BCrypt 做密码哈希，认证由自定义拦截器实现。

### 1.1 三件套（`com/pm/security/`）

| 类 | 职责 |
|----|------|
| `JwtUtil` | token 签发与解析。HS256（`Keys.hmacShaKeyFor`，secret 取 `jwt.secret`，≥32 字符）；`subject` = userId，claim `username`；有效期 = 签发时刻 + `jwt.expire`（7200 分钟 = 5 天）。方法：`generateToken(Long userId, String username)`、`parseToken(String token)`（失败抛 `JwtException/IllegalArgumentException`） |
| `JwtInterceptor`（HandlerInterceptor） | 拦截 `/api/**`（注册时排除白名单 `/api/auth/login`）。校验 `Authorization: Bearer <token>`：缺失/格式错 → 业务 401「未登录或缺少 token」；解析失败 → 业务 401「token 无效或已过期」。成功后把 `userId`（Long，来自 subject）与 `username` 写入 **request 属性**，供 Controller/Service 读取。注意：401 是以 **HTTP 200 + body code=401** 输出的 |
| `CurrentUser`（静态工具类） | 非 ThreadLocal、非注解：`@PostConstruct` 把 Spring 注入的 `UserMapper` 赋给静态字段。`getUserId(request)` 读拦截器写入的 userId；`getAdminUserId(request)` 额外查库校验 `is_admin`，非管理员抛 `BizException(403,"无权限")` |

### 1.2 请求鉴权链路

```
客户端（axios 请求拦截器自动加 Bearer token）
   │
   ▼
JwtInterceptor.preHandle（/api/**，白名单 /api/auth/login）
   ├─ 无/格式错的 Authorization ──► HTTP 200 + {code:401, message:"未登录或缺少 token"}
   ├─ parseToken 失败/过期     ──► HTTP 200 + {code:401, message:"token 无效或已过期"}
   └─ 通过 ──► request.setAttribute("userId"/"username")
                  │
                  ▼
Controller（CurrentUser.getUserId(request) 取当前用户）
   └─ Service 细粒度授权：管理员（getAdminUserId）/ PM / 创建人 / 行负责人 / 状态可操作域
```

### 1.3 放行路径

- `/api/auth/login` —— 登录接口（WebConfig 注册拦截器时 `excludePathPatterns`）。
- `/upload/**` —— 附件静态资源（WebConfig 静态映射，**不经过 JWT 拦截器**，拿到 URL 即可访问，生产环境需注意该边界）。

## 2. 三层权限模型

| 层 | 判定 | 典型校验 |
|----|------|------|
| 全局管理员 | `user.is_admin=1`（`CurrentUser.getAdminUserId`） | 人员管理 CRUD、创建项目、创建月表、维护日历、Bug 归档/重新打开 |
| 项目 PM | `project.creator_id == 当前用户` | 项目编辑/成员管理、需求创建（或 PLANNER 成员）/编辑/取消/归档、排期加需求/填格子、代点完成、Bug 归档/重新打开 |
| 资源归属人 | `requirement.created_by` / `schedule_row.user_id` | 需求创建人可编辑；排期行负责人可点完成 |
| 所有登录用户 | — | **Bug 模块**：编辑/新增/删除未归档 Bug、增删截图（Excel 化模型的有意放权） |

权限不足统一抛 `BizException(403, "无权限")`。前端配合：路由 `meta.admin` 守卫 + 列表/详情 VO 中的 `myRole: string[]`（含 `PM` 或工种码，空数组 = 只读）控制按钮显隐。

## 3. 统一响应结构 `common/Result<T>`

```json
{ "code": 200, "message": "success", "data": { } }
```

- `Result.success()` / `Result.success(data)` → code=200；`Result.error(code, message)` → 业务错误。构造器私有，只能走工厂方法。

## 4. 错误码约定（前后端契约）

| code | 含义 | 典型场景 | 前端处理（`api/request.ts`） |
|------|------|------|------|
| 200 | 成功 | — | 从 `res.data.data` 取数据 |
| 400 | 参数/业务校验失败 | 名称超长、非工作日格子、重复月表、状态不允许操作 | `ElMessage.error(message)`，页面无需重复弹错 |
| 401 | 未登录 / token 失效 | 拦截器 401、HTTP 401 | 清 token → 提示「登录已过期」→ 跳 `/login` |
| 403 | 无权限 | 非管理员操作管理接口、非 PM 操作项目 | 统一错误提示 |
| 404 | 资源不存在 | 用户/成员/行不存在 | 统一错误提示 |
| 409 | 乐观锁冲突 | 需求编辑、排期行保存时 `updatedAt` 不一致 | 提示「数据已被他人修改」并整表/详情刷新 |
| 500 | 系统异常（兜底） | 未捕获异常（HTTP 500） | 统一错误提示 |

> 除兜底 `Exception` 返回 HTTP 500 外，**其余错误均为 HTTP 200 + 业务码**——这是本项目的显式约定（`BizException`、参数校验失败、上传超限、401 均如此），前端拦截器据此分支处理。

## 5. 全局异常处理 `common/GlobalExceptionHandler`

`@RestControllerAdvice`，统一把异常转成 `Result`：

| 异常 | 处理结果 |
|------|------|
| `BizException` | HTTP 200 + `Result.error(e.getCode(), e.getMessage())` —— 业务规则校验的标准载体 |
| `MethodArgumentNotValidException` | HTTP 200 + code=400，message = 「参数错误：」+ 第一条字段错误 |
| `MaxUploadSizeExceededException` | HTTP 200 + code=400，「上传文件过大」 |
| `Exception`（兜底） | **HTTP 500** + `Result.error(500, "系统异常，请稍后重试")`，并 `log.error` 记录堆栈 |

## 6. 并发控制与幂等

| 机制 | 实现 | 场景 |
|------|------|------|
| 乐观锁 | 前端回传 `updatedAt`（毫秒时间戳）与服务端 `updated_at` 比对，不一致抛 `BizException(409, "数据已被他人修改，请刷新后重试")`；写入成功后显式推进 `updated_at`（`nextVersionTime`：截断到秒且严格大于旧值，避免同秒编辑版本不前进） | 需求编辑（`RequirementServiceImpl.updateRequirement`）、排期行保存（`ScheduleServiceImpl.saveRow`） |
| 条件更新 | `UPDATE ... WHERE status = 旧状态`（`LambdaUpdateWrapper.eq(status, expect)`），影响行数为 0 视为并发被覆盖 | 需求状态推进（PENDING→READY、TEST 等），防止与取消竞态 |
| SETNX 防竞态 | Redis key 缺失时 `setIfAbsent` 回填 DB 最大流水，再 INCR 取号 | 需求编号生成 |
| 幂等设计 | 唯一键兜底（成员工种、月表年月、格子 row+day）；跨月顺延行插入前检查下月是否已有同需求+工种+负责人行 | 成员添加、月表创建、排期保存 |

## 7. 密码安全

- BCrypt 哈希存储（`AuthServiceImpl`/`UserServiceImpl` 直接实例化 `BCryptPasswordEncoder`）。
- 默认/初始密码 `Pm@123456`；新建与重置密码后 `force_change_pwd=1`。
- 登录失败对「用户不存在」与「密码错误」返回统一提示「用户名或密码错误」，防账号探测；停用账号单独提示。

**首次登录强制改密闭环（已实现）**：

```
登录成功（LoginVO.forceChangePwd=1）
   → Login.vue 写入 userStore（state + localStorage 持久化）并跳 /change-password
   → 路由全局守卫：forceChangePwd=true 时任何页面都重定向到 /change-password（登出前无法访问其他页面）
   → ChangePassword.vue：原密码 + 新密码（≥6 位）+ 确认；调 POST /api/auth/change-password
       → AuthServiceImpl：校验原密码 → BCrypt 新密码落库 → force_change_pwd=0
   → 成功后 markPwdChanged() 清标记 → 进入 /dashboard
```

## 8. 安全配置边界（部署检查清单）

1. 生产环境**必须更换** `application.yml` 中 `jwt.secret`（仓库内为示例值）与数据库/Redis 密码。
2. `/upload/**` 静态资源无鉴权，若附件敏感需增加鉴权或改为受控下载接口。
3. CORS 当前为 `allowedOriginPatterns("*")` + `allowCredentials(true)`（开发友好；生产建议收敛来源）。
4. token 有效期 5 天，无刷新/注销黑名单机制（无状态 JWT 的固有权衡）。
