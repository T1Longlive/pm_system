<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
// vxe-table 局部引入：仅本页使用，不做全局注册
import { VxeTable, VxeColumn } from 'vxe-table'
import type { VxeTablePropTypes, VxeTableInstance } from 'vxe-table'
import 'vxe-table/lib/style.css'
import { useUserStore } from '@/stores/user'
import { listProjects, type ProjectItem } from '@/api/project'
import {
  listScheduleMonths,
  createScheduleMonth,
  getScheduleMonthView,
  saveScheduleRow,
  copyScheduleRowToNextMonth,
  type ScheduleMonth,
  type ScheduleView,
  type ScheduleRowData
} from '@/api/schedule'
import type { CalendarDayData, CalendarDayType } from '@/api/calendar'
import { craftLabel } from '@/constants/craft'
import { priorityLabel, priorityTagType } from '@/constants/requirement'
import ScheduleRequirementPicker from '@/components/ScheduleRequirementPicker.vue'

const router = useRouter()
const userStore = useUserStore()

// ===== 页面状态 =====
// 月表列表（升序）
const months = ref<ScheduleMonth[]>([])
// 当前选中月表 id
const activeMonthId = ref<number | null>(null)
// 当前月表视图（日历 + 需求分组）
const view = ref<ScheduleView | null>(null)
// 视图加载中
const viewLoading = ref(false)
// 生成月表按钮 loading
const creating = ref(false)
// 项目列表（PM 权限判断用）
const projects = ref<ProjectItem[]>([])

// ===== 权限 =====
// 是否管理员
const isAdmin = computed(() => userStore.userInfo?.isAdmin === 1)
// 「生成下月排期表 / 从需求池添加」可见：管理员或任一项目 PM
const canManage = computed(
  () => isAdmin.value || projects.value.some((p) => p.myRole.includes('PM'))
)
// 我担任 PM 的项目 id 集合（排期格子可编辑、完成按钮可点）
const pmProjectIds = computed(
  () => new Set(projects.value.filter((p) => p.myRole.includes('PM')).map((p) => p.id))
)
// 我是否为该项目 PM
const isPmOf = (projectId: number) => pmProjectIds.value.has(projectId)

// ===== 表格数据拍平 =====
// 表格行：需求分组字段 + 排期行（需求级列通过 merge-cells 纵向合并）
interface FlatRow {
  _key: string
  seq: number
  requirementId: number
  reqNo: string
  title: string
  projectId: number
  projectName: string
  plannerName: string | null
  priority: 1 | 2 | 3
  row: ScheduleRowData
}

const flatRows = computed<FlatRow[]>(() => {
  if (!view.value) return []
  return view.value.groups.flatMap((g) =>
    g.rows.map((r) => ({
      _key: `${g.requirementId}-${r.rowId}`,
      seq: g.seq,
      requirementId: g.requirementId,
      reqNo: g.reqNo,
      title: g.title,
      projectId: g.projectId,
      projectName: g.projectName,
      plannerName: g.plannerName,
      priority: g.priority,
      row: r
    }))
  )
})

// 需求级合并列下标：编号(0) / 需求名称(1) / 项目(4) / 策划(5) / 优先级(6)——负责人(2)/工种(3)为行级列不合并
const GROUP_COL_INDEXES = [0, 1, 4, 5, 6]
// 合并单元格配置：同一需求连续行的需求级列纵向合并
const mergeCells = computed<VxeTablePropTypes.MergeCells>(() => {
  const result: VxeTablePropTypes.MergeCells = []
  let index = 0
  for (const g of view.value?.groups || []) {
    if (g.rows.length > 1) {
      for (const col of GROUP_COL_INDEXES) {
        result.push({ row: index, col, rowspan: g.rows.length, colspan: 1 })
      }
    }
    index += g.rows.length
  }
  return result
})

// ===== 日历辅助 =====
// 日历日索引：day → CalendarDayData
const dayMap = computed<Record<string, CalendarDayData>>(() => {
  const map: Record<string, CalendarDayData> = {}
  view.value?.days.forEach((d) => {
    map[d.day] = d
  })
  return map
})

// 是否可排班日（工作日 / 调休上班）
const isSchedulable = (d: CalendarDayData) => d.type === 'WORKDAY' || d.type === 'ADJUSTED'

// 星期中文（周日=0）
const WEEK_LABELS = ['日', '一', '二', '三', '四', '五', '六']
const weekdayLabel = (day: string) => {
  const [y, m, d] = day.split('-').map(Number)
  return WEEK_LABELS[new Date(y, m - 1, d).getDay()]
}
// 日号（1~31）
const dayNumber = (day: string) => Number(day.slice(8, 10))

// 日类型中文名（表头悬停提示用）
const DAY_TYPE_LABELS: Record<CalendarDayType, string> = {
  WORKDAY: '工作日',
  WEEKEND: '周末',
  HOLIDAY: '节假日',
  ADJUSTED: '调休上班'
}
const headerTitle = (d: CalendarDayData) =>
  `${d.day} 星期${weekdayLabel(d.day)} · ${DAY_TYPE_LABELS[d.type]}${d.name ? `（${d.name}）` : ''}`

// ===== 行 / 单元格样式 =====
// 行样式：作废行删除线灰显
const rowClassName: VxeTablePropTypes.RowClassName<FlatRow> = ({ row }) => {
  if (row.row.status === 'VOID') return 'row-void'
  return ''
}

// 单元格样式：日期列有值浅蓝底、非可排班日斜纹灰
const cellClassName: VxeTablePropTypes.CellClassName<FlatRow> = ({ row, column }) => {
  const day = column?.field || ''
  const dayInfo = dayMap.value[day]
  if (!dayInfo) return ''
  if (!isSchedulable(dayInfo)) return 'cell-day cell-locked'
  const v = row.row.cells[day]
  return v != null && v > 0 ? 'cell-day cell-filled' : 'cell-day'
}

// 表头单元格样式：非可排班日列头灰显
const headerCellClassName: VxeTablePropTypes.HeaderCellClassName = ({ column }) => {
  const dayInfo = dayMap.value[column?.field || '']
  return dayInfo && !isSchedulable(dayInfo) ? 'head-day head-locked' : 'head-day'
}

// ===== 单元格编辑 =====
// 当前行格子是否可编辑：非作废行随时可编辑 + 本人是该项目 PM
const cellEditable = (row: FlatRow) => row.row.status !== 'VOID' && isPmOf(row.projectId)

// 编辑态：同一时刻仅一个格子（key = rowId|day）
const editing = reactive<{ key: string; value: string }>({ key: '', value: '' })
const cellKey = (rowId: number, day: string) => `${rowId}|${day}`
const isEditing = (row: FlatRow, day: string) => editing.key === cellKey(row.row.rowId, day)

// 本地自定义指令：编辑 input 挂载后自动聚焦
const vCellFocus = { mounted: (el: HTMLInputElement) => el.focus() }

// 点击格子进入编辑（仅可编辑行 + 可排班日）
const startEdit = (row: FlatRow, day: CalendarDayData) => {
  if (!cellEditable(row) || !isSchedulable(day)) return
  editing.key = cellKey(row.row.rowId, day.day)
  const v = row.row.cells[day.day]
  editing.value = v != null && v > 0 ? String(v) : ''
}

// 提交编辑：校验 → 落本地 cells → 触发行保存（blur / 回车都会调，重复调用安全）
const commitCell = () => {
  if (!editing.key) return
  const [rowIdStr, day] = editing.key.split('|')
  editing.key = ''
  const row = findRow(Number(rowIdStr))
  if (!row) return
  const raw = editing.value.trim()
  // 空值 = 清空该格
  if (!raw) {
    if (row.cells[day] != null) {
      delete row.cells[day]
      requestSave(row.rowId)
    }
    return
  }
  const v = Number(raw)
  if (!Number.isFinite(v) || v <= 0 || v > 1) {
    ElMessage.warning('格子值需为 0~1 之间的小数（步进 0.1）')
    return
  }
  const rounded = Math.round(v * 10) / 10
  // 超额守卫：填入后已排合计不得超过计划工期（提示先修改工期）
  if (row.plannedEffort && row.plannedEffort > 0) {
    const others = scheduledSum(row) - (row.cells[day] || 0)
    if (Math.round((others + rounded) * 10) / 10 > row.plannedEffort) {
      ElMessage.warning(`已排合计将超过计划工期 ${row.plannedEffort}，请先修改计划工期`)
      return
    }
  }
  if (row.cells[day] !== rounded) {
    row.cells[day] = rounded
    requestSave(row.rowId)
  }
}

// 取消编辑（Esc）
const cancelCell = () => {
  editing.key = ''
}

// 格子显示文本
const cellText = (row: FlatRow, d: CalendarDayData) => {
  const v = row.row.cells[d.day]
  return v != null && v > 0 ? String(v) : ''
}

// ===== 行保存（自动保存 + 同行串行） =====
// 在途 / 脏标记：同一行保存期间的新提交合并为最新一次
const savingRows = new Set<number>()
const dirtyRows = new Set<number>()

// 按行查找当前视图中的排期行（视图刷新后对象会被替换，发送前实时查找）
const findRow = (rowId: number): ScheduleRowData | null => {
  for (const g of view.value?.groups || []) {
    const row = g.rows.find((r) => r.rowId === rowId)
    if (row) return row
  }
  return null
}

// 组装整行 cells（仅保留 >0 的日期，PUT 整行替换）
const buildCells = (row: ScheduleRowData): Record<string, number> => {
  const cells: Record<string, number> = {}
  for (const d of view.value?.days || []) {
    const v = row.cells[d.day]
    if (v != null && v > 0) cells[d.day] = v
  }
  return cells
}

// 是否乐观锁冲突（HTTP 409 / body code=409 / 拦截器转译的冲突文案）
const isConflict = (error: unknown): boolean => {
  if (typeof error !== 'object' || error === null) return false
  const e = error as { response?: { status?: number; data?: { code?: number } }; message?: string }
  if (e.response?.status === 409 || e.response?.data?.code === 409) return true
  return !!e.message && e.message.includes('已被')
}

// 请求保存该行：在途时仅标脏，由在途流程结束后补存
const requestSave = (rowId: number) => {
  if (savingRows.has(rowId)) {
    dirtyRows.add(rowId)
    return
  }
  void flushRow(rowId)
}

// 逐行保存：成功更新本地 updatedAt；顺延时提示并刷新月份 Tab；409 冲突时整表刷新
const flushRow = async (rowId: number) => {
  savingRows.add(rowId)
  try {
    while (true) {
      dirtyRows.delete(rowId)
      const row = findRow(rowId)
      if (!row) break
      try {
        const res = await saveScheduleRow(rowId, {
          plannedEffort: row.plannedEffort,
          cells: buildCells(row),
          updatedAt: row.updatedAt
        })
        row.updatedAt = res.data.data.updatedAt
        const overflow = res.data.data.overflow
        if (overflow) {
          ElMessage.success(
            `剩余 ${overflow.carriedEffort} 工期已顺延至 ${overflow.year}年${overflow.month}月排期表`
          )
          // 顺延会自动创建下月表，静默刷新月份 Tab（保持当前选中）
          void fetchMonths()
        }
      } catch (error) {
        if (isConflict(error)) {
          // 乐观锁冲突：后端 message 已由拦截器弹出，整表刷新恢复最新数据
          dirtyRows.delete(rowId)
          void fetchView()
        }
        break
      }
      if (!dirtyRows.has(rowId)) break
    }
  } finally {
    savingRows.delete(rowId)
  }
}

// 工期变更：v-model 已落本地，低于已排合计则拒绝并回退；否则触发行保存
const handleEffortChange = (row: FlatRow) => {
  const effort = row.row.plannedEffort
  if (effort != null && effort > 0 && scheduledSum(row.row) > effort) {
    ElMessage.warning(`已排合计 ${scheduledSum(row.row)} 超过计划工期 ${effort}，请先减少排期或调高工期`)
    // 回退为库中旧值（重新拉取当前视图，丢弃本地未保存修改）
    fetchView()
    return
  }
  requestSave(row.row.rowId)
}

// 已排合计（本地实时求和）
const scheduledSum = (row: ScheduleRowData): number => {
  let sum = 0
  for (const v of Object.values(row.cells)) sum += v || 0
  return Math.round(sum * 10) / 10
}

// ===== 行状态（三态派生）与复制到下月 =====
// 行状态展示：VOID 已作废；未设工期/无已排内容 → 未排期（灰）；
// 已排合计 < 工期 → 排期中（橙）；已排合计 ≥ 工期 → 已排期（蓝）
const rowStatusInfo = (
  row: ScheduleRowData
): { label: string; type: 'primary' | 'success' | 'info' | 'warning' } => {
  if (row.status === 'VOID') return { label: '已作废', type: 'info' }
  const sum = scheduledSum(row)
  if (!row.plannedEffort || row.plannedEffort <= 0) return { label: '未排期', type: 'info' }
  if (sum >= row.plannedEffort) return { label: '已排期', type: 'primary' }
  return { label: '排期中', type: 'warning' }
}

// 复制到下月可见：非作废行 + 本人是该项目 PM
const canCopy = (row: FlatRow) => row.row.status !== 'VOID' && isPmOf(row.projectId)

// 点击复制：确认后调接口（下月表不存在自动创建、带原工期），完成后跳转下月视图
const handleCopy = async (row: FlatRow) => {
  try {
    await ElMessageBox.confirm(
      `确认将「${row.row.realName} · ${craftLabel(row.row.craft)}」添加到下月排期表？`,
      '添加到下月',
      { type: 'warning', confirmButtonText: '添加', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await copyScheduleRowToNextMonth(row.row.rowId)
    if (res.data.data.duplicated) {
      ElMessage.success('已添加到下月排期表')
    } else {
      ElMessage.warning('该行已在下月排期中')
    }
    await fetchMonths()
    activeMonthId.value = res.data.data.monthId
    await fetchView()
  } catch {
    // 失败已由拦截器统一提示
  }
}

// ===== 数据加载 =====
// 拉取项目列表（PM 权限判断；一次拉取一百条，超出部分一期不考虑）
const fetchProjects = async () => {
  try {
    const res = await listProjects({ page: 1, size: 100 })
    projects.value = res.data.data.records
  } catch {
    // 失败已由拦截器统一提示，权限降级为只读
  }
}

// 拉取月表列表：保持当前选中；未选中或已不存在时默认当前月，否则最新一期
const fetchMonths = async () => {
  const res = await listScheduleMonths()
  months.value = res.data.data
  if (!months.value.some((m) => m.id === activeMonthId.value)) {
    const now = new Date()
    const current = months.value.find(
      (m) => m.year === now.getFullYear() && m.month === now.getMonth() + 1
    )
    activeMonthId.value = current
      ? current.id
      : months.value.length
        ? months.value[months.value.length - 1].id
        : null
  }
}

// 拉取当前月表视图
const fetchView = async () => {
  if (!activeMonthId.value) {
    view.value = null
    return
  }
  viewLoading.value = true
  try {
    const res = await getScheduleMonthView(activeMonthId.value)
    view.value = res.data.data
    editing.key = ''
  } catch {
    view.value = null
  } finally {
    viewLoading.value = false
    // vxe-table 列重建后 merge-cells 会被重置：先刷新列模型，稳定后再补应用合并
    await nextTick()
    await tableRef.value?.refreshColumn()
    tableRef.value?.setMergeCells(mergeCells.value)
  }
}

// 表格实例：用于数据重载后主动应用合并单元格
const tableRef = ref<VxeTableInstance>()

// 月份 Tab 切换
const handleMonthChange = () => {
  fetchView()
}

// 生成下月排期表：基于当前选中月 +1；尚无月表时生成当月（首月）
const handleCreateNextMonth = async () => {
  const base = months.value.find((m) => m.id === activeMonthId.value)
  const now = new Date()
  let year = now.getFullYear()
  let month = now.getMonth() + 1
  if (base) {
    month = base.month + 1
    year = base.year
    if (month > 12) {
      month = 1
      year += 1
    }
  }
  creating.value = true
  try {
    const res = await createScheduleMonth({ year, month })
    ElMessage.success(`${year}年${month}月排期表已生成`)
    // 刷新月份列表后选中新表并加载视图
    await fetchMonths()
    activeMonthId.value = res.data.data.id
    await fetchView()
  } catch {
    // 已存在等情况：拦截器已统一提示
  } finally {
    creating.value = false
  }
}

// ===== 从需求池添加 =====
const pickerVisible = ref(false)
const openPicker = () => {
  pickerVisible.value = true
}
// 添加成功：刷新视图（新需求分组与排期行）
const handleAdded = () => {
  fetchView()
}

// 点击需求名称进详情
const goDetail = (requirementId: number) => {
  router.push(`/requirements/${requirementId}`)
}

// 跳转日历管理（仅管理员入口）
const goCalendar = () => {
  router.push('/admin/calendar')
}

// 页面挂载：并行加载项目与月表列表，随后加载视图
onMounted(async () => {
  fetchProjects()
  await fetchMonths()
  await fetchView()
})
</script>

<template>
  <div class="schedule-page">
    <!-- 工具栏：月份 Tab + 右侧操作按钮 -->
    <div class="page-toolbar">
      <el-radio-group
        v-if="months.length"
        v-model="activeMonthId"
        size="small"
        @change="handleMonthChange"
      >
        <el-radio-button v-for="m in months" :key="m.id" :value="m.id">
          {{ m.year }}年{{ m.month }}月
        </el-radio-button>
      </el-radio-group>
      <span v-else class="toolbar-empty-tip">暂无排期表</span>
      <div class="toolbar-actions">
        <el-button v-if="canManage" :loading="creating" @click="handleCreateNextMonth">
          生成下月排期表
        </el-button>
        <el-button
          v-if="canManage"
          type="primary"
          :disabled="!activeMonthId"
          @click="openPicker"
        >
          从需求池添加
        </el-button>
        <el-button v-if="isAdmin" @click="goCalendar">日历管理</el-button>
      </div>
    </div>

    <!-- 排期矩阵 -->
    <el-card shadow="never" class="table-card">
      <div v-loading="viewLoading" class="matrix-wrap">
        <!-- 尚无任何月表：引导生成首月 -->
        <el-empty v-if="!months.length">
          <template #description>
            <div class="empty-guide">
              <div>暂无排期表</div>
              <div class="empty-sub">首月需由项目经理或管理员点击右上角「生成下月排期表」创建</div>
            </div>
          </template>
        </el-empty>
        <!-- 月表矩阵 -->
        <template v-else>
          <!-- 注：不加表格级 :key——vxe-table 整表重挂载时 staticColumns 对动态 v-for 列收集顺序错乱（日期列交错）；
               合并丢失问题改由 ref.loadMergeCells() 在数据加载后主动应用（见 fetchView） -->
          <vxe-table
            ref="tableRef"
            :data="flatRows"
            :merge-cells="mergeCells"
            :row-config="{ keyField: '_key', isHover: false }"
            :row-class-name="rowClassName"
            :cell-class-name="cellClassName"
            :header-cell-class-name="headerCellClassName"
            :column-config="{ resizable: true }"
            :scroll-x="{ enabled: false }"
            :scroll-y="{ enabled: false }"
            border
            size="small"
            class="matrix-table"
          >
            <!-- 需求级列：编号 / 需求名称（固定列）；项目/策划/优先级随滚动，为日期列让位 -->
            <vxe-column field="seq" title="编号" width="42" align="center" fixed="left" />
            <vxe-column field="title" title="需求名称" width="140" fixed="left">
              <template #default="{ row }">
                <div class="req-cell" @click="goDetail(row.requirementId)">
                  <span class="req-no">{{ row.reqNo }}</span>
                  <span class="req-title" :title="row.title">{{ row.title }}</span>
                </div>
              </template>
            </vxe-column>
            <vxe-column field="realName" title="负责人" width="80" fixed="left">
              <template #default="{ row }">
                <span class="owner-cell">
                  <span class="cell-text">{{ row.row.realName }}</span>
                  <span
                    v-if="row.row.isOverflow === 1"
                    class="overflow-badge"
                    title="由上月顺延生成"
                  >延</span>
                </span>
              </template>
            </vxe-column>
            <vxe-column field="craft" title="工种" width="80">
              <template #default="{ row }">
                <span class="cell-text">{{ craftLabel(row.row.craft) }}</span>
              </template>
            </vxe-column>
            <vxe-column field="projectName" title="项目" min-width="72">
              <template #default="{ row }">
                <span class="cell-text" :title="row.projectName">{{ row.projectName }}</span>
              </template>
            </vxe-column>
            <vxe-column field="plannerName" title="策划" min-width="64">
              <template #default="{ row }">
                <span class="cell-text">{{ row.plannerName || '—' }}</span>
              </template>
            </vxe-column>
            <vxe-column field="priority" title="优先级" width="50" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="priorityTagType(row.priority)" effect="dark">
                  {{ priorityLabel(row.priority) }}
                </el-tag>
              </template>
            </vxe-column>
            <!-- 行级列：工期 / 已排 -->
            <vxe-column field="plannedEffort" title="工期" width="80">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.row.plannedEffort"
                  class="effort-input"
                  size="small"
                  :min="0.1"
                  :step="0.1"
                  :precision="1"
                  controls-position="right"
                  clearable
                  placeholder="—"
                  :disabled="!cellEditable(row)"
                  @change="handleEffortChange(row)"
                />
              </template>
            </vxe-column>
            <vxe-column field="scheduled" title="已排" width="46" align="center">
              <template #default="{ row }">
                <span class="scheduled-sum">{{ scheduledSum(row.row) }}</span>
              </template>
            </vxe-column>
            <!-- 日期列：当月每天一列（窄列 36px 平铺），仅可排班日可填；
                 key 用日号（非完整日期）：跨月切换时 1~30 号列原位复用，仅追加/移除 31 号列，
                 避免 vxe-table 动态列全量重建触发 staticColumns 交错收集（顺序错乱）与合并重置 -->
            <vxe-column
              v-for="d in view?.days || []"
              :key="'day-' + dayNumber(d.day)"
              :field="d.day"
              width="30"
              align="center"
            >
              <template #header>
                <div
                  class="day-head"
                  :class="{
                    'day-off': !isSchedulable(d),
                    'day-adjusted': d.type === 'ADJUSTED'
                  }"
                  :title="headerTitle(d)"
                >
                  <div class="day-num">{{ dayNumber(d.day) }}</div>
                  <div class="day-week">{{ weekdayLabel(d.day) }}</div>
                </div>
              </template>
              <template #default="{ row }">
                <!-- 编辑态：原生 input（回车/失焦提交，Esc 取消，空=清空） -->
                <div v-if="isEditing(row, d.day)" class="cell-editing">
                  <input
                    v-cell-focus
                    v-model="editing.value"
                    class="cell-input"
                    type="text"
                    inputmode="decimal"
                    @keyup.enter="commitCell"
                    @keyup.esc="cancelCell"
                    @blur="commitCell"
                  />
                </div>
                <!-- 展示态：TODO 且可排班日的格子可点击进入编辑 -->
                <div
                  v-else
                  class="cell-value"
                  :class="{ editable: cellEditable(row) && isSchedulable(d) }"
                  @click="startEdit(row, d)"
                >
                  {{ cellText(row, d) }}
                </div>
              </template>
            </vxe-column>
            <!-- 状态 / 操作（右侧固定） -->
            <vxe-column field="status" title="状态" width="68" align="center" fixed="right">
              <template #default="{ row }">
                <el-tag size="small" :type="rowStatusInfo(row.row).type" effect="dark">
                  {{ rowStatusInfo(row.row).label }}
                </el-tag>
              </template>
            </vxe-column>
            <vxe-column field="action" title="操作" width="80" align="center" fixed="right">
              <template #default="{ row }">
                <el-button v-if="canCopy(row)" link type="primary" size="small" @click="handleCopy(row)">
                  添加到下月
                </el-button>
                <span v-else class="cell-text"></span>
              </template>
            </vxe-column>
            <!-- 当月无需求分组 -->
            <template #empty>
              <el-empty description="从需求池添加需求开始排期" :image-size="80" />
            </template>
          </vxe-table>
          <!-- 图例说明 -->
          <div class="matrix-legend">
            <span class="legend-item"><i class="legend-swatch filled" />已排工作量</span>
            <span class="legend-item"><i class="legend-swatch locked" />周末 / 节假日（不可排）</span>
            <span class="legend-item"><i class="legend-swatch adjusted" />调休上班</span>
            <span class="legend-item legend-tip">格子点击填写：0.1 步进，单格 0~1；失焦或回车自动保存</span>
          </div>
        </template>
      </div>
    </el-card>

    <!-- 从需求池添加弹窗 -->
    <ScheduleRequirementPicker
      v-model="pickerVisible"
      :month-id="activeMonthId"
      @success="handleAdded"
    />
  </div>
</template>

<style scoped>
/* 页面：纵向排布工具栏与矩阵卡片 */
.schedule-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 顶部工具栏：白底圆角，操作按钮靠右 */
.page-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  background: #fff;
  border-radius: 4px;
}

/* 无月表时的占位提示 */
.toolbar-empty-tip {
  font-size: 14px;
  color: #909399;
}

/* 工具栏右侧按钮组 */
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 矩阵外层：白卡内滚动 */
.table-card {
  overflow: hidden;
}

.matrix-wrap {
  min-height: 320px;
}

/* 首月引导文案 */
.empty-guide {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 14px;
  color: #909399;
}

.empty-sub {
  font-size: 12px;
}

/* 通用单元格文本：超长省略 */
.cell-text {
  display: block;
  overflow: hidden;
  font-size: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 需求单元格：编号 + 名称两行，整体可点击进详情 */
.req-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  cursor: pointer;
}

.req-cell:hover .req-title {
  color: #409eff;
}

/* 需求编号：等宽小字 */
.req-no {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  color: #909399;
}

/* 需求名称：按列宽自适应换行 */
.req-title {
  font-size: 13px;
  white-space: normal;
  word-break: break-all;
  line-height: 1.4;
}

/* 负责人单元格：姓名 + 顺延徽标 */
.owner-cell {
  position: relative;
  display: block;
  padding-right: 14px;
}

/* 顺延徽标：上月顺延生成的行 */
.overflow-badge {
  position: absolute;
  top: 0;
  right: 0;
  padding: 0 3px;
  font-size: 10px;
  line-height: 14px;
  color: #e6a23c;
  border: 1px solid #f3d19e;
  border-radius: 2px;
}

/* 工期输入框：铺满列宽 */
.effort-input {
  width: 100%;
}

/* 已排合计：只读灰字 */
.scheduled-sum {
  font-size: 13px;
  color: #909399;
}

/* 日期列表头：日号 + 星期两行 */
.day-head {
  padding: 2px 0;
  line-height: 15px;
  cursor: default;
}

.day-num {
  font-size: 12px;
  font-weight: 600;
}

.day-week {
  font-size: 11px;
  color: #909399;
}

/* 非可排班日列表头：整体灰显 */
.day-off .day-num,
.day-off .day-week {
  color: #c0c4cc;
}

/* 调休上班日：绿色数字提示可排 */
.day-adjusted .day-num {
  color: #67c23a;
}

/* 日期列展示格：数字加深加粗，扫视更清晰 */
.cell-value {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 22px;
  font-size: 12px;
  font-weight: 600;
  color: #303133;
}

/* 可编辑格子：手型 + 悬停高亮 */
.cell-value.editable {
  cursor: pointer;
}

.cell-value.editable:hover {
  background: #d9ecff;
}

/* 编辑态输入框：铺满格子、居中无边框 */
.cell-input {
  width: 100%;
  padding: 0;
  font-size: 12px;
  text-align: center;
  background: transparent;
  border: none;
  border-bottom: 1px solid #409eff;
  outline: none;
}

/* 图例 */
.matrix-legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  padding-top: 10px;
  font-size: 12px;
  color: #909399;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.legend-tip {
  margin-left: auto;
}

/* 图例色块 */
.legend-swatch {
  display: inline-block;
  width: 14px;
  height: 10px;
  border-radius: 2px;
}

/* 已排：中蓝（与格子同步加深） */
.legend-swatch.filled {
  background: #c6e2ff;
  border: 1px solid #8cc5ff;
}

/* 不可排：斜纹灰底加深（与格子同步） */
.legend-swatch.locked {
  background: repeating-linear-gradient(45deg, #dfe3ea, #dfe3ea 2px, #c5cdd9 2px, #c5cdd9 4px);
  border: 1px solid #c5cdd9;
}

/* 调休：深绿色 */
.legend-swatch.adjusted {
  background: #e1f3d8;
  border: 1px solid #3f9a2d;
}

/* ===== vxe-table 内部样式覆盖（scoped 需 :deep） ===== */
/* 全表格：黑色实线边框（每个单元格 + 表头），边框独立避免塌陷 */
:deep(.matrix-table) .vxe-table--header-wrapper .vxe-header--column,
:deep(.matrix-table) .vxe-table--body-wrapper .vxe-body--column {
  border: 1px solid #303133;
}

/* 合并单元格保持外边框完整（合并后单元格不再重复画内线） */
:deep(.matrix-table) .vxe-table--body .vxe-body--row .vxe-body--column {
  border-bottom: 1px solid #303133;
}

/* 有值格子：加深蓝色底 + 深蓝粗体数字，扫视更清晰 */
:deep(td.cell-filled) {
  background-color: #c6e2ff;
  font-weight: 700;
  color: #1b4f8a;
}

/* 非可排班日格子：斜纹灰底加深（高对比条纹，与可排班白格明显区分，提高特异度压过 vxe 默认 background-image） */
:deep(td.vxe-body--column.cell-locked) {
  background-color: #dfe3ea;
  background-image: repeating-linear-gradient(
    45deg,
    transparent,
    transparent 3px,
    #c5cdd9 3px,
    #c5cdd9 6px
  ) !important;
}

/* 非可排班日格子文字：保持黑色清晰可读 */
:deep(td.cell-locked) .vxe-cell {
  color: #303133;
}

/* 日期列格子：压缩左右内边距 */
:deep(td.cell-day) .vxe-cell {
  padding: 0 2px;
}

/* 非可排班日列头：浅灰底（与正文周末格同步，提高特异度压过表头通用底色） */
:deep(th.vxe-header--column.head-locked) {
  background-color: #eef0f4 !important;
  color: #909399;
}

/* 日期列头：压缩内边距 */
:deep(th.head-day) .vxe-cell {
  padding: 0 2px;
}

/* 表头整体：深色底白字，与正文区分 */
:deep(.matrix-table) .vxe-table--header-wrapper .vxe-header--column {
  background-color: #f0f2f5;
  font-weight: 600;
  color: #303133;
}

/* 非可排班日列头文字：黑色清晰 */
:deep(th.head-locked) .day-num,
:deep(th.head-locked) .day-week {
  color: #303133;
}

/* 调休上班日：绿色加深 */
:deep(th .day-adjusted) .day-num,
.day-adjusted .day-num {
  color: #3f9a2d;
  font-weight: 700;
}

/* 作废行：仅保留删除线标识，文字保持黑色 */
:deep(tr.row-void) .vxe-cell {
  color: #303133;
  text-decoration: line-through;
}
</style>
