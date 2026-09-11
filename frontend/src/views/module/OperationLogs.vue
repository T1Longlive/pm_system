<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listProjects, type ProjectItem } from '@/api/project'
import {
  pageOperationLogs,
  listLogOperators,
  type OperationLogItem,
  type OperatorOption
} from '@/api/operationLogs'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const isAdmin = userStore.userInfo?.isAdmin === 1

// ===== 筛选条件（空串=全部） =====
const query = reactive({
  bizType: '' as '' | string,
  action: '' as '' | string,
  operatorId: '' as '' | number,
  projectId: '' as '' | number,
  keyword: '',
  dateRange: [] as [string, string] | []
})
// 分页参数
const page = ref(1)
const size = ref(20)
const total = ref(0)
// 日志列表与加载状态
const list = ref<OperationLogItem[]>([])
const loading = ref(false)

// 项目下拉（管理员=全部可见项目；项目经理=仅自己担任 PM 的项目，与后端可见范围一致）
const projects = ref<ProjectItem[]>([])
// 操作人下拉（日志中出现过的操作人去重）
const operators = ref<OperatorOption[]>([])

// 业务类型映射
const BIZ_TYPE_OPTIONS = [
  { value: 'REQUIREMENT', label: '需求' },
  { value: 'BUG', label: 'Bug' },
  { value: 'PROJECT', label: '项目' }
]

// 动作码 -> 中文名/标签色（覆盖全部留痕动作）
const ACTION_MAP: Record<string, { label: string; tag: 'success' | 'primary' | 'danger' | 'warning' | 'info' }> = {
  CREATE: { label: '创建', tag: 'success' },
  UPDATE: { label: '编辑', tag: 'primary' },
  DELETE: { label: '删除', tag: 'danger' },
  CANCEL: { label: '取消', tag: 'info' },
  ARCHIVE: { label: '归档', tag: 'warning' },
  RESTORE: { label: '重新打开', tag: 'success' },
  ADD_PARTICIPANT: { label: '添加参与人', tag: 'primary' },
  REMOVE_PARTICIPANT: { label: '移除参与人', tag: 'danger' },
  SCHEDULE_ADD: { label: '加入排期', tag: 'primary' },
  SCHEDULE_SAVE: { label: '保存排期', tag: 'primary' },
  SCHEDULE_REMOVE: { label: '移除排期', tag: 'danger' },
  SCHEDULE_COPY: { label: '复制到下月', tag: 'primary' },
  ADD_MEMBER: { label: '添加成员', tag: 'success' },
  REMOVE_MEMBER: { label: '移除成员', tag: 'danger' },
  ADD_IMAGE: { label: '上传截图', tag: 'info' },
  DELETE_IMAGE: { label: '删除截图', tag: 'danger' },
  HANDLE_DONE: { label: '处理完成', tag: 'success' },
  ACCEPT_PASS: { label: '验收通过', tag: 'success' },
  ACCEPT_REJECT: { label: '验收不通过', tag: 'danger' },
  NODE_DONE: { label: '节点完成', tag: 'success' }
}

const bizTypeLabel = (v: string) => BIZ_TYPE_OPTIONS.find((o) => o.value === v)?.label || v
const bizTypeTag = (v: string) =>
  v === 'REQUIREMENT' ? 'primary' : v === 'BUG' ? 'danger' : 'success'
const actionLabel = (v: string) => ACTION_MAP[v]?.label || v
const actionTag = (v: string) => ACTION_MAP[v]?.tag || 'info'

// 拉取项目下拉：项目经理仅显示自己创建（担任 PM）的项目，与后端日志可见范围一致
const fetchProjects = async () => {
  try {
    const res = await listProjects({ page: 1, size: 500 })
    const records = res.data.data.records
    projects.value = isAdmin ? records : records.filter((p) => p.myRole.includes('PM'))
  } catch {
    // 失败已由拦截器统一提示，下拉留空不影响查询
  }
}

// 拉取操作人下拉
const fetchOperators = async () => {
  try {
    const res = await listLogOperators()
    operators.value = res.data.data || []
  } catch {
    // 静默：下拉留空
  }
}

// 查询日志（条件为空不传对应参数）
const fetchList = async () => {
  loading.value = true
  try {
    const params: Record<string, unknown> = { page: page.value, size: size.value }
    if (query.bizType) params.bizType = query.bizType
    if (query.action) params.action = query.action
    if (query.operatorId !== '') params.operatorId = query.operatorId
    if (query.projectId !== '') params.projectId = query.projectId
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    if (query.dateRange.length === 2) {
      params.startDate = query.dateRange[0]
      params.endDate = query.dateRange[1]
    }
    const res = await pageOperationLogs(params as never)
    list.value = res.data.data.records
    total.value = res.data.data.total
  } finally {
    loading.value = false
  }
}

// 查询（按钮/清空）：回到第一页
const handleSearch = () => {
  page.value = 1
  fetchList()
}

const handleReset = () => {
  query.bizType = ''
  query.action = ''
  query.operatorId = ''
  query.projectId = ''
  query.keyword = ''
  query.dateRange = []
  handleSearch()
}

const handleSizeChange = () => {
  page.value = 1
  fetchList()
}

const handleCurrentChange = () => {
  fetchList()
}

// 时间格式化（后端 LocalDateTime 数组或字符串，统一显示 YYYY-MM-DD HH:mm）
const formatTime = (v: string) => (v ? v.replace('T', ' ').slice(0, 19) : '')

onMounted(async () => {
  fetchProjects()
  fetchOperators()
  await fetchList()
})
</script>

<template>
  <div class="logs-page">
    <!-- 筛选区 -->
    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <el-select v-model="query.bizType" placeholder="业务类型" clearable class="f-item">
          <el-option v-for="o in BIZ_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="query.action" placeholder="动作" clearable filterable class="f-item">
          <el-option
            v-for="(cfg, code) in ACTION_MAP"
            :key="code"
            :label="cfg.label"
            :value="code"
          />
        </el-select>
        <el-select v-model="query.operatorId" placeholder="操作人" clearable filterable class="f-item">
          <el-option v-for="o in operators" :key="o.id" :label="o.realName" :value="o.id" />
        </el-select>
        <el-select v-model="query.projectId" placeholder="项目" clearable filterable class="f-item">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-date-picker
          v-model="query.dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          clearable
          class="f-date"
        />
        <el-input
          v-model="query.keyword"
          placeholder="备注关键词"
          clearable
          class="f-keyword"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>
    </el-card>

    <!-- 日志表 -->
    <el-card shadow="never">
      <el-table :data="list" v-loading="loading" border stripe>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" prop="operatorName" width="110" />
        <el-table-column label="动作" width="120">
          <template #default="{ row }">
            <el-tag :type="actionTag(row.action)" size="small">{{ actionLabel(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="业务类型" width="100">
          <template #default="{ row }">
            <el-tag :type="bizTypeTag(row.bizType)" size="small" effect="plain">
              {{ bizTypeLabel(row.bizType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="业务对象" min-width="220">
          <template #default="{ row }">
            <span v-if="row.bizNo" class="biz-no">{{ row.bizNo }}</span>
            <span v-if="row.bizName" class="biz-name">{{ row.bizName }}</span>
            <span v-if="!row.bizNo && !row.bizName" class="biz-deleted">（原对象已删除）</span>
          </template>
        </el-table-column>
        <el-table-column label="所属项目" prop="projectName" min-width="140" />
        <el-table-column label="备注" prop="remark" min-width="280" show-overflow-tooltip />
      </el-table>
      <el-pagination
        class="pager"
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        :page-sizes="[20, 50, 100]"
        v-model:current-page="page"
        v-model:page-size="size"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </el-card>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 12px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.f-item {
  width: 140px;
}

.f-date {
  width: 240px;
}

.f-keyword {
  width: 180px;
}

.biz-no {
  font-weight: 600;
  margin-right: 8px;
  color: #1b4f8a;
}

.biz-name {
  color: #303133;
}

.biz-deleted {
  color: #909399;
  font-style: italic;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
