# 05 · 前端架构与页面模块

前端为 Vue 3 + TypeScript 单页应用（Vite 构建，严格模式 TS）。入口 `src/main.ts` 装配 Pinia、Router、Element Plus（全量注册 + 中文语言包 + 图标全局注册）后挂载；根组件 `App.vue` 仅承载 `<router-view />`。

## 1. 工程结构

```
frontend/src/
├── main.ts              # 入口：图标全局注册 → Pinia → Router → ElementPlus(zhCn)
├── App.vue              # 根组件（router-view）
├── vite-env.d.ts        # vite/client 引用 + wangEditor-for-vue 组件类型声明（TS7016 兜底）
├── router/index.ts      # 路由表（14 条）+ 全局前置守卫（登录/回填/管理员/强制改密）
├── stores/user.ts       # Pinia 会话（token/userInfo/forceChangePwd，localStorage 持久化）
├── api/                 # request.ts 统一实例 + 12 个领域 API 模块
├── layout/MainLayout.vue# 主布局（侧边栏菜单 + 顶栏：全局搜索/通知铃铛/用户下拉）
├── components/          # 4 个公共组件
├── constants/           # craft.ts 工种、requirement.ts 需求枚举、bug.ts 处理/验收进度
└── views/
    ├── Login.vue
    ├── ChangePassword.vue   # 首次登录/重置密码后的强制改密页（独立于主布局）
    └── module/          # 11 个业务页
```

## 2. 路由与权限守卫 `router/index.ts`

路由模式 `createWebHistory`，业务页均懒加载；`declare module 'vue-router'` 中**只扩展了 `RouteMeta.admin?: boolean`**（`pmOrAdmin` 未声明类型、也未在守卫中消费，见下）。

| 路径 | name | 组件 | meta |
|------|------|------|------|
| /login | Login | `views/Login.vue` | `{title:'登录'}` |
| /change-password | ChangePassword | `views/ChangePassword.vue` | `{title:'修改密码'}`（强制改密页，独立于主布局） |
| / | — | `layout/MainLayout.vue` | redirect → `/dashboard` |
| /dashboard | Dashboard | `views/module/Dashboard.vue` | 工作台 |
| /projects | Projects | `views/module/Projects.vue` | 项目管理 |
| /projects/:id | ProjectDetail | `views/module/ProjectDetail.vue` | 项目详情 |
| /requirements | Requirements | `views/module/Requirements.vue` | 需求管理 |
| /requirements/:id | RequirementDetail | `views/module/RequirementDetail.vue` | 需求详情 |
| /schedule | Schedule | `views/module/Schedule.vue` | 排期管理 |
| /bugs | Bugs | `views/module/Bugs.vue` | Bug 管理 |
| /reports | Reports | `views/module/Reports.vue` | 统计报表 |
| /operation-logs | OperationLogs | `views/module/OperationLogs.vue` | `{pmOrAdmin: true}`（操作日志） |
| /admin/users | AdminUsers | `views/module/AdminUsers.vue` | `{admin: true}` |
| /admin/calendar | AdminCalendar | `views/module/AdminCalendar.vue` | `{admin: true}` |

**全局前置守卫 `beforeEach`（五段）**：
1. **自愈漂移**：pinia 有 token 但 localStorage 已被清（401/手动清除）→ 同步登出状态；
2. 无 token 且目标非 `/login` → 重定向 `/login`；
3. 有 token 但 `userInfo` 缺失（刷新/旧会话）→ `await userStore.fetchUserInfo()` 调 `/auth/me` 回填（失败由响应拦截器统一处理）；
4. `to.meta.admin === true` 且 `isAdmin !== 1` → 重定向 `/dashboard`；
5. **强制改密拦截**：`forceChangePwd` 为 true 时，除 `/change-password` 外任何路径都重定向 `/change-password`；反之已无改密需求的用户访问 `/change-password` 回 `/dashboard`。

> ⚠️ 已知缺口：`meta.pmOrAdmin`（操作日志页）**并未在守卫中拦截**——该页入口仅靠侧边栏菜单按 `isAdmin || isPm` 显隐控制，直接输入 URL 可进入（后端接口仍会按可见范围过滤数据，不会越权取数）。
> ⚠️ 未定义 catch-all 404 路由：未匹配路径不重定向，页面渲染空白。

## 3. 状态管理 `stores/user.ts`

Pinia Options 风格 `defineStore('user', {...})`：

| 成员 | 说明 |
|------|------|
| state.token | 初始从 `localStorage['token']` 恢复 |
| state.userInfo | 从 `localStorage['userInfo']` JSON 解析恢复（失败为 null）。含 `isAdmin`、**`isPm`**（是否任一项目创建者）、`forceChangePwd` |
| state.forceChangePwd | 首次登录/重置密码后的强制改密标记（登录/回填时随 userInfo 同步，`markPwdChanged()` 改密成功后清除并持久化） |
| getter.isLoggedIn | `!!token` |
| action.login(params) | 调 `loginApi`，写入 state 并持久化 localStorage |
| action.fetchUserInfo() | 调 `/auth/me` 刷新 userInfo 并同步 localStorage |
| action.markPwdChanged() | 改密成功后置 `forceChangePwd=false` 并同步 userInfo/localStorage |
| action.logout() | 清空 state + 移除 localStorage 两键（跳转由调用方负责） |

持久化为**手动 localStorage**（键 `token`、`userInfo`），未用持久化插件；`request.ts` 请求拦截器也直接读 localStorage，与 store 双轨一致。

## 4. HTTP 封装 `api/request.ts`

- `axios.create({ baseURL: '/api', timeout: 15000 })`；导出类型 `ApiResponse<T> = {code, message, data}`。
- **请求拦截器**：从 localStorage 取 token → `Authorization: Bearer <token>`。
- **响应拦截器（成功分支）**：
  - `code === 401`（HTTP 200 业务 401）：清 token → `ElMessage.warning('登录已过期，请重新登录')` → 跳 `/login` → reject；
  - `code !== 200`：`ElMessage.error(message || '操作失败')` 并 reject（**页面无需重复弹错**）。
- **错误分支**：HTTP 401 同样清 token 跳登录；其余原样 reject（如登录页要读 `error.response.data.message` 展示具体原因）。

## 5. 领域 API 模块（`src/api/`，12 个）

| 模块 | 导出函数（对应后端接口） |
|------|------|
| `auth.ts` | `login`（POST /auth/login）、`changePassword`（POST /auth/change-password）、`me`（GET /auth/me）；类型含 `isPm` |
| `user.ts` | `listUsers`、`createUser`、`updateUser`、`resetPassword`、`updateUserStatus`、`userOptions`；并定义通用分页类型 `PageResult<T>{records,total,current,size}` |
| `project.ts` | `listProjects`、`createProject`、`getProject`、`updateProject`、`addProjectMember`、`removeProjectMemberCraft`（DELETE 带 `?craft=`）、`removeProjectMember`（DELETE 不带 craft） |
| `requirement.ts` | `listRequirements`（含 `excludeTerminal`）、`createRequirement`、`getRequirement`、`getRequirementLogs`、`updateRequirement`（`updatedAt` 乐观锁必传）、`cancelRequirement`、`archiveRequirement`；类型含 `craftSchedules: CraftSchedule[]`（各工种排期日期区间，进度缩略图用） |
| `schedule.ts` | `listScheduleMonths`、`createScheduleMonth`、`getScheduleMonthView`、`addRequirementToMonth`、`removeRequirementFromMonth`、`saveScheduleRow`（PUT /schedule/rows/{rowId}）、`copyRowToNextMonth`、`listIncompleteRequirements`（退出拦截） |
| `calendar.ts` | `getCalendar(year, month)`、`updateCalendarDay(day, {type, name?})` |
| `file.ts` | `uploadFile(file)`（POST /files/upload，FormData 字段 `file`） |
| `notification.ts` | `listNotifications`、`getUnreadCount`、`readAllNotifications` |
| `bug.ts` | `listBugs`（archived 区分总表/归档表）、`createBug`、`updateBug`（清空哨兵：''=清空文本、0=清空数值/ID）、`deleteBug`、`archiveBug`、`reopenBug`、`addBugImages`、`deleteBugImage` |
| `dashboard.ts` | `getDashboardSummary`（GET /dashboard/summary）、`listMyRequirements`（GET /dashboard/my-requirements） |
| `reports.ts` | `getRequirementStatus`、`getWorkload(months)`、`getProjectOverview`（GET /reports/*） |
| `search.ts` | `globalSearch(keyword)`（GET /search） |
| `operationLogs.ts` | `pageOperationLogs(params)`（GET /operation-logs）、`listLogOperators`（GET /operation-logs/operators） |

**关键类型约定**：`myRole: string[]`（PM 或工种码，空数组=只读）；`updatedAt` 毫秒时间戳（乐观锁）；`ReqAttachment.fileType: 'IMAGE'|'DOC'`；`ScheduleRowData.cells: Record<yyyy-MM-dd, number>`；`CalendarDayType: 'WORKDAY'|'WEEKEND'|'HOLIDAY'|'ADJUSTED'`。

## 6. 常量 `src/constants/`

- `craft.ts`：`CRAFT_OPTIONS`（PLANNER 策划 / FE 前端开发 / BE 后端开发 / OPS 运维 / QA 测试 / UI）与 `craftLabel(v)`；注释说明 PM 为固定角色不在工种下拉中（与后端 `CraftConstants` 手工对齐）。
- `requirement.ts`：`STATUS_OPTIONS`（**6 项**：PENDING/DEV/TEST/ARCHIVING/ARCHIVED/CANCELLED，`terminal` 标记终态 ARCHIVED/CANCELLED）、`PRIORITY_OPTIONS`（1 高/2 中/3 低）、`statusLabel/priorityLabel`、el-tag 颜色映射 `statusTagType`（PENDING→info、TEST→primary、DEV/ARCHIVING→warning、ARCHIVED→success、CANCELLED→danger；`STATUS_TAG` 中另留 **`READY→primary` 仅作历史数据兜底**，不在 `STATUS_OPTIONS` 中、不可被选）与 `priorityTagType`（高→danger、中→warning、低→info）。
- `bug.ts`：`HANDLE_STATUS_OPTIONS`（DONE 已处理/HOLD 暂不处理/SKIP 不处理）、`ACCEPT_STATUS_OPTIONS`（PASSED/REJECTED）、`handleStatusLabel/acceptStatusLabel`（空值归「未处理/未验收」）与对应 el-tag 颜色映射（下拉 clearable，不设空选项）。

## 7. 主布局 `layout/MainLayout.vue`

- `el-container`：左侧 `el-aside`（220px 深色菜单，`router` 模式跟随当前路径）+ 右侧（`el-header` + `el-main`）。
- **菜单**（按身份动态拼装）：工作台 / 项目管理 / 需求管理 / 排期管理 / Bug 管理 / 统计报表 / 人员管理（所有人可见，页面由守卫拦截）；**`isAdmin || isPm` 时追加「操作日志」**；**仅 `isAdmin===1` 时追加「日历管理」**。
- **顶栏**：
  - **全局搜索**（已接接口）：`el-input` 300ms 防抖 + 竞态序号（仅采纳最后一次响应）；弹层分组展示 需求/Bug/项目/人员 四类（各前 5 条），点击跳转对应页面（Bug 跳 `/bugs?projectId=`、项目跳详情、需求跳详情、人员仅展示）；点击面板外收起。
  - **通知铃铛**（`el-badge` max 99）+ `el-popover`：挂载时取未读数并**每 60 秒轮询**，点开拉最近 10 条，未读红点+标题加粗，底部「全部已读」，点击 `bizType==='REQUIREMENT'` 的通知跳 `/requirements/{bizId}`。
  - **用户下拉**：显示姓名，仅 `logout` 一项 → `userStore.logout()` → 跳 `/login`。

## 8. 页面模块职责（13 个：2 个独立页 + 11 个 module/ 业务页）

| 页面 | 状态 | 职责与功能点 |
|------|------|------|
| `Login.vue` | ✅ | 用户名/密码表单（回车提交、必填校验）；成功 `userStore.login` → **`forceChangePwd` 为 true 跳 `/change-password`**，否则跳 `/dashboard`；失败读 `error.response.data.message` 展示具体原因。无记住密码 |
| `ChangePassword.vue` | ✅ | 强制改密页（独立于主布局，与登录页同风格渐变背景居中卡片）。原密码 + 新密码（≥6 位）+ 确认（两次一致校验）；成功 `markPwdChanged()` → 跳 `/dashboard`；「暂不修改，退出登录」直接登出；后端错误（如原密码错误）优先展示 message |
| `module/Dashboard.vue` | ✅ | **工作台**：4 张统计卡片（未读通知 / 参与需求 / 本月排期工时 / 待处理 Bug，可点击跳对应页）+ 「我参与的需求」表格（点行进详情）+ 「最近通知」列表（点击按 bizType 跳需求详情或 Bug 页）+ 快捷入口按钮组 |
| `module/Projects.vue` | ✅ | 自适应**卡片网格**（每页 12/24/48）；关键词搜索；卡片含缩写 tag、描述、创建人、成员数、「我的角色」标签；仅管理员见「创建项目」按钮（弹窗：名称 ≤50 字、缩写正则 `/^[A-Z]{2,6}$/`、描述）；点卡片进详情 |
| `module/ProjectDetail.vue` | ✅ | 纵向卡片：基本信息 `el-descriptions`；成员管理 `el-table`（工种标签列）。PM 可编辑项目（缩写不可改）、添加成员（人员下拉 + 工种多选 checkbox）、点工种标签 × 删单工种、移除整成员（均二次确认） |
| `module/Requirements.vue` | ✅ | 筛选（项目下拉一次拉 100 条 / 状态 / 优先级 / 关键词）；表格列含 `ProgressSteps mini` 迷你进度、等宽编号、优先级 tag、参与人数；分页 10/20/50；新增弹窗复用 `RequirementFormDialog`（`canCreate` = 管理员或任一项目 myRole 含 PM/PLANNER） |
| `module/RequirementDetail.vue` | ✅ | 区块：工具栏（操作按钮区）→ `ProgressSteps full` 进度流程图 → 基本信息 → 富文本描述（v-html）→ 附件列表（`window.open` 打开）→ 参与人（按工种分组）→ 操作日志 `el-timeline`。按钮显隐：编辑 = 非终态且（PM 或创建人）；取消 = 非终态且 PM（prompt 原因必填）；归档 = ARCHIVING 且 PM。编辑弹窗 409 冲突时 emit `conflict` 刷新详情 |
| `module/Schedule.vue` | ✅ 最复杂 | 月视图排期矩阵（vxe-table 局部引入）。顶部月份 Tab（默认当月/最新）；需求级列纵向合并单元格（编号/名称/项目/策划/优先级，`GROUP_COL_INDEXES` + `setMergeCells`）；行级列：负责人（顺延行橙色「延」徽标）、工种、工期（el-input-number 0.1 步进，跨月共用）、已排（**跨月合计**：后端 `scheduledSum` + 本地编辑差量实时计算）；行状态三态派生：未设工期→待排期（灰）/ 跨月已排<工期→排期中（橙）/ ≥工期→已排期（绿）；**日期列 = 当月每天**（非可排班日灰显斜纹+禁止光标、调休绿色）；点格子编辑（0~1、0.1 步进，仅 PM、仅可排班日，单格编辑，回车/失焦提交、Esc 取消）；**自动保存**（`savingRows/dirtyRows` Set 在途合并，409 整表刷新，返回 overflow 提示顺延并刷新月份 Tab）；「添加到下月」按钮（PM）= **整个需求**逐组插入下月新行（格子空白、工期共用）后跳转下月；**移除需求**（PM，删当月行+格子）；**退出拦截**（`onBeforeRouteLeave` + `beforeunload`：存在自己可修复的未完成排期时强制弹窗，弹窗内可点击跳需求详情取消需求逃生）；表头列筛选（`ScheduleFilterHeader`）；工具栏：生成下月排期表 / 从需求池添加（Picker）/ 管理员日历入口 |
| `module/Bugs.vue` | ✅ 最复杂之一（约 1100 行） | **Excel 化 Bug 管理**（vxe-table 局部引入）。顶部项目下拉（**必选**，未选显示引导页）；「Bug 列表 / 归档表」双 Tab（归档表懒加载、每次切入刷新）；一页全量 size=1000；**单元格逐格编辑**（仅未归档行）：文本格无边框输入框（点击进入编辑、Enter/失焦提交、Esc 取消，同一时刻仅一格）、负责人/测试人/优先级/处理进度/验收进度下拉格（清空传哨兵 ''/0）；**新增行置顶**（编号自动生成、测试人=当前用户，其余留空待编辑）；截图列默认显示第一张缩略图、点击打开管理弹窗（预览走马灯 / 上传 / **窗口粘贴上传** / 删除，归档行只可预览）；视频列显示视频名 + 播放按钮弹窗播放（支持上传 mp4/webm/mov ≤50MB 或粘贴外链、清除双清 URL 与名称）；归档/重新打开限该项目 PM 或全局管理员（行在两表间移动后两表都刷新），删除所有人可用（二次确认，归档行也可删） |
| `module/Reports.vue` | ✅ | **统计报表**（ECharts 6 按需引入 core + Pie/Bar/Heatmap + 组件）：KPI 卡片（需求总数 / 进行中 / 项目数 / 未归档 Bug）+ 需求状态分布环形图（自定义 HTML 图例，含数量与占比）+ 项目×状态纵向堆叠柱（项目 >6 时启用 dataZoom）+ 人员负载热力图（人员×月份，可切 3/6/12 个月，visualMap 映射工时深浅）+ 项目概览数据条表格（成员数 / 进行中需求 / 未归档 Bug 各指标独立比例条）。卸载 dispose、窗口 resize 自适应 |
| `module/OperationLogs.vue` | ✅ | **操作日志**：筛选（业务类型 / 动作 / 操作人 / 项目 / 关键词 / 日期区间）+ 分页表格（每页 20）；动作码映射中文与标签色；项目下拉按可见范围（管理员 = 全部，PM = 自己创建的 + myRole 含 PM 的）；操作人下拉由后端按可见范围去重返回 |
| `module/AdminUsers.vue` | ✅ | el-table 列表（关键词 + 状态筛选，停用行淡灰）；新增/编辑同弹窗（用户名 3-30 位字母数字，编辑仅可改姓名）；重置密码 prompt（空则默认 `Pm@123456`，提示下次登录强制改密）；启用/停用确认切换 |
| `module/AdminCalendar.vue` | ✅ | 自绘 7 列 CSS Grid 月历（周一起始，`el-date-picker type=month` 切换）；四类日视觉（工作日白 / 周末灰斜纹「休」/ 节假日红字带名称 / 调休绿框「调休」）+ 图例；点日期弹窗单选类型（节假日可输名称 ≤20 字）→ `updateCalendarDay` |

## 9. 公共组件 `src/components/`

| 组件 | props / emits | 说明 |
|------|------|------|
| `ProgressSteps.vue` | `status: string`、`participants?: ReqParticipant[]`、`size?: 'mini'\|'full'`（纯展示，无 emits）。列表页另传各工种排期区间 `craftSchedules` | 需求进度可视化。`mini`：小圆点+连线迷你进度条（需求列表用，已取消尾部红字）；`full`：横向节点流程图（需求详情用）——主干节点 PENDING→…→ARCHIVED（READY 节点语义保留为"排期"），**DEV 阶段按参与人工种（FE/BE/OPS/UI）扇出分支卡**，TEST 为 QA 分支卡；**开发/测试节点的点亮颜色由排期日期实时推导**（与后端派生口径一致，不读 status）；节点内渲染文字头像（userId 对 6 色板取模稳定取色，完成态带对勾）；SVG Bézier 曲线连接线按状态点亮；挂载后 `scrollIntoView` 定位当前节点 |
| `RequirementFormDialog.vue` | `modelValue`（v-model）、`projects`（新增模式数据源）、`editData?`（传入即编辑）；emits：`success`、`conflict` | 新建/编辑需求两用弹窗。字段：项目（**可选项过滤 myRole 含 PM/PLANNER**；编辑模式禁用）、名称 ≤100 字、优先级 radio、策划（候选 = 原策划 + 项目创建人 + PLANNER 成员去重，新增默认当前用户）、wangEditor 富文本（内嵌图片走 `customUpload → uploadFile`）、附件 el-upload（白名单 pdf/doc/docx/xlsx/zip ≤50MB，`http-request` 自定义上传）、参与人动态行（工种下拉 + 该工种成员多选，提交按 craft-userId 去重）。编辑提交携带 `editData.updatedAt`，错误含「已被他人修改」时关闭并 emit `conflict`；`destroy-on-close` 防编辑器多实例 |
| `ScheduleRequirementPicker.vue` | `modelValue`、`monthId`；emits：`success` | 从需求池挑选需求加入月表。打开时拉 `listRequirements({excludeTerminal:true, size:100})`；表格单选（点行切换选中）；提交 `addRequirementToMonth` 成功提示「已添加，生成 N 行排期」并 emit `success` |
| `ScheduleFilterHeader.vue` | `title: string`、`active: boolean`；emits：`toggle(ev: MouseEvent)` | 排期页表头列筛选触发器（标题 + 右下角绿色三角按钮）。携带原生事件供父组件用按钮位置定位单例筛选面板；`active` 高亮表示该列已启用筛选 |

## 10. 前端已知缺口（截至当前代码）

1. **`meta.pmOrAdmin` 未在守卫中拦截**：操作日志页仅靠菜单显隐控制，直接输入 URL 可进入（后端接口仍按可见范围过滤数据）。
2. 无 404 catch-all 路由。
3. Bug 页与排期页的项目下拉一次最多拉 100 条（源码注释：超出部分一期不考虑）。
4. 通知点击跳转仅处理 `bizType==='REQUIREMENT'`（`MainLayout.vue` 中；BUG 类通知当前无产生方，跳转分支未接入）——**注意 Dashboard.vue 已额外处理 `bizType==='BUG'` 跳 `/bugs`，两处口径不完全一致**。
5. 全局搜索输入无历史记录/快捷键唤起。
