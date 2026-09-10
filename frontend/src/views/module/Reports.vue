<script setup lang="ts">
// 统计报表模块（全员可见）
// 图表选择：环形图（状态占比）+ 纵向堆叠柱（项目×状态）+ 热力图（人员×月负载）+ 数据条表格（项目概览）
// 热力图避免堆叠柱挤压；数据条表格避免不同量级指标共用一轴的失真
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, PieChart, HeatmapChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
  TitleComponent,
  VisualMapComponent,
  DataZoomComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  getProjectOverview,
  getRequirementStatus,
  getWorkload,
  type ReportProjectOverview,
  type ReportRequirementStatus,
  type ReportWorkload
} from '@/api/reports'
import { STATUS_OPTIONS } from '@/constants/requirement'

echarts.use([
  BarChart,
  PieChart,
  HeatmapChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  TitleComponent,
  VisualMapComponent,
  DataZoomComponent,
  CanvasRenderer
])

// ===== 状态常量与配色 =====
const STATUS_ORDER = ['PENDING', 'DEV', 'TEST', 'ARCHIVING', 'ARCHIVED', 'CANCELLED'] as const
const STATUS_LABEL: Record<string, string> = Object.fromEntries(
  STATUS_OPTIONS.map((s) => [s.value, s.label])
)
// 图表配色：每种状态用独立可区分的颜色（ARCHIVING 用深金区别于 DEV 橙）
const STATUS_COLOR: Record<string, string> = {
  PENDING: '#909399',
  DEV: '#e6a23c',
  TEST: '#409eff',
  ARCHIVING: '#b88230',
  ARCHIVED: '#67c23a',
  CANCELLED: '#f56c6c'
}

// ===== 数据 =====
const statusRows = ref<ReportRequirementStatus[]>([])
const workloadRows = ref<ReportWorkload[]>([])
const overviewRows = ref<ReportProjectOverview[]>([])
const workloadMonths = ref(6)
const loading = ref(false)

// ===== 图表实例与容器 =====
const pieEl = ref<HTMLDivElement>()
const stackEl = ref<HTMLDivElement>()
const workloadEl = ref<HTMLDivElement>()
let pieChart: echarts.ECharts | null = null
let stackChart: echarts.ECharts | null = null
let workloadChart: echarts.ECharts | null = null

// ===== 数据加工（computed） =====

// KPI 汇总
const statusTotal = computed(() =>
  statusRows.value.reduce((sum, r) => sum + r.count, 0)
)
const activeReqTotal = computed(() =>
  statusRows.value
    .filter((r) => !['ARCHIVED', 'CANCELLED'].includes(r.status))
    .reduce((sum, r) => sum + r.count, 0)
)
const openBugTotal = computed(() =>
  overviewRows.value.reduce((sum, r) => sum + r.openBugCount, 0)
)
const projectTotal = computed(() => overviewRows.value.length)

const kpiCards = computed(() => [
  { label: '需求总数', value: statusTotal.value },
  { label: '进行中需求', value: activeReqTotal.value },
  { label: '项目数', value: projectTotal.value },
  { label: '未归档 Bug', value: openBugTotal.value }
])

// 环形图数据：仅含非零状态
const pieData = computed(() =>
  STATUS_ORDER.map((status) => ({
    name: STATUS_LABEL[status],
    value: statusRows.value
      .filter((r) => r.status === status)
      .reduce((sum, r) => sum + r.count, 0),
    itemStyle: { color: STATUS_COLOR[status] }
  })).filter((d) => d.value > 0)
)

// 项目列表：按需求总数降序排列（大项目在左）
const sortedProjects = computed(() => {
  const map = new Map<string, number>()
  statusRows.value.forEach((r) => {
    map.set(r.projectName, (map.get(r.projectName) || 0) + r.count)
  })
  return [...map.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([name]) => name)
})

// 非零状态列表（仅含实际有数据的状态，避免空 series）
const activeStatuses = computed(() =>
  STATUS_ORDER.filter((status) =>
    statusRows.value.some((r) => r.status === status && r.count > 0)
  )
)

// 人员负载月份序列
const monthLabels = computed(() => {
  const labels: string[] = []
  const now = new Date()
  for (let i = workloadMonths.value - 1; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    labels.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
  }
  return labels
})

// 负载人员序列（按姓名排序）
const workloadUsers = computed(() => {
  const map = new Map<number, string>()
  workloadRows.value.forEach((r) => map.set(r.userId, r.realName))
  return [...map.entries()]
    .map(([id, name]) => ({ id, name }))
    .sort((a, b) => a.name.localeCompare(b.name, 'zh'))
})

// 热力图数据：[月份索引, 人员索引, 工时]
const heatmapData = computed(() => {
  const users = workloadUsers.value
  const labels = monthLabels.value
  const result: [number, number, number][] = []
  users.forEach((u, yIdx) => {
    labels.forEach((label, xIdx) => {
      const [year, month] = label.split('-').map(Number)
      const effort = workloadRows.value.find(
        (r) => r.userId === u.id && r.year === year && r.month === month
      )?.effort
      const val = Number(effort) || 0
      // 只渲染非零格子，零格子留白更干净
      if (val > 0) result.push([xIdx, yIdx, val])
    })
  })
  return result
})

// 热力图最大工时（visualMap 上限）
const maxEffort = computed(() => {
  const max = Math.max(0, ...heatmapData.value.map((d) => d[2]))
  return Math.max(1, max)
})

// 热力图高度：按人数动态计算
const workloadChartHeight = computed(() => {
  const users = workloadUsers.value.length
  // 每人 34px 行高 + 头部 40 + 底部 visualMap 60 + 内边距
  return Math.max(240, users * 34 + 100) + 'px'
})

// 堆叠柱高度：按项目数动态计算
const stackChartHeight = computed(() => {
  const count = sortedProjects.value.length
  // 纵向柱状图：每个项目至少 40px 柱宽 + 图例 + 标签
  // 如果项目少，用固定高度；项目多则加高
  return Math.max(300, count * 48 + 100) + 'px'
})

// 项目概览：各指标最大值（用于数据条比例）
const maxMembers = computed(() =>
  Math.max(1, ...overviewRows.value.map((r) => r.memberCount))
)
const maxReqs = computed(() =>
  Math.max(1, ...overviewRows.value.map((r) => r.requirementCount))
)
const maxBugs = computed(() =>
  Math.max(1, ...overviewRows.value.map((r) => r.openBugCount))
)

function barPercent(val: number, max: number): string {
  return Math.min(100, (val / max) * 100) + '%'
}

// ===== 图表渲染 =====

function renderPieChart() {
  if (!pieChart) return
  // 环形独占容器：不画外侧标签和内置图例（信息由下方 HTML 图例列表承载），环体放到最大且绝对居中
  pieChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: '{b}：{c}（{d}%）'
    },
    title: {
      text: String(statusTotal.value),
      subtext: '需求总数',
      left: 'center',
      top: 'middle',
      itemGap: 4,
      textAlign: 'center',
      textStyle: { fontSize: 30, color: '#303133', fontWeight: 700, lineHeight: 34 },
      subtextStyle: { fontSize: 12, color: '#909399', lineHeight: 18 }
    },
    series: [
      {
        type: 'pie',
        radius: ['62%', '85%'],
        center: ['50%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: {
          borderColor: '#fff',
          borderWidth: 2
        },
        label: { show: false },
        labelLine: { show: false },
        emphasis: {
          scale: true,
          scaleSize: 4,
          label: { show: false }
        },
        data: pieData.value
      }
    ]
  })
}

// 占比文本（自定义图例用）
function pctText(value: number): string {
  if (!statusTotal.value) return '0%'
  return Math.round((value / statusTotal.value) * 100) + '%'
}

function renderStackChart() {
  if (!stackChart) return
  const projects = sortedProjects.value
  const many = projects.length > 6

  stackChart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' }
    },
    legend: {
      bottom: 4,
      icon: 'circle',
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 12,
      textStyle: { fontSize: 12, color: '#606266' }
    },
    grid: {
      left: 48,
      right: 24,
      top: 20,
      bottom: many ? 72 : 48
    },
    xAxis: {
      type: 'category',
      data: projects,
      axisLabel: {
        fontSize: 11,
        color: '#606266',
        interval: 0,
        rotate: many ? 25 : 0,
        width: 80,
        overflow: 'truncate',
        ellipsis: '...'
      }
    },
    yAxis: { type: 'value', minInterval: 1 },
    dataZoom: many
      ? [
          { type: 'inside', xAxisIndex: 0, start: 0, end: 60 },
          { type: 'slider', xAxisIndex: 0, height: 16, bottom: 40, start: 0, end: 60 }
        ]
      : [],
    series: activeStatuses.value.map((status) => ({
      name: STATUS_LABEL[status],
      type: 'bar',
      stack: 'total',
      itemStyle: { color: STATUS_COLOR[status] },
      emphasis: { focus: 'series' },
      barWidth: '60%',
      data: projects.map(
        (name) =>
          statusRows.value.find(
            (r) => r.projectName === name && r.status === status
          )?.count || 0
      )
    }))
  })
}

function renderWorkloadChart() {
  if (!workloadChart) return
  const users = workloadUsers.value
  const labels = monthLabels.value

  workloadChart.setOption({
    tooltip: {
      formatter: (params: { value: [number, number, number] }) => {
        const user = users[params.value[1]]?.name || ''
        const month = labels[params.value[0]] || ''
        return `${user}<br/>${month}<br/>工时：${params.value[2]} 人日`
      }
    },
    grid: {
      left: 90,
      right: 30,
      top: 16,
      bottom: 70
    },
    xAxis: {
      type: 'category',
      data: labels,
      splitArea: { show: true },
      axisLabel: { fontSize: 11, color: '#606266' }
    },
    yAxis: {
      type: 'category',
      data: users.map((u) => u.name),
      inverse: true,
      splitArea: { show: true },
      axisLabel: { fontSize: 12, color: '#606266' }
    },
    visualMap: {
      min: 0,
      max: maxEffort.value,
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 5,
      itemWidth: 14,
      itemHeight: 120,
      textStyle: { fontSize: 11, color: '#606266' },
      inRange: {
        color: ['#e8eef5', '#a0cfff', '#409eff', '#1b4f8a', '#003366']
      }
    },
    series: [
      {
        type: 'heatmap',
        data: heatmapData.value,
        label: {
          show: true,
          fontSize: 11,
          color: '#fff'
        },
        itemStyle: {
          borderColor: '#fff',
          borderWidth: 1
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 8,
            shadowColor: 'rgba(0,0,0,0.3)'
          }
        }
      }
    ]
  })
}

function renderCharts() {
  renderPieChart()
  renderStackChart()
  renderWorkloadChart()
}

function initCharts() {
  if (pieEl.value && !pieChart) pieChart = echarts.init(pieEl.value)
  if (stackEl.value && !stackChart) stackChart = echarts.init(stackEl.value)
  if (workloadEl.value && !workloadChart) workloadChart = echarts.init(workloadEl.value)
}

// ===== 数据加载 =====
async function loadAll() {
  loading.value = true
  const [s, w, o] = await Promise.allSettled([
    getRequirementStatus(),
    getWorkload(workloadMonths.value),
    getProjectOverview()
  ])
  if (s.status === 'fulfilled') statusRows.value = s.value.data.data || []
  else ElMessage.error('需求状态分布加载失败')
  if (w.status === 'fulfilled') workloadRows.value = w.value.data.data || []
  else ElMessage.error('人员负载加载失败')
  if (o.status === 'fulfilled') overviewRows.value = o.value.data.data || []
  else ElMessage.error('项目概览加载失败')
  loading.value = false
  // 必须等 v-show 容器恢复显示（有真实宽高）后再初始化 ECharts，
  // 否则在 display:none 上 init 会量到 0×0，图表全部挤在左上角
  await nextTick()
  initCharts()
  renderCharts()
}

// 切换负载月份数
watch(workloadMonths, async () => {
  try {
    const res = await getWorkload(workloadMonths.value)
    workloadRows.value = res.data.data || []
    // 人数变化时需要重建实例以适配新高度；同样要等 DOM 更新后再 init
    if (workloadChart) {
      workloadChart.dispose()
      workloadChart = null
    }
    await nextTick()
    initCharts()
    renderWorkloadChart()
  } catch {
    ElMessage.error('人员负载加载失败')
  }
})

function handleResize() {
  pieChart?.resize()
  stackChart?.resize()
  workloadChart?.resize()
}

onMounted(async () => {
  await loadAll()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  pieChart?.dispose()
  stackChart?.dispose()
  workloadChart?.dispose()
  pieChart = stackChart = workloadChart = null
})
</script>

<template>
  <div class="reports-page" v-loading="loading">
    <!-- 顶部 KPI 汇总卡片 -->
    <el-row :gutter="16">
      <el-col v-for="card in kpiCards" :key="card.label" :xs="12" :sm="12" :md="6">
        <el-card class="kpi-card" shadow="never">
          <div class="kpi-body">
            <div class="kpi-value">{{ card.value }}</div>
            <div class="kpi-label">{{ card.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 上排：全局状态分布（环形）+ 项目×状态（纵向堆叠柱） -->
    <el-row :gutter="16" class="mt16">
      <el-col :xs="24" :sm="24" :md="8">
        <el-card shadow="never" class="chart-card">
          <template #header><span class="card-title">需求状态分布</span></template>
          <div v-show="pieData.length > 0" class="pie-wrap">
            <div ref="pieEl" class="chart chart-pie" />
            <ul class="pie-legend">
              <li v-for="d in pieData" :key="d.name" class="pie-legend-item">
                <span class="pie-dot" :style="{ background: d.itemStyle.color }" />
                <span class="pie-name">{{ d.name }}</span>
                <span class="pie-count">{{ d.value }}</span>
                <span class="pie-pct">{{ pctText(d.value) }}</span>
              </li>
            </ul>
          </div>
          <el-empty v-show="pieData.length === 0" description="暂无需求数据" :image-size="60" />
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="24" :md="16">
        <el-card shadow="never" class="chart-card">
          <template #header><span class="card-title">项目需求状态分布</span></template>
          <div
            v-show="sortedProjects.length > 0"
            ref="stackEl"
            class="chart"
            :style="{ height: stackChartHeight }"
          />
          <el-empty v-show="sortedProjects.length === 0" description="暂无需求数据" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 中排：人员负载（热力图：人员×月份，颜色深浅=工时） -->
    <el-row :gutter="16" class="mt16">
      <el-col :span="24">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-header">
              <span class="card-title">人员负载热力图（排期工时，人日）</span>
              <el-select v-model="workloadMonths" style="width: 140px" size="small">
                <el-option label="最近 3 个月" :value="3" />
                <el-option label="最近 6 个月" :value="6" />
                <el-option label="最近 12 个月" :value="12" />
              </el-select>
            </div>
          </template>
          <div
            v-show="workloadUsers.length > 0"
            ref="workloadEl"
            class="chart"
            :style="{ height: workloadChartHeight }"
          />
          <el-empty
            v-show="workloadUsers.length === 0"
            description="区间内暂无排期工时数据"
            :image-size="60"
          />
        </el-card>
      </el-col>
    </el-row>

    <!-- 下排：项目概览（数据条表格，每指标独立比例条避免量级失真） -->
    <el-row :gutter="16" class="mt16">
      <el-col :span="24">
        <el-card shadow="never" class="chart-card">
          <template #header><span class="card-title">项目概览</span></template>
          <el-table
            v-show="overviewRows.length > 0"
            :data="overviewRows"
            class="overview-table"
            :header-cell-style="{ background: '#a0cfff', color: '#000', fontWeight: 700 }"
            :default-sort="{ prop: 'requirementCount', order: 'descending' }"
          >
            <el-table-column prop="projectName" label="项目名称" min-width="160" show-overflow-tooltip />
            <el-table-column label="成员数" min-width="180" sortable :sort-by="'memberCount'">
              <template #default="{ row }">
                <div class="bar-cell">
                  <span class="bar-num">{{ row.memberCount }}</span>
                  <div class="bar-track">
                    <div
                      class="bar-fill bar-fill-blue"
                      :style="{ width: barPercent(row.memberCount, maxMembers) }"
                    />
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="进行中需求" min-width="180" sortable :sort-by="'requirementCount'">
              <template #default="{ row }">
                <div class="bar-cell">
                  <span class="bar-num">{{ row.requirementCount }}</span>
                  <div class="bar-track">
                    <div
                      class="bar-fill bar-fill-green"
                      :style="{ width: barPercent(row.requirementCount, maxReqs) }"
                    />
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="未归档 Bug" min-width="180" sortable :sort-by="'openBugCount'">
              <template #default="{ row }">
                <div class="bar-cell">
                  <span class="bar-num">{{ row.openBugCount }}</span>
                  <div class="bar-track">
                    <div
                      class="bar-fill bar-fill-red"
                      :style="{ width: barPercent(row.openBugCount, maxBugs) }"
                    />
                  </div>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-show="overviewRows.length === 0" description="暂无项目数据" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* ===== 页面纵向排布 ===== */
.reports-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mt16 {
  margin-top: 0;
}

/* ===== KPI 汇总卡片 ===== */
.kpi-card :deep(.el-card__body) {
  padding: 18px 20px;
}

.kpi-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kpi-value {
  font-size: 28px;
  font-weight: 700;
  color: #1b4f8a;
  line-height: 1.2;
}

.kpi-label {
  font-size: 13px;
  color: #909399;
}

/* ===== 卡片标题 ===== */
.card-title {
  font-weight: 600;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* ===== 图表卡片内边距 ===== */
.chart-card :deep(.el-card__body) {
  padding: 12px 16px 8px;
}

/* ===== 图表容器 ===== */
.chart {
  width: 100%;
}

.chart-pie {
  height: 260px;
}

/* ===== 环形图自定义图例（色点 + 名称 + 数量 + 占比） ===== */
.pie-wrap {
  display: flex;
  flex-direction: column;
}

.pie-legend {
  list-style: none;
  margin: 4px 0 8px;
  padding: 8px 4px 0;
  border-top: 1px dashed #e4e7ed;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 16px;
}

.pie-legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 2px 4px;
  border-radius: 4px;
}

.pie-legend-item:hover {
  background: #f5f7fa;
}

.pie-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.pie-name {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pie-count {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  flex-shrink: 0;
}

.pie-pct {
  width: 38px;
  text-align: right;
  font-size: 11px;
  color: #909399;
  flex-shrink: 0;
}

/* ===== 项目概览数据条表格 ===== */
.overview-table :deep(tbody tr) {
  height: 44px;
}

.bar-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bar-num {
  width: 34px;
  text-align: right;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  flex-shrink: 0;
}

.bar-track {
  flex: 1;
  min-width: 60px;
  height: 18px;
  background: #f0f2f5;
  border-radius: 3px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.4s ease;
}

.bar-fill-blue {
  background: linear-gradient(90deg, #a0cfff, #409eff);
}

.bar-fill-green {
  background: linear-gradient(90deg, #b3e19d, #67c23a);
}

.bar-fill-red {
  background: linear-gradient(90deg, #fab6b6, #f56c6c);
}
</style>
