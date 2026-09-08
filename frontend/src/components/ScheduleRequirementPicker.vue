<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listRequirements, type ReqListItem } from '@/api/requirement'
import { addRequirementToMonth } from '@/api/schedule'
import { priorityLabel, priorityTagType } from '@/constants/requirement'

const props = defineProps<{
  // 弹窗显隐（v-model）
  modelValue: boolean
  // 目标月表 id
  monthId: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  // 添加成功（父页面刷新视图）
  success: []
}>()

// 弹窗显隐双向绑定
const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

// 待排期需求列表
const list = ref<ReqListItem[]>([])
// 列表加载中
const loading = ref(false)
// 已选需求 id（单选）
const selectedId = ref<number | null>(null)
// 提交按钮 loading
const submitting = ref(false)

// 拉取待排期需求（一次拉取一百条，超出部分一期不考虑）
const fetchList = async () => {
  loading.value = true
  try {
    const res = await listRequirements({ status: 'PENDING', page: 1, size: 100 })
    list.value = res.data.data.records
  } catch {
    // 失败已由拦截器统一提示
  } finally {
    loading.value = false
  }
}

// 弹窗打开：拉取待排期需求并重置选择
watch(
  () => props.modelValue,
  (v) => {
    if (v) {
      selectedId.value = null
      fetchList()
    }
  }
)

// 点击行：单选（再次点击同一行取消选择）
const handleRowClick = (row: ReqListItem) => {
  selectedId.value = selectedId.value === row.id ? null : row.id
}

// 行选中样式：当前选中行高亮
const rowClassName = ({ row }: { row: ReqListItem }) =>
  row.id === selectedId.value ? 'picker-row-selected' : ''

// 确认添加：调接口生成排期行，成功后关闭并通知父页面
const handleSubmit = async () => {
  if (!selectedId.value || !props.monthId) return
  submitting.value = true
  try {
    const res = await addRequirementToMonth(props.monthId, {
      requirementId: selectedId.value
    })
    ElMessage.success(`已添加，生成 ${res.data.data} 行排期`)
    visible.value = false
    emit('success')
  } catch {
    // 失败已由拦截器统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="从需求池添加" width="760px" :close-on-click-modal="false">
    <!-- 待排期需求单选列表：行点击选择，再次点击取消 -->
    <el-table
      v-loading="loading"
      :data="list"
      max-height="380"
      :row-class-name="rowClassName"
      class="picker-table"
      @row-click="handleRowClick"
    >
      <el-table-column label="" width="46" align="center">
        <template #default="{ row }">
          <el-radio :model-value="selectedId" :value="row.id" />
        </template>
      </el-table-column>
      <el-table-column label="需求编号" width="150">
        <template #default="{ row }">
          <span class="req-no">{{ row.reqNo }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="需求名称" min-width="200" show-overflow-tooltip />
      <el-table-column prop="projectName" label="所属项目" width="140" show-overflow-tooltip />
      <el-table-column label="优先级" width="76" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="priorityTagType(row.priority)">
            {{ priorityLabel(row.priority) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="策划" width="90">
        <template #default="{ row }">
          {{ row.plannerName || '—' }}
        </template>
      </el-table-column>
      <el-table-column label="参与人数" width="80" align="center">
        <template #default="{ row }">
          {{ row.participants.length }}
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无待排期需求" :image-size="70" />
      </template>
    </el-table>
    <div class="picker-tip">按需求参与人（工种 × 负责人）自动生成排期行，添加后可在矩阵中逐日填写工作量</div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!selectedId" :loading="submitting" @click="handleSubmit">
        确定
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 需求编号：等宽字体 */
.req-no {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 13px;
  color: #606266;
}

/* 选中行高亮（配合行点击单选） */
:deep(.picker-table .picker-row-selected) {
  background-color: #ecf5ff;
}

:deep(.picker-table .picker-row-selected:hover > td) {
  background-color: #ecf5ff;
}

/* 单选 radio 隐藏默认文案，仅保留圆点 */
:deep(.el-radio__label) {
  display: none;
}

/* 表格行：手型提示可点选 */
:deep(.picker-table .el-table__row) {
  cursor: pointer;
}

/* 底部说明文案 */
.picker-tip {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}
</style>
