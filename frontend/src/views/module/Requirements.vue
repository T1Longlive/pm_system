<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { listProjects, type ProjectItem } from '@/api/project'
import { listRequirements, type ReqListItem } from '@/api/requirement'
import {
  STATUS_OPTIONS,
  PRIORITY_OPTIONS,
  statusLabel,
  statusTagType,
  priorityLabel,
  priorityTagType
} from '@/constants/requirement'
import ProgressSteps from '@/components/ProgressSteps.vue'
import RequirementFormDialog from '@/components/RequirementFormDialog.vue'

const router = useRouter()
const userStore = useUserStore()

// ===== 页签：list = 需求列表（进行中）；archive = 归档表 =====
const activeTab = ref<'list' | 'archive'>('list')

// 项目下拉选项（同时作为新增需求弹窗的项目数据源）
const projects = ref<ProjectItem[]>([])

// 需求列表（进行中）查询条件与分页参数（projectId/status/priority 为空串表示全部）
const query = reactive({
  projectId: '' as '' | number,
  status: '' as '' | string,
  priority: '' as '' | 1 | 2 | 3,
  keyword: '',
  page: 1,
  size: 10
})
// 进行中列表数据
const list = ref<ReqListItem[]>([])
// 进行中总条数
const total = ref(0)
// 表格 loading
const loading = ref(false)

// 归档表数据与状态（归档表不做筛选，仅分页）
const archList = ref<ReqListItem[]>([])
const archTotal = ref(0)
const archLoading = ref(false)
const archQuery = reactive({
  page: 1,
  size: 10
})

// 当前用户是否可新增需求：任一项目中担任 PM 或策划，或全局管理员（可自建项目后担任 PM）
const canCreate = computed(() => {
  if (userStore.userInfo?.isAdmin === 1) return true
  return projects.value.some((p) => p.myRole.includes('PM') || p.myRole.includes('PLANNER'))
})

// 拉取项目下拉数据（一次拉取五百条，覆盖绝大多数场景）
const fetchProjects = async () => {
  try {
    const res = await listProjects({ page: 1, size: 500 })
    projects.value = res.data.data.records
  } catch {
    // 失败已由拦截器统一提示，项目下拉留空不影响列表查询
  }
}

// 查询需求列表（进行中：排除终态 ARCHIVED/CANCELLED；条件为空时不传对应参数）
const fetchList = async () => {
  loading.value = true
  try {
    const params: {
      page: number
      size: number
      excludeTerminal: boolean
      projectId?: number
      status?: string
      priority?: number
      keyword?: string
    } = {
      page: query.page,
      size: query.size,
      excludeTerminal: true
    }
    if (query.projectId !== '') params.projectId = query.projectId
    if (query.status !== '') params.status = query.status
    if (query.priority !== '') params.priority = query.priority
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    const res = await listRequirements(params)
    list.value = res.data.data.records
    total.value = res.data.data.total
  } finally {
    loading.value = false
  }
}

// 查询归档表（仅 ARCHIVED 状态）
const fetchArchive = async () => {
  archLoading.value = true
  try {
    const res = await listRequirements({
      status: 'ARCHIVED',
      page: archQuery.page,
      size: archQuery.size
    })
    archList.value = res.data.data.records
    archTotal.value = res.data.data.total
  } finally {
    archLoading.value = false
  }
}

// 页签切换：切入归档表时刷新（保证归档操作后数据最新）
const handleTabChange = (tab: string | number) => {
  if (tab === 'archive') {
    archQuery.page = 1
    fetchArchive()
  }
}

// 查询（回车 / 按钮 / 清空 / 切换筛选）：回到第一页查询
const handleSearch = () => {
  query.page = 1
  fetchList()
}

// 每页条数变化：回到第一页查询
const handleSizeChange = () => {
  query.page = 1
  fetchList()
}

// 页码变化：查询当前页
const handleCurrentChange = () => {
  fetchList()
}

// 归档表分页
const handleArchSizeChange = () => {
  archQuery.page = 1
  fetchArchive()
}

const handleArchCurrentChange = () => {
  fetchArchive()
}

// 点击需求名称进入详情
const goDetail = (id: number) => {
  router.push(`/requirements/${id}`)
}

// ===== 新增需求弹窗 =====
const createVisible = ref(false)

const openCreate = () => {
  createVisible.value = true
}

// 新增成功：回到第一页展示新需求
const handleCreated = () => {
  query.page = 1
  fetchList()
}

// 页面挂载后加载项目下拉与需求列表
onMounted(() => {
  fetchList()
  fetchProjects()
})
</script>

<template>
  <div class="requirements-page">
    <!-- 顶部：筛选行 + 新增需求按钮（PM/策划可见） -->
    <div class="page-toolbar">
      <el-select
        v-model="query.projectId"
        class="toolbar-project"
        placeholder="全部项目"
        clearable
        @change="handleSearch"
      >
        <el-option label="全部项目" value="" />
        <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <el-select
        v-model="query.status"
        class="toolbar-status"
        placeholder="全部状态"
        clearable
        @change="handleSearch"
      >
        <el-option label="全部状态" value="" />
        <el-option v-for="s in STATUS_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-select
        v-model="query.priority"
        class="toolbar-priority"
        placeholder="全部优先级"
        clearable
        @change="handleSearch"
      >
        <el-option label="全部优先级" value="" />
        <el-option v-for="p in PRIORITY_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
      </el-select>
      <el-input
        v-model="query.keyword"
        class="toolbar-search"
        placeholder="搜索需求编号 / 名称"
        clearable
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #append>
          <el-button @click="handleSearch">搜索</el-button>
        </template>
      </el-input>
      <el-button v-if="canCreate" type="primary" class="toolbar-add" @click="openCreate">
        新增需求
      </el-button>
    </div>

    <!-- 双页签：需求列表（进行中）/ 归档表 -->
    <el-card shadow="never" class="table-card">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <!-- Tab 1：需求列表（进行中，排除终态） -->
        <el-tab-pane label="需求列表" name="list">
          <el-table v-loading="loading" :data="list">
            <el-table-column label="进度" width="165">
              <template #default="{ row }">
                <ProgressSteps :status="row.status" :participants="row.participants" :craft-schedules="row.craftSchedules" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="需求编号" width="160">
              <template #default="{ row }">
                <span class="req-no">{{ row.reqNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="需求名称" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <el-button link type="primary" @click="goDetail(row.id)">{{ row.title }}</el-button>
              </template>
            </el-table-column>
            <el-table-column prop="projectName" label="所属项目" min-width="140" show-overflow-tooltip />
            <el-table-column label="优先级" width="80">
              <template #default="{ row }">
                <el-tag :type="priorityTagType(row.priority)">{{ priorityLabel(row.priority) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="策划" width="100">
              <template #default="{ row }">
                {{ row.plannerName || '—' }}
              </template>
            </el-table-column>
            <el-table-column label="参与人数" width="90" align="center">
              <template #default="{ row }">
                {{ row.participants.length }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="170" />
            <el-table-column label="操作" width="80" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="goDetail(row.id)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pagination-wrap">
            <el-pagination
              v-model:current-page="query.page"
              v-model:page-size="query.size"
              :total="total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next, jumper"
              @size-change="handleSizeChange"
              @current-change="handleCurrentChange"
            />
          </div>
        </el-tab-pane>

        <!-- Tab 2：归档表（已归档需求，只读展示） -->
        <el-tab-pane label="归档表" name="archive">
          <el-table v-loading="archLoading" :data="archList">
            <el-table-column label="进度" width="165">
              <template #default="{ row }">
                <ProgressSteps :status="row.status" :participants="row.participants" :craft-schedules="row.craftSchedules" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="需求编号" width="160">
              <template #default="{ row }">
                <span class="req-no">{{ row.reqNo }}</span>
              </template>
            </el-table-column>
            <el-table-column label="需求名称" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <el-button link type="primary" @click="goDetail(row.id)">{{ row.title }}</el-button>
              </template>
            </el-table-column>
            <el-table-column prop="projectName" label="所属项目" min-width="140" show-overflow-tooltip />
            <el-table-column label="优先级" width="80">
              <template #default="{ row }">
                <el-tag :type="priorityTagType(row.priority)">{{ priorityLabel(row.priority) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="策划" width="100">
              <template #default="{ row }">
                {{ row.plannerName || '—' }}
              </template>
            </el-table-column>
            <el-table-column label="参与人数" width="90" align="center">
              <template #default="{ row }">
                {{ row.participants.length }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="170" />
            <el-table-column label="操作" width="80" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="goDetail(row.id)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pagination-wrap">
            <el-pagination
              v-model:current-page="archQuery.page"
              v-model:page-size="archQuery.size"
              :total="archTotal"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next, jumper"
              @size-change="handleArchSizeChange"
              @current-change="handleArchCurrentChange"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 新增需求弹窗（项目在弹窗内先选） -->
    <RequirementFormDialog v-model="createVisible" :projects="projects" @success="handleCreated" />
  </div>
</template>

<style scoped>
/* 页面：纵向排布工具栏与表格卡片 */
.requirements-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 顶部工具栏：白底圆角，新增按钮靠右 */
.page-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #fff;
  border-radius: 4px;
}

/* 项目下拉宽度 */
.toolbar-project {
  width: 180px;
}

/* 状态下拉宽度 */
.toolbar-status {
  width: 130px;
}

/* 优先级下拉宽度 */
.toolbar-priority {
  width: 120px;
}

/* 搜索框宽度 */
.toolbar-search {
  width: 260px;
}

/* 新增按钮推到最右 */
.toolbar-add {
  margin-left: auto;
}

/* 需求编号：等宽字体 */
.req-no {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 13px;
  color: #606266;
}

/* 分页：右对齐 */
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
