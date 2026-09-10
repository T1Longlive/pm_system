<script setup lang="ts">
// 工作台模块：个人视角汇总（统计卡片 / 我参与的需求 / 最近通知 / 快捷入口）
// 数据口径与后端 /dashboard 对齐：通知复用 /notifications 既有接口，其余走工作台接口
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, DataLine, Document, Odometer } from '@element-plus/icons-vue'
import {
  getDashboardSummary,
  listMyRequirements,
  type DashboardRequirement,
  type DashboardSummary
} from '@/api/dashboard'
import { listNotifications, type NotificationItem } from '@/api/notification'
import { priorityLabel, priorityTagType, statusLabel, statusTagType } from '@/constants/requirement'

const router = useRouter()

// ===== 数据 =====
const summary = ref<DashboardSummary>({
  unreadNotifications: 0,
  myRequirementCount: 0,
  monthEffort: 0,
  openBugCount: 0
})
const myReqs = ref<DashboardRequirement[]>([])
const notifications = ref<NotificationItem[]>([])
const reqsLoading = ref(false)

// 统计卡片（to 非空可点击跳转）
const statCards = computed(() => [
  { label: '未读通知', value: summary.value.unreadNotifications, icon: Bell, to: '' },
  { label: '参与需求', value: summary.value.myRequirementCount, icon: Document, to: '/requirements' },
  { label: '本月排期工时', value: summary.value.monthEffort, icon: DataLine, to: '/schedule' },
  { label: '待处理 Bug', value: summary.value.openBugCount, icon: Odometer, to: '/bugs' }
])

// 快捷入口
const quickLinks = [
  { label: '排期管理', path: '/schedule' },
  { label: '需求管理', path: '/requirements' },
  { label: '项目管理', path: '/projects' },
  { label: 'Bug 管理', path: '/bugs' },
  { label: '统计报表', path: '/reports' }
]

// ===== 加载 =====
onMounted(async () => {
  reqsLoading.value = true
  const [s, r, n] = await Promise.allSettled([
    getDashboardSummary(),
    listMyRequirements(),
    listNotifications({ page: 1, size: 8 })
  ])
  reqsLoading.value = false
  if (s.status === 'fulfilled') {
    summary.value = s.value.data.data
  } else {
    ElMessage.error('工作台汇总数据加载失败')
  }
  if (r.status === 'fulfilled') {
    myReqs.value = r.value.data.data || []
  } else {
    ElMessage.error('我的需求加载失败')
  }
  if (n.status === 'fulfilled') {
    notifications.value = n.value.data.data?.records || []
  } else {
    ElMessage.error('通知加载失败')
  }
})

// ===== 交互 =====
// 通知点击：按业务类型跳转（需求→详情页，Bug→Bug 管理）
function goNotification(n: NotificationItem) {
  if (n.bizType === 'REQUIREMENT') {
    router.push(`/requirements/${n.bizId}`)
  } else if (n.bizType === 'BUG') {
    router.push('/bugs')
  }
}

// 时间显示（ISO → yyyy-MM-dd HH:mm）
function fmtTime(v: string) {
  return v ? v.replace('T', ' ').slice(0, 16) : ''
}
</script>

<template>
  <div class="dashboard-page">
    <!-- 顶部：个人汇总统计卡片 -->
    <el-row :gutter="16">
      <el-col v-for="card in statCards" :key="card.label" :span="6">
        <el-card
          class="stat-card"
          :class="{ clickable: !!card.to }"
          shadow="never"
          @click="card.to && router.push(card.to)"
        >
          <div class="stat-body">
            <el-icon class="stat-icon" :size="30">
              <component :is="card.icon" />
            </el-icon>
            <div>
              <div class="stat-value">{{ card.value }}</div>
              <div class="stat-label">{{ card.label }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 中部：我参与的需求 + 最近通知 -->
    <el-row :gutter="16" class="mt16">
      <el-col :span="14">
        <el-card shadow="never">
          <template #header><span class="card-title">我参与的需求</span></template>
          <el-table
            :data="myReqs"
            v-loading="reqsLoading"
            class="req-table"
            :header-cell-style="{ background: '#a0cfff', color: '#000', fontWeight: 700 }"
            @row-click="(row: DashboardRequirement) => router.push(`/requirements/${row.id}`)"
          >
            <el-table-column prop="reqNo" label="编号" width="150" />
            <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
            <el-table-column prop="projectName" label="项目" width="130" show-overflow-tooltip />
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="优先级" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="priorityTagType(row.priority)" size="small" effect="plain">
                  {{ priorityLabel(row.priority) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never">
          <template #header><span class="card-title">最近通知</span></template>
          <div class="notif-list">
            <div
              v-for="item in notifications"
              :key="item.id"
              class="notif-item"
              @click="goNotification(item)"
            >
              <div class="notif-line">
                <span v-if="item.isRead === 0" class="notif-dot" />
                <span class="notif-title">{{ item.title }}</span>
                <span class="notif-time">{{ fmtTime(item.createdAt) }}</span>
              </div>
              <div class="notif-content">{{ item.content }}</div>
            </div>
            <el-empty v-if="!notifications.length" description="暂无通知" :image-size="60" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 底部：快捷入口 -->
    <el-row class="mt16">
      <el-col :span="24">
        <el-card shadow="never">
          <template #header><span class="card-title">快捷入口</span></template>
          <div class="quick-links">
            <el-button v-for="q in quickLinks" :key="q.path" @click="router.push(q.path)">
              {{ q.label }}
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* ===== 页面纵向排布 ===== */
.dashboard-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mt16 {
  margin-top: 0;
}

/* ===== 统计卡片 ===== */
.stat-card :deep(.el-card__body) {
  padding: 16px 20px;
}

.stat-card.clickable {
  cursor: pointer;
}

.stat-card.clickable:hover {
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
}

.stat-body {
  display: flex;
  align-items: center;
  gap: 14px;
}

.stat-icon {
  color: #1b4f8a;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: #909399;
}

/* ===== 卡片标题 ===== */
.card-title {
  font-weight: 600;
}

/* ===== 我参与的需求表：行可点击 ===== */
.req-table :deep(tbody tr) {
  cursor: pointer;
}

/* ===== 通知列表 ===== */
.notif-list {
  display: flex;
  flex-direction: column;
}

.notif-item {
  padding: 8px 4px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
}

.notif-item:last-child {
  border-bottom: none;
}

.notif-item:hover {
  background: #f5f7fa;
}

.notif-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.notif-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #f56c6c;
  flex-shrink: 0;
}

.notif-title {
  font-weight: 600;
  color: #303133;
  font-size: 13px;
}

.notif-time {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

.notif-content {
  margin-top: 2px;
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ===== 快捷入口 ===== */
.quick-links {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
