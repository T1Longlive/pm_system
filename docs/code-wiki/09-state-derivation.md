# 09 · 需求状态派生化（核心重构）

> 本文档记录一次影响面最大的架构重构：需求状态从「事件驱动的显式状态机」改为「由排期数据 + 当前日期派生的纯函数投影」。
> 阅读代码前务必先读完本文，否则会按旧的 `READY → DEV → TEST → ARCHIVING` 爬坡模型理解，处处对不上。

## 1. 为什么改

旧模型（已废弃）用「事件推进」维护状态：

- 排期保存时若「该行/该需求全部排满」→ 把 `READY` 推进成 `DEV`；
- 每小时定时任务扫「READY 且有开发行日期 ≤ 今天」的需求 → 推进 `DEV`；
- 再扫 `DEV` 全部行已过期的 → `TEST` → `ARCHIVING`。

痛点：

| 问题 | 说明 |
|------|------|
| 状态会「漂移」 | 状态一旦推进就无法回退（或需专门写回退逻辑）；用户改掉排期后，状态与事实不符 |
| 定时任务滞后 | 「按日期到期」必须等下一次整点扫描，最长滞后 1 小时 |
| 口径分散 | 「排满」「完成」判定散落在 `saveRow`、`advanceBySchedule`、多个 SQL 里，容易不一致 |
| `READY` 语义模糊 | 它是「排满了但还没到开发日」的中间态，与 `PENDING` 边界靠事件时序区分，很脆 |

新模型：**状态不再「维护」，而是随时可重算**。任何时刻的状态 = `f(排期数据, 今天)`。

## 2. 核心函数 `deriveStage`

入口：`RequirementStageService.deriveStage(Requirement req, List<RequirementGroupAggVO> groups, LocalDate today)`

返回值为纯计算结果，**不写库**。判定顺序（短路，自上而下）：

```
1. status == CANCELLED  →  CANCELLED   （人工终态，永不改写）
2. status == ARCHIVED   →  ARCHIVED    （人工终态，永不改写）
3. groups 为空          →  PENDING     （无非 VOID 排期行）
4. 任一排期组「未排满」  →  PENDING
5. 存在非 QA 行 且 开发窗口未结束 → DEV
6. 全部行（含 QA）窗口已结束 → ARCHIVING
   否则 → TEST
```

### 2.1 第 4 步：什么叫「排期组未排满」

以 `(requirement_id, craft, user_id)` 为一个**排期组**（跨全部月份合并，见第 3 节）。未排满的三种情况任一成立：

```java
g.getPlannedEffort() == null                 // 组无计划工期
|| g.getPlannedEffort().signum() <= 0        // 工期 ≤ 0
|| g.getTotal().compareTo(g.getPlannedEffort()) < 0   // 跨月格子合计 < 工期
```

> 注意 `getTotal()` 是**跨全部月份**的已排合计（`schedule_cell.value` 之和），不是单月。

### 2.2 第 5 步：开发窗口（`allRowsPassed`）

```java
private boolean allRowsPassed(List<RequirementGroupAggVO> groups, LocalDate today) {
    return groups.stream().allMatch(g ->
            g.getRowsWithCells() >= g.getRowCount()      // 组内无空行（空行阻塞推进）
            && g.getMaxDay() != null
            && g.getMaxDay().isBefore(today));            // 组内最大格子日 早于 今天
}
```

- `devGroups` = 过滤掉 `craft != 'QA'` 的组。若**存在**非 QA 组且它们**未**全部 pass → `DEV`。
- **没有非 QA 行**（只有 QA）时跳过第 5 步，直接进第 6 步。

「开发窗口结束」= 每个非 QA 组都满足：**组内每行都有格子** 且 **最大格子日 < 今天**。

### 2.3 第 6 步：测试窗口

对**全部组（含 QA）**再跑一次 `allRowsPassed`：

- 全部 pass → `ARCHIVING`（所有排期都已成为过去）
- 否则 → `TEST`

## 3. 关键概念：排期组与聚合口径

状态派生的输入来自唯一查询 `ScheduleRowMapper.selectGroupAggBatch(reqIds)`：

```sql
SELECT r.requirement_id, r.craft, r.user_id,
       MAX(r.planned_effort) AS plannedEffort,        -- 组内工期（同组各月行同步，取 MAX 即可）
       COUNT(DISTINCT r.id)  AS rowCount,             -- 组内行数（含无格子的空行）
       COUNT(DISTINCT CASE WHEN c.id IS NOT NULL THEN r.id END) AS rowsWithCells,
       COALESCE(SUM(c.value), 0) AS total,            -- 跨全部月份已排合计
       MAX(c.day)            AS maxDay                 -- 组内最大格子日
FROM schedule_row r LEFT JOIN schedule_cell c ON c.row_id = r.id
WHERE r.status != 'VOID' AND r.requirement_id IN (...)
GROUP BY r.requirement_id, r.craft, r.user_id
```

要点：

- **分组键是「需求 × 工种 × 负责人」，且不含月份** → 同一组跨多月的行合并统计（主行 + 跨月顺延行共享工期与已排合计）。
- **`VOID` 行完全排除**（换人时旧行被置 VOID）。
- `LEFT JOIN schedule_cell`：无格子的行 `c.id` 为 null，故 `rowsWithCells < rowCount` 即「有空行」。
- 一次批量查询覆盖多个需求，避免 `syncAllStages` 的 N+1。

## 4. 唯一写入口 `syncStage` / `syncAllStages`

### 4.1 `syncStage(requirementId, operatorId)`

单需求实时对账：查需求 → 已是终态则直接返回 false → 算 `deriveStage` → `applyIfChanged`。

写路径（保存排期、增删需求、参与人变更）都调它做**实时对账**，结果立即落库。

### 4.2 `syncAllStages()`

全量兜底：查出**全部非终态**需求 → 一次 `loadGroups` 批量取聚合 → 逐个 `deriveStage` + `applyIfChanged(req, after, null)`。返回变更条数。

由 `RequirementStatusTask` 触发：

- `@Scheduled(cron = "0 0 * * * *")` 每小时整点；
- `ApplicationReadyEvent` 启动时执行一次（补齐停机期间跨过的日期）。

**它存在的唯一理由**：纯时间流逝。日期变旧让「窗口结束」，没有任何用户写操作发生 → 只能靠定时重算。

### 4.3 `applyIfChanged`（并发安全的关键）

```java
if (after.equals(req.getStatus())) return false;                      // 无变化，不写
int updated = requirementMapper.update(null, new LambdaUpdateWrapper<Requirement>()
        .eq(Requirement::getId,   req.getId())
        .eq(Requirement::getStatus, req.getStatus())                  // ← 条件更新，防并发覆盖
        .set(Requirement::getStatus, after));
if (updated <= 0) return false;                                       // 期间被他人改了，放弃本次
if (operatorId != null) writeLog(req, operatorId, req.getStatus(), after);  // 用户触发才写日志
notifyParticipants(req, after);                                       // 通知全部参与人（去重）
```

- **`eq(旧状态)` 保护不可删**：A 线程派生 `PENDING→DEV`，B 线程基于旧值可能写回 `PENDING`，条件更新保证只有一个赢。
- **文档日志的非对称**：`operatorId != null`（用户触发）才写 `STAGE_SYNC` 日志；定时对账传 `null`，**不写**——否则日志表被每小时全量刷爆。
- **通知是无条件的**（只要状态真的变了）：包括**回退**（如排期被改小 → `DEV` 退回 `PENDING`），文案按目标阶段区分，见 `notifyParticipants`。

## 5. 人工 vs 派生：谁说了算

| 状态 | 归属 | 能否被派生改写 |
|------|------|----------------|
| `CANCELLED` | 人工（cancel 接口） | ❌ 永不 |
| `ARCHIVED` | 人工（归档接口，PM/管理员） | ❌ 永不 |
| `PENDING` / `DEV` / `TEST` / `ARCHIVING` | **派生** | ✅ 随时重算 |

> **`READY` 已不存在于存储层**。旧代码/旧文档中的 `READY` 一律对应新的「排满但开发窗口未开始」——那是 `DEV` 与 `PENDING` 之间的瞬时判定，不再单独落库。

## 6. 调用点清单（谁触发对账）

| 调用方 | 时机 | operatorId |
|--------|------|-----------|
| `ScheduleServiceImpl.addRequirement` | 加需求生成行后 | 当前用户 |
| `ScheduleServiceImpl.removeRequirement` | 删该需求该月全部行后（可能回退 PENDING） | 当前用户 |
| `ScheduleServiceImpl.saveRow` | 保存格子/工期后（第 6 步） | 当前用户 |
| `ScheduleServiceImpl.copyRequirementToNextMonth` | 复制到下一月后 | 当前用户 |
| `RequirementServiceImpl`（参与人变更/编辑） | `applyParticipantDiff`、`syncParticipantScheduleRows` 后 | 当前用户 |
| `RequirementStatusTask.syncAllStages` | 每小时 + 启动 | `null`（不写日志） |

> **不在调用方之列**：`BugServiceImpl`（Bug 与需求排期无关，只写自己的 `biz_operation_log`）。

## 7. 影响面速查（改动时注意）

1. **`requirement.status` 不要手写**：所有非终态变更必须经 `syncStage`/`deriveStage`，否则下次对账会被覆盖回派生值。
2. **`schedule_row.done_at` / `done_by` 已废弃**：新模型里「单行完成」不再是独立事件，判定完全基于格子数据与日期。列保留仅为兼容旧数据。
3. **前端不再推导状态**：`constants/requirement.ts` 只做「派生值 → 文案/颜色」映射，不重算状态机。
4. **`selectReadyReqIdsWithDueDevCell` 已删除**：任何地方不要试图恢复它。
5. **通知包含回退**：如果看到用户收到「已回退为待排期」，这是正确行为，不是 bug。
6. 相关文档：[02-backend-modules.md](02-backend-modules.md) 后端模块、[03-database-design.md](03-database-design.md) 库表、[06-key-classes.md](06-key-classes.md) 类细节。
