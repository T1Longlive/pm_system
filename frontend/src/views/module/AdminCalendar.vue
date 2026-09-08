<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getCalendar,
  updateCalendarDay,
  type CalendarDayData,
  type CalendarDayType,
  type CalendarMonthData
} from '@/api/calendar'

// 当前年月（yyyy-MM，el-date-picker month 类型格式）
const yearMonth = ref('')
// 月历数据
const calendar = ref<CalendarMonthData | null>(null)
// 月历加载中
const loading = ref(false)

// 日类型选项（4 选 1）
const DAY_TYPE_OPTIONS: { value: CalendarDayType; label: string; desc: string }[] = [
  { value: 'WORKDAY', label: '工作日', desc: '正常上班，排期可填' },
  { value: 'WEEKEND', label: '周末', desc: '非工作日，排期不可填' },
  { value: 'HOLIDAY', label: '节假日', desc: '法定节假日，可填名称' },
  { value: 'ADJUSTED', label: '调休上班', desc: '周末调整为工作日' }
]

// 星期表头：周一起始
const WEEK_HEADERS = ['一', '二', '三', '四', '五', '六', '日']

// 月历网格前置空位数（首日星期偏移，周一为 0）
const leadingBlanks = computed(() => {
  const days = calendar.value?.days || []
  if (!days.length) return 0
  const [y, m] = days[0].day.split('-').map(Number)
  return (new Date(y, m - 1, 1).getDay() + 6) % 7
})

// 拉取当前年月日历
const fetchCalendar = async () => {
  if (!yearMonth.value) return
  const [year, month] = yearMonth.value.split('-').map(Number)
  loading.value = true
  try {
    const res = await getCalendar(year, month)
    calendar.value = res.data.data
  } catch {
    calendar.value = null
  } finally {
    loading.value = false
  }
}

// 年月切换
const handleMonthChange = () => {
  fetchCalendar()
}

// ===== 单日编辑弹窗 =====
// 编辑表单：日期 + 类型 + 节假日名称
const editForm = reactive<{ visible: boolean; day: string; type: CalendarDayType; name: string }>({
  visible: false,
  day: '',
  type: 'WORKDAY',
  name: ''
})
// 提交按钮 loading
const submitting = ref(false)

// 点击日期格：带入当前类型与名称
const openEdit = (d: CalendarDayData) => {
  editForm.day = d.day
  editForm.type = d.type
  editForm.name = d.name || ''
  editForm.visible = true
}

// 所选类型是否节假日（需填名称）
const isHolidayType = computed(() => editForm.type === 'HOLIDAY')

// 提交：PUT 更新单日类型，成功后刷新月历
const handleSubmit = async () => {
  submitting.value = true
  try {
    await updateCalendarDay(editForm.day, {
      type: editForm.type,
      // 仅节假日携带名称
      name: editForm.type === 'HOLIDAY' && editForm.name.trim() ? editForm.name.trim() : undefined
    })
    ElMessage.success(`${editForm.day} 日历已更新`)
    editForm.visible = false
    fetchCalendar()
  } catch {
    // 失败已由拦截器统一提示
  } finally {
    submitting.value = false
  }
}

// 页面挂载：默认当前月
onMounted(() => {
  const now = new Date()
  yearMonth.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  fetchCalendar()
})
</script>

<template>
  <div class="calendar-page">
    <!-- 工具栏：年月切换 + 说明 -->
    <div class="page-toolbar">
      <el-date-picker
        v-model="yearMonth"
        type="month"
        placeholder="选择月份"
        value-format="YYYY-MM"
        :clearable="false"
        @change="handleMonthChange"
      />
      <div class="toolbar-tip">排期表的可填格、跨月顺延、状态推进均以此日历为准</div>
    </div>

    <el-card shadow="never" class="calendar-card">
      <div v-loading="loading" class="calendar-body">
        <!-- 星期表头：周一起始 -->
        <div class="week-header">
          <div v-for="w in WEEK_HEADERS" :key="w" class="week-cell">周{{ w }}</div>
        </div>
        <!-- 月历网格：前置空位 + 每天一格 -->
        <div class="day-grid">
          <div v-for="i in leadingBlanks" :key="`blank-${i}`" class="day-blank" />
          <div
            v-for="d in calendar?.days || []"
            :key="d.day"
            class="day-cell"
            :class="'type-' + d.type.toLowerCase()"
            @click="openEdit(d)"
          >
            <span class="day-num">{{ Number(d.day.slice(8, 10)) }}</span>
            <!-- 类型标识：节假日红字显示名称，调休绿框标注 -->
            <span v-if="d.type === 'HOLIDAY'" class="day-name" :title="d.name || '节假日'">
              {{ d.name || '节假日' }}
            </span>
            <span v-else-if="d.type === 'ADJUSTED'" class="day-name">调休</span>
            <span v-else-if="d.type === 'WEEKEND'" class="day-name">休</span>
            <span v-else class="day-name">&nbsp;</span>
          </div>
        </div>
        <!-- 图例 -->
        <div class="legend">
          <span class="legend-item"><i class="swatch workday" />工作日</span>
          <span class="legend-item"><i class="swatch weekend" />周末（不可排）</span>
          <span class="legend-item"><i class="swatch holiday" />节假日（不可排）</span>
          <span class="legend-item"><i class="swatch adjusted" />调休上班（可排）</span>
        </div>
      </div>
    </el-card>

    <!-- 单日编辑弹窗 -->
    <el-dialog v-model="editForm.visible" :title="`编辑日历 · ${editForm.day}`" width="420px">
      <el-form label-width="80px">
        <el-form-item label="日类型">
          <el-radio-group v-model="editForm.type">
            <el-radio v-for="t in DAY_TYPE_OPTIONS" :key="t.value" :value="t.value">
              {{ t.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="isHolidayType" label="节假日名称">
          <el-input v-model="editForm.name" placeholder="如：国庆节" maxlength="20" />
        </el-form-item>
        <el-form-item label=" " label-width="80px">
          <span class="type-desc">
            {{ DAY_TYPE_OPTIONS.find((t) => t.value === editForm.type)?.desc }}
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editForm.visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页面：纵向排布 */
.calendar-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 工具栏：年月选择 + 说明文案 */
.page-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 16px;
  background: #fff;
  border-radius: 4px;
}

/* 说明文案 */
.toolbar-tip {
  font-size: 13px;
  color: #909399;
}

/* 日历卡片 */
.calendar-card {
  max-width: 980px;
}

.calendar-body {
  min-height: 360px;
}

/* 星期表头：7 列（与日期网格同 gap 对齐） */
.week-header {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
  margin-bottom: 8px;
}

.week-cell {
  padding: 6px 0;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  text-align: center;
}

/* 月历网格：7 列 */
.day-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
}

/* 前置空位占位 */
.day-blank {
  min-height: 72px;
}

/* 日期格：白底卡片，可点击编辑 */
.day-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 72px;
  padding: 8px;
  font-size: 13px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.day-cell:hover {
  border-color: #409eff;
}

/* 周末：灰斜纹 */
.day-cell.type-weekend {
  background-image: repeating-linear-gradient(
    45deg,
    #fafafa,
    #fafafa 5px,
    #f2f3f5 5px,
    #f2f3f5 10px
  );
  color: #909399;
}

/* 节假日：红字 */
.day-cell.type-holiday {
  color: #f56c6c;
  border-color: #fbc4c4;
}

/* 调休上班：绿边框 */
.day-cell.type-adjusted {
  border: 1px solid #67c23a;
  color: #67c23a;
}

/* 日期数字 */
.day-num {
  font-size: 14px;
  font-weight: 600;
}

/* 类型标识文字：超长省略 */
.day-name {
  overflow: hidden;
  font-size: 12px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 图例 */
.legend {
  display: flex;
  gap: 20px;
  margin-top: 14px;
  font-size: 12px;
  color: #909399;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 5px;
}

/* 图例色块 */
.swatch {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 1px solid #e4e7ed;
  border-radius: 3px;
}

.swatch.workday {
  background: #fff;
}

.swatch.weekend {
  background-image: repeating-linear-gradient(
    45deg,
    #fafafa,
    #fafafa 4px,
    #f2f3f5 4px,
    #f2f3f5 8px
  );
}

.swatch.holiday {
  background: #fef0f0;
  border-color: #f56c6c;
}

.swatch.adjusted {
  background: #f0f9eb;
  border-color: #67c23a;
}

/* 类型说明文字 */
.type-desc {
  font-size: 12px;
  color: #909399;
}
</style>
