<script setup lang="ts">
import { computed, h, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
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
  copyRequirementToNextMonth,
  removeRequirementFromMonth,
  listIncompleteRequirements,
  type ScheduleMonth,
  type ScheduleView,
  type ScheduleRowData,
  type IncompleteRequirement
} from '@/api/schedule'
import type { CalendarDayData, CalendarDayType } from '@/api/calendar'
import { craftLabel } from '@/constants/craft'
import { priorityLabel, priorityTagType } from '@/constants/requirement'
import {
  buildFilterOptions,
  buildMergeCells,
  buildRowCells,
  computeScheduledSum,
  deriveRowStatus,
  filterScheduleRows,
  isConflictError,
  localCellSum,
  type ScheduleFilterField as FilterField
} from '@/utils/schedule'
import ScheduleRequirementPicker from '@/components/ScheduleRequirementPicker.vue'
import ScheduleFilterHeader from '@/components/ScheduleFilterHeader.vue'

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
  reqStatus: string
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
      reqStatus: g.reqStatus,
      row: r
    }))
  )
})

// 需求级合并列下标：编号(0) / 需求名称(1) / 项目(2) / 策划(3) / 优先级(4)——工种(5)/负责人(6)为行级列不合并
const GROUP_COL_INDEXES = [0, 1, 2, 3, 4]

// ===== 列筛选（表头右下角三角按钮，勾选面板：去重合并统计 + 全选/反选/清除/确定） =====
// 筛选状态：null=未筛选；否则为勾选的归一化值集合
const colFilters = reactive<Record<FilterField, string[] | null>>({
  title: null,
  projectName: null,
  plannerName: null,
  priority: null,
  realName: null
})

// 按当前筛选状态过滤后的表格行（负责人为行级条件，其余为需求级条件）
const filteredFlatRows = computed<FlatRow[]>(() =>
  filterScheduleRows(flatRows.value, colFilters)
)

// 筛选面板候选项：从当前月全部行（未筛选）去重合并统计
const colFilterOptions = (field: FilterField) =>
  buildFilterOptions(flatRows.value, field)

// 筛选面板单例状态：vxe 固定列复制渲染表头，:visible 共享会双开面板，
// 故面板用 Teleport 单例 + 按触发按钮位置定位；checked 为打开时的初始勾选
const filterPanel = reactive<{ open: boolean; field: FilterField | null; checked: string[] }>({
  open: false,
  field: null,
  checked: []
})

// 面板定位（fixed，跟随触发按钮右下角）
const PANEL_WIDTH = 210
const panelPos = reactive({ top: 0, left: 0 })

const panelVisible = (field: FilterField) => filterPanel.open && filterPanel.field === field

const panelInitialChecked = (field: FilterField): string[] => {
  const active = colFilters[field]
  return active ? [...active] : colFilterOptions(field).map((o) => o.value)
}

const togglePanel = (field: FilterField, ev: MouseEvent) => {
  if (panelVisible(field)) {
    filterPanel.open = false
    return
  }
  const rect = (ev.currentTarget as HTMLElement).getBoundingClientRect()
  panelPos.top = rect.bottom + 4
  panelPos.left = Math.max(8, Math.min(rect.right - PANEL_WIDTH + 12, window.innerWidth - PANEL_WIDTH - 8))
  filterPanel.field = field
  filterPanel.checked = panelInitialChecked(field)
  filterPanel.open = true
}

// 全选勾选框三态：勾=全选，空=全不选，半态=部分选中
const panelAllChecked = computed(() => {
  if (!filterPanel.field) return false
  const opts = colFilterOptions(filterPanel.field)
  return opts.length > 0 && filterPanel.checked.length >= opts.length
})

const panelIndeterminate = computed(() => {
  if (!filterPanel.field) return false
  const opts = colFilterOptions(filterPanel.field)
  return filterPanel.checked.length > 0 && filterPanel.checked.length < opts.length
})

const toggleAllPanel = () => {
  if (!filterPanel.field) return
  filterPanel.checked = panelAllChecked.value
    ? []
    : colFilterOptions(filterPanel.field).map((o) => o.value)
}

// 反选：勾选当前未勾选项
const invertPanel = () => {
  if (!filterPanel.field) return
  const cur = new Set(filterPanel.checked)
  filterPanel.checked = colFilterOptions(filterPanel.field)
    .filter((o) => !cur.has(o.value))
    .map((o) => o.value)
}

// 确定：全不选或全选视为清除筛选，其余按勾选集合过滤
const confirmFilter = (field: FilterField, checked: string[]) => {
  const total = colFilterOptions(field).length
  colFilters[field] = checked.length === 0 || checked.length >= total ? null : [...checked]
  filterPanel.open = false
}

const clearFilter = (field: FilterField) => {
  colFilters[field] = null
  filterPanel.open = false
}

// 点击面板与触发按钮之外的区域关闭面板（capture 阶段，先于触发按钮的 @click.stop）
const handleDocClickForFilter = (e: MouseEvent) => {
  if (!filterPanel.open) return
  const t = e.target as HTMLElement | null
  if (t && (t.closest('.col-filter-pop') || t.closest('.filter-trigger'))) return
  filterPanel.open = false
}

// 合并单元格配置：筛选后同需求幸存行仍连续，按连续段计算需求级列纵向合并
const mergeCells = computed<VxeTablePropTypes.MergeCells>(() =>
  buildMergeCells(filteredFlatRows.value, GROUP_COL_INDEXES)
)

// 数据（含筛选结果）变化后重应用合并：先清旧合并再按新行集设置，避免残留跨行错误指向
watch(filteredFlatRows, async () => {
  await nextTick()
  tableRef.value?.clearMergeCells()
  tableRef.value?.setMergeCells(mergeCells.value)
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

// ISO 周序号（周一为一周起点，周四定位所属年份）：跨月连续、跨年稳定，用作每周配色的分组键
const isoWeekKey = (day: string): number => {
  const [y, m, d] = day.split('-').map(Number)
  const date = new Date(y, m - 1, d)
  const shift = (date.getDay() + 6) % 7 // 周一=0 … 周日=6
  date.setDate(date.getDate() - shift + 3) // 落到本周周四，确定 ISO 年归属
  const firstThursday = new Date(date.getFullYear(), 0, 4)
  firstThursday.setDate(firstThursday.getDate() - ((firstThursday.getDay() + 6) % 7) + 3)
  return Math.round((date.getTime() - firstThursday.getTime()) / 604800000) + 1
}

// 每周底色色板：6 色循环，保证相邻周一定不同色
const WEEK_COLOR_COUNT = 6
const weekColorIndex = (day: string) => {
  const k = isoWeekKey(day) % WEEK_COLOR_COUNT
  return k < 0 ? k + WEEK_COLOR_COUNT : k
}

// 当月自然周分组（含起止日）：图例按周展示对应底色
const monthWeeks = computed<{ week: number; index: number; start: string; end: string }[]>(() => {
  const map = new Map<number, { start: string; end: string }>()
  for (const d of view.value?.days || []) {
    const key = isoWeekKey(d.day)
    const cur = map.get(key)
    if (cur) cur.end = d.day
    else map.set(key, { start: d.day, end: d.day })
  }
  return [...map.entries()].map(([week, r]) => ({
    week,
    index: (week % WEEK_COLOR_COUNT + WEEK_COLOR_COUNT) % WEEK_COLOR_COUNT,
    start: r.start,
    end: r.end
  }))
})

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
// 行样式：作废行删除线灰显；已归档需求的行禁止光标（排期只读）
const rowClassName: VxeTablePropTypes.RowClassName<FlatRow> = ({ row }) => {
  if (row.row.status === 'VOID') return 'row-void'
  if (row.reqStatus === 'ARCHIVED') return 'row-archived'
  return ''
}

// 单元格样式：已排期格子按自然周交替底色；非可排班日斜纹灰
const cellClassName: VxeTablePropTypes.CellClassName<FlatRow> = ({ row, column }) => {
  const day = column?.field || ''
  const dayInfo = dayMap.value[day]
  if (!dayInfo) return ''
  if (!isSchedulable(dayInfo)) return 'cell-day cell-locked'
  const v = row.row.cells[day]
  if (v != null && v > 0) return `cell-day cell-filled wk${weekColorIndex(day)}`
  return 'cell-day'
}

// 表头单元格样式：非可排班日列头灰显
const headerCellClassName: VxeTablePropTypes.HeaderCellClassName = ({ column }) => {
  const dayInfo = dayMap.value[column?.field || '']
  return dayInfo && !isSchedulable(dayInfo) ? 'head-day head-locked' : 'head-day'
}

// ===== 单元格编辑 =====
// 当前行格子是否可编辑：非作废行随时可编辑 + 本人是该项目 PM + 需求未归档（已归档排期只读）
const cellEditable = (row: FlatRow) =>
  row.row.status !== 'VOID' && isPmOf(row.projectId) && row.reqStatus !== 'ARCHIVED'

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
const buildCells = (row: ScheduleRowData): Record<string, number> =>
  buildRowCells(row.cells, (view.value?.days || []).map((d) => d.day))

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
        if (isConflictError(error)) {
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

// 已排合计（跨全部月份）：后端组内合计 - 加载时本行格子合计 + 本地当前格子合计
// （本地编辑格子时实时反映：保存前本地即为最新，保存成功后本地值即已持久化）
const rowSumAtLoad = ref<Record<number, number>>({})
const scheduledSum = (row: ScheduleRowData): number =>
  computeScheduledSum(row.scheduledSum, rowSumAtLoad.value[row.rowId], row.cells)

// ===== 行状态（三态派生）与整个需求添加到下月 =====
// 行状态展示：VOID 已作废；需求已归档 → 已归档（排期只读）；未设工期 → 待排期（灰）；
// 跨月已排合计 < 工期 → 排期中（橙）；跨月已排合计 ≥ 工期 → 已排期（绿）
const rowStatusInfo = (row: FlatRow) =>
  deriveRowStatus({
    rowStatus: row.row.status,
    reqStatus: row.reqStatus,
    plannedEffort: row.row.plannedEffort,
    scheduledSum: scheduledSum(row.row)
  })

// 整个需求添加到下月/删除按钮可见：非作废行 + 本人是该项目 PM + 需求未归档（已归档排期只读）
const canCopy = (row: FlatRow) =>
  row.row.status !== 'VOID' && isPmOf(row.projectId) && row.reqStatus !== 'ARCHIVED'

// 点击添加到下月：确认后调接口（该需求全部工种×负责人逐组插入下月新行：
// 格子空白、工期跨月共用；下月表不存在自动创建），完成后跳转下月视图
const handleCopy = async (row: FlatRow) => {
  try {
    await ElMessageBox.confirm(
      `确认将整个需求「${row.title}」添加到下月排期表？（全部工种×负责人，格子空白，工期跨月共用）`,
      '添加到下月',
      { type: 'warning', confirmButtonText: '添加', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await copyRequirementToNextMonth(row.row.rowId)
    if (res.data.data.duplicated) {
      ElMessage.success(`已添加到下月排期表（新插入 ${res.data.data.insertedCount} 行）`)
    } else {
      ElMessage.warning('该需求已在下月排期中')
    }
    await fetchMonths()
    activeMonthId.value = res.data.data.monthId
    await fetchView()
  } catch {
    // 失败已由拦截器统一提示
  }
}

// 从当月移除整个需求（PM）：确认后调接口（删除该需求当月全部行及格子，其他月份不受影响）
const handleRemove = async (row: FlatRow) => {
  try {
    await ElMessageBox.confirm(
      `确认将「${row.title}」从当月排期表移除？（该需求本月全部排期行及格子将删除，其他月份不受影响）`,
      '移除需求',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    if (!activeMonthId.value) return
    const res = await removeRequirementFromMonth(activeMonthId.value, row.requirementId)
    ElMessage.success(`已从当月排期表移除（删除 ${res.data.data} 行）`)
    await fetchView()
  } catch {
    // 失败已由拦截器统一提示
  }
}

// ===== 数据加载 =====
// 拉取项目列表（PM 权限判断；一次拉取五百条，覆盖绝大多数场景）
const fetchProjects = async () => {
  try {
    const res = await listProjects({ page: 1, size: 500 })
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
    // 记录各行加载时的格子合计（跨月已排合计的本地实时计算基准）
    const sums: Record<number, number> = {}
    for (const g of res.data.data.groups) {
      for (const r of g.rows) {
        sums[r.rowId] = localCellSum(r.cells)
      }
    }
    rowSumAtLoad.value = sums
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
// 当月已排的需求 id 集合（Picker 标记"已在当月"并禁选，后端仍兜底校验唯一）
const existingReqIds = computed<number[]>(() =>
  (view.value?.groups || []).map((g) => g.requirementId)
)
// 添加成功：刷新视图（新需求分组与排期行）
const handleAdded = () => {
  fetchView()
}

// ===== 退出拦截：排期未完成不许离开 =====
// 后端 /schedule/incomplete 仅返回当前用户为项目 PM 的可修复需求（非终态+未排满+开发已开始）；
// 逃生门：允许跳转到未完成需求的详情页取消需求（取消后不再拦截），其余导航强制弹窗阻止

// 未完成排期缓存标志：beforeunload（F5/关闭标签页）回调是同步的、无法实时查询，
// 用缓存值兜底判断；挂载时、路由离开检查时、视图刷新（写操作后）都会更新
const hasIncomplete = ref(false)

// 刷新未完成排期缓存（静默失败：缓存仅用于 beforeunload 兜底，失败保持原值）
const refreshIncompleteCache = async () => {
  try {
    const res = await listIncompleteRequirements()
    hasIncomplete.value = (res.data.data || []).length > 0
  } catch {
    // 保持原值
  }
}

// F5/关闭标签页：路由守卫不生效，用 beforeunload 兜底触发浏览器原生确认框
//（原生框无法自定义文案与逃生门，仅提示"确定离开"；站内导航仍走 onBeforeRouteLeave 的可交互弹窗）
const handleBeforeUnload = (e: BeforeUnloadEvent) => {
  if (hasIncomplete.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

let guardDialogOpen = false
onBeforeRouteLeave(async (to) => {
  // 以 localStorage token 为准（请求拦截器的真实凭证来源）：登出或 401 过期时拦截器已清空，
  // 此时再查排期注定 401，直接放行去登录页，否则「守卫调接口→401→跳登录→再触发守卫」死循环
  if (!localStorage.getItem('token')) return true
  let incomplete: IncompleteRequirement[] = []
  try {
    const res = await listIncompleteRequirements()
    incomplete = res.data.data || []
    // 顺带同步 beforeunload 兜底缓存
    hasIncomplete.value = incomplete.length > 0
  } catch {
    // 登录过期（拦截器已清 token 并跳登录）：静默放行，避免死循环
    if (!localStorage.getItem('token')) return true
    // 其余接口异常（如后端未部署新版本）：明确提示而非静默拦截，宁可拦截不可放行
    ElMessage.error('无法检查排期完成状态（接口异常），请确认后端已更新后重试')
    return false
  }
  if (incomplete.length === 0) return true
  // 逃生门：目标为未完成需求的详情页 → 放行（去详情页取消需求），取消后该需求不再拦截
  const detailMatch = to.path.match(/^\/requirements\/(\d+)$/)
  if (detailMatch && incomplete.some((r) => r.requirementId === Number(detailMatch[1]))) {
    return true
  }
  // 弹窗已打开时不再重复弹（并发触发导航直接拦截）
  if (guardDialogOpen) return false
  guardDialogOpen = true
  try {
    await ElMessageBox.alert(
      h('div', null, [
        h('p', { style: 'margin: 0 0 8px' }, '以下需求排期未完成，请先完成排期后再离开：'),
        h('p', { style: 'margin: 0 0 8px; color: #909399; font-size: 12px' },
          '也可点击需求进详情页取消需求（取消后排期不再拦截）'),
        h(
          'ul',
          {
            style:
              'margin: 0; padding-left: 18px; max-height: 220px; overflow: auto; text-align: left'
          },
          incomplete.map((r) =>
            h(
              'li',
              {
                style: 'cursor: pointer; color: #1976d2; text-decoration: underline',
                onClick: () => {
                  ElMessageBox.close()
                  router.push(`/requirements/${r.requirementId}`)
                }
              },
              `${r.reqNo} ${r.title}`
            )
          )
        )
      ]),
      '排期未完成',
      {
        type: 'warning',
        confirmButtonText: '去完成排期',
        showClose: false,
        closeOnClickModal: false,
        closeOnPressEscape: false
      }
    )
    return false
  } catch {
    return false
  } finally {
    guardDialogOpen = false
  }
})

// 点击需求名称进详情
const goDetail = (requirementId: number) => {
  router.push(`/requirements/${requirementId}`)
}

// 跳转日历管理（仅管理员入口）
const goCalendar = () => {
  router.push('/admin/calendar')
}

// ===== 表格可视高度 =====
// 表格按「内容高度」渲染，最多长到可视区（max-height）：
// 内容少 → 表格紧贴内容，四周黑框闭合，下方不留白；内容多 → 达到上限后内部滚动（表头仍固定）。
// 🔴 不能再用 height="auto"（撑满容器）：那样内容下方会空出一大块，边框线永远够不到表格底边。
const matrixWrapRef = ref<HTMLElement | null>(null)
const tableMaxHeight = ref(0)
let wrapResizeObserver: ResizeObserver | null = null

// 页面挂载：并行加载项目与月表列表，随后加载视图；注册 F5/关标签页兜底拦截
onMounted(async () => {
  window.addEventListener('beforeunload', handleBeforeUnload)
  // 列筛选面板：点击外部关闭（capture 阶段，先于触发按钮的 @click.stop 生效）
  window.addEventListener('click', handleDocClickForFilter, true)
  // 可视区高度测量：窗口/侧栏变化时同步给表格的 max-height
  if (matrixWrapRef.value) {
    wrapResizeObserver = new ResizeObserver(() => {
      tableMaxHeight.value = matrixWrapRef.value?.clientHeight || 0
    })
    wrapResizeObserver.observe(matrixWrapRef.value)
  }
  refreshIncompleteCache()
  fetchProjects()
  await fetchMonths()
  await fetchView()
})

// 卸载：移除 beforeunload 监听（离开排期页后不再拦截刷新/关闭）
onUnmounted(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  window.removeEventListener('click', handleDocClickForFilter, true)
  wrapResizeObserver?.disconnect()
  wrapResizeObserver = null
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
      <div v-loading="viewLoading" class="matrix-wrap" ref="matrixWrapRef">
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
          <!-- 注：滚动两个方向都开 enabled:true，否则超出可视区的行/列连同最外圈黑框一起被裁掉、永远看不到。
               🔴 gt 必须显式给 -1：vxe 判定虚拟滚动的条件是 enabled && gt > -1，gt:0 = "总是虚拟滚动"，
                  只渲染可视行会打乱 merge-cells 的合并计算。-1 = 全量渲染 DOM，仅给容器加原生滚动条。 -->
          <vxe-table
            ref="tableRef"
            :data="filteredFlatRows"
            :merge-cells="mergeCells"
            :row-config="{ keyField: '_key', isHover: false }"
            :row-class-name="rowClassName"
            :cell-class-name="cellClassName"
            :header-cell-class-name="headerCellClassName"
            :column-config="{ resizable: true }"
            :scroll-x="{ enabled: true, gt: -1 }"
            :scroll-y="{ enabled: true, gt: -1 }"
            :show-header-overflow="'title'"
            :max-height="tableMaxHeight || undefined"
            border
            size="small"
            class="matrix-table"
          >
            <!-- 需求级列：编号 / 需求名称+操作按钮（固定列）；项目/策划/优先级随滚动，为日期列让位 -->
            <vxe-column field="seq" title="编号" width="42" align="center" fixed="left" />
            <vxe-column field="title" title="需求名称" width="140" align="center" fixed="left">
              <template #header>
                <ScheduleFilterHeader
                  title="需求名称"
                  :active="!!colFilters.title"
                  @toggle="(ev) => togglePanel('title', ev)"
                />
              </template>
              <template #default="{ row }">
                <div class="req-cell">
                  <div class="req-info" @click="goDetail(row.requirementId)">
                    <span class="req-no">{{ row.reqNo }}</span>
                    <span class="req-title" :title="row.title">{{ row.title }}</span>
                  </div>
                  <!-- 需求级操作：添加到下月 / 从当月移除（合并单元格垂直水平居中） -->
                  <div v-if="canCopy(row)" class="req-actions">
                    <el-button link type="primary" size="small" @click.stop="handleCopy(row)">
                      添加到下月
                    </el-button>
                    <el-button link type="danger" size="small" @click.stop="handleRemove(row)">
                      删除
                    </el-button>
                  </div>
                </div>
              </template>
            </vxe-column>
            <vxe-column field="projectName" title="项目" min-width="72" align="center">
              <template #header>
                <ScheduleFilterHeader
                  title="项目"
                  :active="!!colFilters.projectName"
                  @toggle="(ev) => togglePanel('projectName', ev)"
                />
              </template>
              <template #default="{ row }">
                <span class="cell-text" :title="row.projectName">{{ row.projectName }}</span>
              </template>
            </vxe-column>
            <vxe-column field="plannerName" title="策划" min-width="64" align="center">
              <template #header>
                <ScheduleFilterHeader
                  title="策划"
                  :active="!!colFilters.plannerName"
                  @toggle="(ev) => togglePanel('plannerName', ev)"
                />
              </template>
              <template #default="{ row }">
                <span class="cell-text">{{ row.plannerName || '—' }}</span>
              </template>
            </vxe-column>
            <vxe-column field="priority" title="优先级" width="50" align="center">
              <template #header>
                <ScheduleFilterHeader
                  title="优先级"
                  :active="!!colFilters.priority"
                  @toggle="(ev) => togglePanel('priority', ev)"
                />
              </template>
              <template #default="{ row }">
                <el-tag size="small" :type="priorityTagType(row.priority)" effect="dark">
                  {{ priorityLabel(row.priority) }}
                </el-tag>
              </template>
            </vxe-column>
            <vxe-column field="craft" title="工种" width="80" align="center">
              <template #default="{ row }">
                <span class="cell-text">{{ craftLabel(row.row.craft) }}</span>
              </template>
            </vxe-column>
            <vxe-column field="realName" title="负责人" width="80" align="center">
              <template #header>
                <ScheduleFilterHeader
                  title="负责人"
                  :active="!!colFilters.realName"
                  @toggle="(ev) => togglePanel('realName', ev)"
                />
              </template>
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
            <!-- 行级列：工期 / 已排 -->
            <vxe-column field="plannedEffort" title="工期" width="80" align="center">
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
            <!-- 状态（右侧固定） -->
            <vxe-column field="status" title="状态" width="68" align="center" fixed="right">
              <template #default="{ row }">
                <el-tag size="small" :type="rowStatusInfo(row).type" effect="dark">
                  {{ rowStatusInfo(row).label }}
                </el-tag>
              </template>
            </vxe-column>
            <!-- 当月无需求分组 -->
            <template #empty>
              <el-empty description="从需求池添加需求开始排期" :image-size="80" />
            </template>
          </vxe-table>
        </template>
      </div>
      <!-- 图例：必须放在 matrix-wrap 之外——matrix-wrap 的高度就是表格的 max-height，
           图例若留在里面会挤掉表格可用高度，也会让"表格底边"计算不准 -->
      <div v-if="months.length" class="matrix-legend">
        <span class="legend-item legend-tip">格子点击填写：0.1 步进，单格 0~1；失焦或回车自动保存（已排格子按周交替底色 + 深蓝粗体数字）</span>
        <span class="legend-item"><i class="legend-swatch locked" />周末 / 节假日（不可排）</span>
        <span class="legend-item"><i class="legend-swatch adjusted" />调休上班</span>
      </div>
      <!-- 每周底色图例：当月每个自然周一段，已排期格子底色与之对应 -->
      <div v-if="months.length && monthWeeks.length" class="week-legend">
        <span class="week-legend-label">已排期格子底色（按周区分）：</span>
        <span v-for="w in monthWeeks" :key="w.week" class="week-legend-item">
          <i class="legend-swatch" :class="`wk${w.index}`" />
          {{ Number(w.start.slice(5, 7)) }}/{{ Number(w.start.slice(8, 10)) }}–{{
            Number(w.end.slice(5, 7))
          }}/{{ Number(w.end.slice(8, 10)) }}
        </span>
      </div>
    </el-card>

    <!-- 从需求池添加弹窗 -->
    <ScheduleRequirementPicker
      v-model="pickerVisible"
      :month-id="activeMonthId"
      :existing-ids="existingReqIds"
      @success="handleAdded"
    />

    <!-- 列筛选单例面板：teleport 到 body，按触发按钮位置定位（固定列复制渲染下保证只有一个面板） -->
    <Teleport to="body">
      <div
        v-if="filterPanel.open && filterPanel.field"
        class="col-filter-pop"
        :style="{ top: panelPos.top + 'px', left: panelPos.left + 'px' }"
        @click.stop
      >
        <div class="cf-actions">
          <el-checkbox
            class="cf-select-all"
            :model-value="panelAllChecked"
            :indeterminate="panelIndeterminate"
            @change="toggleAllPanel"
          >全选</el-checkbox>
          <el-button link size="small" @click="invertPanel">反选</el-button>
          <el-button
            link
            size="small"
            :disabled="!colFilters[filterPanel.field]"
            @click="clearFilter(filterPanel.field)"
          >清除</el-button>
        </div>
        <el-checkbox-group v-model="filterPanel.checked" class="cf-list">
          <el-checkbox v-for="o in colFilterOptions(filterPanel.field)" :key="o.value" :value="o.value">
            <span class="cf-label" :title="o.label">{{ o.label }}</span>
            <span class="cf-count">({{ o.count }})</span>
          </el-checkbox>
        </el-checkbox-group>
        <div class="cf-footer">
          <el-button
            type="primary"
            size="small"
            @click="confirmFilter(filterPanel.field, filterPanel.checked)"
          >确定</el-button>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/* 页面：纵向排布工具栏与矩阵卡片，整体占满可视高度（外层不出滚动条） */
.schedule-page {
  /* 每周底色：6 色循环，均为浅色底以保证深蓝粗体数字可读 */
  --wk0: #d6e9ff; /* 浅蓝 */
  --wk1: #dbf2d9; /* 浅绿 */
  --wk2: #ffe6c4; /* 浅橙 */
  --wk3: #e6dcfa; /* 浅紫 */
  --wk4: #ffd9e0; /* 浅粉 */
  --wk5: #d0eeee; /* 浅青 */
  display: flex;
  flex-direction: column;
  gap: 16px;
  /* 视口高度 - 顶栏 60px - el-main 上下内边距 40px，页面自身不产生滚动 */
  height: calc(100vh - 100px);
  overflow: hidden;
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

/* 矩阵外层：白卡内滚动；卡片弹性填充剩余高度 */
.table-card {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* 卡片体同样纵向弹性填充 */
.table-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 表格容器：弹性填充（表格 height="auto" 据此计算可见区域，超出在表格内部滚动） */
.matrix-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 表格按内容高度渲染，不拉伸（上限由 :max-height 控制，见 matrix-wrap 的高度测量）。
   🔴 不要改回 flex:1：那会让表格撑满容器，内容不满时下方空出一大块，黑框底边远离内容。 */
.matrix-wrap :deep(.matrix-table) {
  flex: 0 0 auto;
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

/* 需求单元格：编号+名称 与「添加到下月」按钮，整体垂直水平居中（合并单元格内） */
.req-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

/* 需求信息区（编号+名称）：可点击进详情 */
.req-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  cursor: pointer;
}

.req-info:hover .req-title {
  color: #409eff;
}

/* 需求级操作按钮组：水平排布居中 */
.req-actions {
  display: flex;
  align-items: center;
  justify-content: center;
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

/* 可编辑格子：手型；悬停不加填充色 */
.cell-value.editable {
  cursor: pointer;
}

/* 编辑态输入框：铺满格子、居中无边框，聚焦与输入均为透明底 */
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

.cell-input:focus {
  background: transparent;
}

.cell-input::selection {
  background: transparent;
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

/* 不可排：斜纹灰底加深（与格子同步） */
.legend-swatch.locked {
  background: repeating-linear-gradient(45deg, #cdd3dd, #cdd3dd 2px, #aab4c4 2px, #aab4c4 4px);
  border: 1px solid #aab4c4;
}

/* 调休：深绿色 */
.legend-swatch.adjusted {
  background: #e1f3d8;
  border: 1px solid #3f9a2d;
}

/* 每周底色图例：位于图例下方单独一行，色块与格子底色同源 */
.week-legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  padding-top: 8px;
  font-size: 12px;
  color: #606266;
}

.week-legend-label {
  color: #909399;
}

.week-legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-family: Consolas, Monaco, 'Courier New', monospace;
}

/* 图例周色块：复用格子底色变量（顺序必须与格子一致） */
.legend-swatch.wk0 { background: var(--wk0); border: 1px solid #9ec5f0; }
.legend-swatch.wk1 { background: var(--wk1); border: 1px solid #a3d69c; }
.legend-swatch.wk2 { background: var(--wk2); border: 1px solid #e6b877; }
.legend-swatch.wk3 { background: var(--wk3); border: 1px solid #bca9e8; }
.legend-swatch.wk4 { background: var(--wk4); border: 1px solid #eb9aab; }
.legend-swatch.wk5 { background: var(--wk5); border: 1px solid #8fc9c9; }

/* ===== vxe-table 内部样式覆盖（scoped 需 :deep） ===== */
/* 表格外框线：vxe 会额外画一条 .vxe-table--border-line（absolute 铺满整个表格容器，z-index:10）。
   🔴 它框的是「容器」而不是「内容」：表格 height="auto" 撑满 flex 容器时，行内容高度常小于容器高度，
   这条线就悬在内容下方几条像素处，看着像多出来的一条线（先前的"白线"就是它，默认色 #e8eaec）。
   → 直接去掉：表格轮廓由每个单元格自己的 1px 边框围成，位置必然与内容一致。 */
:deep(.matrix-table) .vxe-table--border-line {
  display: none;
}

/* 全表格：黑色实线边框（每个单元格 + 表头），边框独立避免塌陷。
   🔴 不写 .vxe-table--body-wrapper 这一层限定：固定列的单元格会被复制一份到
      .vxe-table--fixed-left/right-wrapper（绝对定位覆盖在上层，自带不透明背景），
      只给 body-wrapper 加边框的话，固定列这一份就没有边框、还会遮住底下那条 → 左右两侧看不到边线。 */
:deep(.matrix-table) .vxe-header--column,
:deep(.matrix-table) .vxe-body--column {
  border: 1px solid #303133;
}

/* 合并单元格保持外边框完整（合并后单元格不再重复画内线） */
:deep(.matrix-table) .vxe-table--body .vxe-body--row .vxe-body--column {
  border-bottom: 1px solid #303133;
}

/* 有值格子：深蓝粗体数字 + 所属自然周的浅底色
   （!important 压过 vxe 默认单元格底色；色板定义在 .schedule-page 的 --wk0~--wk5） */
:deep(td.cell-filled) {
  font-weight: 700;
  color: #1b4f8a;
}

:deep(td.cell-filled.wk0) { background-color: var(--wk0) !important; }
:deep(td.cell-filled.wk1) { background-color: var(--wk1) !important; }
:deep(td.cell-filled.wk2) { background-color: var(--wk2) !important; }
:deep(td.cell-filled.wk3) { background-color: var(--wk3) !important; }
:deep(td.cell-filled.wk4) { background-color: var(--wk4) !important; }
:deep(td.cell-filled.wk5) { background-color: var(--wk5) !important; }

/* 非可排班日格子：斜纹底加深（底色与条纹再加深一档，与可排班白格明显区分；!important 压过 vxe 默认 background-image） */
:deep(td.vxe-body--column.cell-locked) {
  background-color: #cdd3dd !important;
  background-image: repeating-linear-gradient(
    45deg,
    transparent,
    transparent 3px,
    #aab4c4 3px,
    #aab4c4 6px
  ) !important;
  cursor: not-allowed;
}

/* 非可排班日格子的内部展示元素：同样禁止光标，悬浮提示不可填写 */
:deep(td.cell-locked) .cell-value,
:deep(td.cell-locked) .vxe-cell {
  cursor: not-allowed;
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

/* 表头整体：浅蓝底（加深），与正文区分 */
:deep(.matrix-table) .vxe-table--header-wrapper .vxe-header--column {
  background-color: #a0cfff;
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

/* 已归档需求的行：整行禁止光标（排期只读，所有交互入口均已禁用/隐藏） */
:deep(tr.row-archived) {
  cursor: not-allowed;
}

/* 表头单元格作为筛选三角的定位锚点：三角 absolute 贴到单元格右下角而非标题文字右下角 */
:deep(.matrix-table) .vxe-header--column .vxe-cell {
  position: relative;
}

:deep(tr.row-archived) .vxe-cell,
:deep(tr.row-archived) .vxe-cell .cell-value,
:deep(tr.row-archived) .vxe-cell .req-info,
:deep(tr.row-archived) .effort-input .el-input__inner {
  cursor: not-allowed;
}

/* 表格正文单元格：垂直居中（vxe-cell 默认顶对齐；水平居中由列 align="center" 的 text-align 实现，
   保持子元素默认拉伸以维持 .cell-text 的超长省略） */
:deep(.matrix-table) .vxe-table--body-wrapper .vxe-body--column .vxe-cell {
  display: flex;
  flex-direction: column;
  justify-content: center;
}
</style>

<style>
.col-filter-pop {
  position: fixed;
  z-index: 3000;
  width: 210px;
  padding: 8px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  box-sizing: border-box;
}

.col-filter-pop .cf-actions {
  display: flex;
  gap: 2px;
  margin-bottom: 4px;
}

.col-filter-pop .cf-list {
  display: flex;
  flex-direction: column;
  max-height: 240px;
  overflow: auto;
  border-top: 1px solid #ebeef5;
  border-bottom: 1px solid #ebeef5;
  padding: 2px 0;
}

.col-filter-pop .cf-list .el-checkbox {
  height: 26px;
  margin-right: 0;
  padding: 0 4px;
}

.col-filter-pop .cf-list .el-checkbox:hover {
  background: #f5f7fa;
}

.col-filter-pop .cf-label {
  display: inline-block;
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.col-filter-pop .cf-count {
  margin-left: 6px;
  color: #909399;
  font-size: 12px;
}

.col-filter-pop .cf-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}
</style>
<style>
.col-filter-pop .cf-actions {
  align-items: center;
}

.col-filter-pop .cf-select-all {
  height: auto;
  margin-right: 0;
}

.col-filter-pop .cf-select-all .el-checkbox__label {
  font-size: 12px;
  color: #606266;
}
</style>
<style>
.col-filter-pop .cf-list .el-checkbox__label,
.col-filter-pop .cf-select-all .el-checkbox__label {
  color: #303133;
}

.col-filter-pop .cf-list .el-checkbox:hover .el-checkbox__label,
.col-filter-pop .cf-list .el-checkbox.is-checked .el-checkbox__label,
.col-filter-pop .cf-list .el-checkbox.is-checked:hover .el-checkbox__label {
  color: #303133;
}

.col-filter-pop .cf-select-all.is-checked .el-checkbox__label {
  color: #1976d2;
  font-weight: 600;
}

.col-filter-pop .el-checkbox__input.is-checked .el-checkbox__inner,
.col-filter-pop .el-checkbox__input.is-indeterminate .el-checkbox__inner {
  background-color: #1976d2;
  border-color: #1976d2;
}

.col-filter-pop .el-checkbox__inner:hover {
  border-color: #1976d2;
}
</style>