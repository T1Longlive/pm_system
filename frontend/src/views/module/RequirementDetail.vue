<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getRequirement,
  getRequirementLogs,
  cancelRequirement,
  archiveRequirement,
  type ReqDetail,
  type ReqLogItem
} from '@/api/requirement'
import {
  STATUS_OPTIONS,
  statusLabel,
  statusTagType,
  priorityLabel,
  priorityTagType
} from '@/constants/requirement'
import { craftLabel } from '@/constants/craft'
import { useUserStore } from '@/stores/user'
import ProgressSteps from '@/components/ProgressSteps.vue'
import RequirementFormDialog from '@/components/RequirementFormDialog.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 路由参数中的需求 id
const requirementId = Number(route.params.id)

// 需求详情数据
const detail = ref<ReqDetail | null>(null)
// 操作日志（倒序）
const logs = ref<ReqLogItem[]>([])
// 页面 loading
const loading = ref(false)

// 当前用户是否项目经理
const isPM = computed(() => detail.value?.myRole?.includes('PM') ?? false)

// 当前用户是否创建人
const isCreator = computed(() => {
  const v = detail.value?.createdBy
  const me = userStore.userInfo
  if (v === undefined || v === null || !me) return false
  return v === me.userId
})

// 是否终态（已归档 / 已取消，不可再流转）
const isTerminal = computed(() => {
  const opt = STATUS_OPTIONS.find((s) => s.value === detail.value?.status)
  return opt?.terminal ?? true
})

// 编辑按钮显隐：PM 或创建人，且非终态
const canEdit = computed(() => !isTerminal.value && (isPM.value || isCreator.value))

// 取消按钮显隐：PM，且非终态
const canCancel = computed(() => !isTerminal.value && isPM.value)

// 归档按钮显隐：PM，仅待归档状态
const canArchive = computed(() => detail.value?.status === 'ARCHIVING' && isPM.value)

// 参与人按工种分组（参与人卡片展示用）
const groupedParticipants = computed(() => {
  const map = new Map<string, string[]>()
  detail.value?.participants.forEach((p) => {
    map.set(p.craft, [...(map.get(p.craft) || []), p.realName])
  })
  return [...map.entries()].map(([craft, names]) => ({ craft, names }))
})

// 查询需求详情
const fetchDetail = async () => {
  loading.value = true
  try {
    const res = await getRequirement(requirementId)
    detail.value = res.data.data
  } finally {
    loading.value = false
  }
}

// 查询操作日志
const fetchLogs = async () => {
  try {
    const res = await getRequirementLogs(requirementId)
    logs.value = res.data.data
  } catch {
    // 失败已由拦截器统一提示
  }
}

// 刷新详情与日志（操作成功 / 乐观锁冲突后调用）
const refresh = () => {
  fetchDetail()
  fetchLogs()
}

// 返回需求列表
const goBack = () => {
  router.push('/requirements')
}

// 跳转所属项目详情
const goProject = () => {
  if (detail.value) router.push(`/projects/${detail.value.projectId}`)
}

// 附件点击：新窗口打开
const openFile = (url: string) => {
  window.open(url, '_blank')
}

// 文件大小格式化
const formatSize = (size: number) => {
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  if (size >= 1024) return `${(size / 1024).toFixed(0)} KB`
  return `${size} B`
}

// ===== 编辑弹窗（复用新增/编辑表单组件） =====
const editVisible = ref(false)

const openEdit = () => {
  editVisible.value = true
}

// 编辑成功：刷新详情与日志
const handleEdited = () => {
  refresh()
}

// 乐观锁冲突（409）：刷新详情拿最新 updatedAt，用户可基于最新数据重试
const handleConflict = () => {
  refresh()
}

// 取消需求（仅 PM，弹窗必填原因）
const handleCancel = async () => {
  try {
    const { value } = await ElMessageBox.prompt(
      `确认取消需求「${detail.value?.title}」？取消后不可恢复`,
      '取消需求',
      {
        type: 'warning',
        confirmButtonText: '确认取消',
        cancelButtonText: '再想想',
        inputPlaceholder: '请填写取消原因（必填）',
        inputValidator: (v: string) => !!v.trim() || '请填写取消原因'
      }
    )
    await cancelRequirement(requirementId, { reason: value.trim() })
    ElMessage.success('需求已取消')
    refresh()
  } catch {
    // 用户取消确认或接口失败（失败信息已由拦截器统一提示）
  }
}

// 归档需求（仅 PM，仅待归档状态）
const handleArchive = async () => {
  try {
    await ElMessageBox.confirm(`确认归档需求「${detail.value?.title}」？归档后进入终态`, '提示', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    await archiveRequirement(requirementId)
    ElMessage.success('需求已归档')
    refresh()
  } catch {
    // 用户取消确认或接口失败（失败信息已由拦截器统一提示）
  }
}

// 页面挂载后加载详情与日志
onMounted(() => {
  fetchDetail()
  fetchLogs()
})
</script>

<template>
  <div v-loading="loading" class="req-detail-page">
    <!-- 顶部：返回 + 编号 + 标题 + 操作按钮区 -->
    <div class="detail-toolbar">
      <el-button @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
        返回
      </el-button>
      <span class="req-no">{{ detail?.reqNo }}</span>
      <span class="req-title" :title="detail?.title">{{ detail?.title }}</span>
      <div class="toolbar-actions">
        <el-button v-if="canEdit" type="primary" @click="openEdit">编辑</el-button>
        <el-button v-if="canCancel" type="danger" plain @click="handleCancel">取消需求</el-button>
        <el-button v-if="canArchive" type="warning" plain @click="handleArchive">归档</el-button>
      </div>
    </div>

    <template v-if="detail">
      <!-- 完整进度条节点视图（含节点参与人头像） -->
      <el-card shadow="never">
        <template #header><span>需求进度</span></template>
        <ProgressSteps :status="detail.status" :participants="detail.participants" :craft-schedules="detail.craftSchedules" size="full" />
      </el-card>

      <!-- 基本信息 -->
      <el-card shadow="never">
        <template #header><span>基本信息</span></template>
        <el-descriptions :column="4">
          <el-descriptions-item label="需求编号">
            <span class="req-no">{{ detail.reqNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="所属项目">
            <el-link type="primary" :underline="false" @click="goProject">
              {{ detail.projectName }}
            </el-link>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="优先级">
            <el-tag :type="priorityTagType(detail.priority)">
              {{ priorityLabel(detail.priority) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="策划">{{ detail.plannerName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建人">{{ detail.creatorName || detail.createdBy }}</el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2">{{ detail.createdAt }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 需求描述：富文本渲染 -->
      <el-card shadow="never">
        <template #header><span>需求描述</span></template>
        <div class="rich-content" v-html="detail.description || '暂无描述'"></div>
      </el-card>

      <!-- 附件：点击新窗口打开 -->
      <el-card shadow="never">
        <template #header><span>附件</span></template>
        <template v-if="detail.attachments.length">
          <div v-for="f in detail.attachments" :key="f.id" class="att-item" @click="openFile(f.url)">
            <el-icon><Document /></el-icon>
            <span class="att-name" :title="f.fileName">{{ f.fileName }}</span>
            <span class="att-size">{{ formatSize(f.fileSize) }}</span>
            <el-icon class="att-link"><Right /></el-icon>
          </div>
        </template>
        <el-empty v-else description="暂无附件" />
      </el-card>

      <!-- 参与人：按工种分组 -->
      <el-card shadow="never">
        <template #header><span>参与人</span></template>
        <template v-if="groupedParticipants.length">
          <div v-for="g in groupedParticipants" :key="g.craft" class="participant-group">
            <el-tag size="small" type="info" class="group-craft">{{ craftLabel(g.craft) }}</el-tag>
            <el-tag v-for="(name, idx) in g.names" :key="idx" class="group-name">{{ name }}</el-tag>
          </div>
        </template>
        <el-empty v-else description="暂无参与人" />
      </el-card>

      <!-- 操作日志：时间线 -->
      <el-card shadow="never">
        <template #header><span>操作日志</span></template>
        <el-timeline v-if="logs.length" class="log-timeline">
          <el-timeline-item v-for="log in logs" :key="log.id" :timestamp="log.createdAt" placement="top">
            <div class="log-head">
              <span class="log-operator">{{ log.operatorName }}</span>
              <el-tag size="small" type="info">{{ log.action }}</el-tag>
            </div>
            <div v-if="log.remark" class="log-remark">{{ log.remark }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无操作记录" />
      </el-card>
    </template>

    <!-- 编辑需求弹窗（复用表单组件，编辑模式传详情数据） -->
    <RequirementFormDialog
      v-model="editVisible"
      :projects="[]"
      :edit-data="detail"
      @success="handleEdited"
      @conflict="handleConflict"
    />
  </div>
</template>

<style scoped>
/* 页面：纵向排布工具栏与信息卡片 */
.req-detail-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 300px;
}

/* 顶部：返回按钮 + 编号 + 标题 + 操作按钮 */
.detail-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #fff;
  border-radius: 4px;
}

/* 需求编号：等宽字体 */
.req-no {
  flex-shrink: 0;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 13px;
  color: #909399;
}

/* 需求标题：超长省略 */
.req-title {
  overflow: hidden;
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 操作按钮区推到最右 */
.toolbar-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

/* 富文本内容渲染：图片自适应宽度 */
.rich-content {
  line-height: 1.7;
  color: #303133;
  word-break: break-word;
}

.rich-content :deep(img) {
  max-width: 100%;
}

/* 附件行：可点击 */
.att-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #f8f9fb;
  font-size: 13px;
  color: #606266;
  cursor: pointer;
}

.att-item:hover {
  border-color: #c6e2ff;
  background: #ecf5ff;
}

/* 附件名超长省略 */
.att-name {
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 附件大小 */
.att-size {
  flex-shrink: 0;
  font-size: 12px;
  color: #909399;
}

/* 附件打开指示图标 */
.att-link {
  flex-shrink: 0;
  color: #c0c4cc;
}

/* 参与人按工种分组：每组一行 */
.participant-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.participant-group:last-child {
  margin-bottom: 0;
}

/* 工种标签 */
.group-craft {
  min-width: 64px;
  text-align: center;
}

/* 参与人姓名标签 */
.group-name {
  margin-right: 0;
}

/* 日志时间线：控制内边距 */
.log-timeline {
  padding-left: 6px;
}

/* 日志头部：操作人 + 动作码 */
.log-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.log-operator {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

/* 日志备注摘要 */
.log-remark {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
}
</style>
