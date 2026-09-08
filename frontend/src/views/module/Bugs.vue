<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { VxeTable, VxeColumn } from 'vxe-table'
import 'vxe-table/lib/style.css'
import { useUserStore } from '@/stores/user'
import { listProjects, getProject, type ProjectItem, type ProjectMember } from '@/api/project'
import {
  listBugs,
  createBug,
  updateBug,
  deleteBug,
  archiveBug,
  reopenBug,
  addBugImages,
  deleteBugImage,
  type BugListItem,
  type BugImage,
  type UpdateBugParams
} from '@/api/bug'
import { uploadFile } from '@/api/file'
import {
  HANDLE_STATUS_OPTIONS,
  ACCEPT_STATUS_OPTIONS,
  handleStatusLabel,
  handleStatusTagType,
  acceptStatusLabel,
  acceptStatusTagType
} from '@/constants/bug'
import { PRIORITY_OPTIONS, priorityLabel } from '@/constants/requirement'
import { craftLabel } from '@/constants/craft'

const userStore = useUserStore()

// 当前登录用户是否全局管理员（归档/重新打开按钮判断用）
const isAdmin = computed(() => userStore.userInfo?.isAdmin === 1)

// ==================== 项目选择与数据加载 ====================
// 项目下拉选项
const projects = ref<ProjectItem[]>([])
// 当前选中项目（空串 = 未选择，显示引导页）
const projectId = ref<number | ''>('')

// 拉取项目下拉数据（一次拉取一百条，超出部分一期不考虑）
const fetchProjects = async () => {
  try {
    const res = await listProjects({ page: 1, size: 100 })
    projects.value = res.data.data.records
  } catch {
    // 失败已由拦截器统一提示，项目下拉留空不影响
  }
}

// Bug 列表（总表）
const bugList = ref<BugListItem[]>([])
const bugLoading = ref(false)
// 归档表
const archList = ref<BugListItem[]>([])
const archLoading = ref(false)

// 查询总表（一页全量 Excel 模式）
const fetchBugs = async () => {
  if (projectId.value === '') return
  bugLoading.value = true
  try {
    const res = await listBugs({ projectId: projectId.value, page: 1, size: 1000 }, 0)
    bugList.value = res.data.data.records
  } finally {
    bugLoading.value = false
  }
}

// 查询归档表
const fetchArchive = async () => {
  if (projectId.value === '') return
  archLoading.value = true
  try {
    const res = await listBugs({ projectId: projectId.value, page: 1, size: 1000 }, 1)
    archList.value = res.data.data.records
  } finally {
    archLoading.value = false
  }
}

// 归档/重新打开/删除后两个表都刷新（行在两表间移动）
const refreshBoth = () => {
  fetchBugs()
  fetchArchive()
}

// 项目成员（负责人/测试人下拉数据源）
const members = ref<ProjectMember[]>([])

const fetchMembers = async () => {
  if (projectId.value === '') return
  try {
    const res = await getProject(projectId.value)
    members.value = res.data.data.members
  } catch {
    // 失败已由拦截器统一提示，下拉候选留空
  }
}

// 成员下拉选项：按用户去重，label = 姓名（工种、工种）
const memberOptions = computed(() => {
  const list: { userId: number; label: string }[] = []
  members.value.forEach((m) => {
    if (list.some((o) => o.userId === m.userId)) return
    const crafts = m.crafts.map(craftLabel).join('、')
    list.push({ userId: m.userId, label: crafts ? `${m.realName}（${crafts}）` : m.realName })
  })
  return list
})

// 切换项目：清空两个表与成员后加载（未选择项目时显示引导页；归档表懒加载）
const handleProjectChange = () => {
  bugList.value = []
  archList.value = []
  members.value = []
  if (projectId.value === '') return
  fetchBugs()
  fetchMembers()
}

// 页签：list = Bug 列表；archive = 归档表
const activeTab = ref<'list' | 'archive'>('list')

// 页签切换：归档表每次切入都刷新（保证归档/删除后数据最新）
const handleTabChange = (tab: string | number) => {
  if (tab === 'archive' && projectId.value !== '') {
    fetchArchive()
  }
}

// ==================== 行级权限 ====================
// 可归档/重新打开：该项目 PM 或全局管理员
const canArchiveRow = (row: BugListItem) => row.myRole.includes('PM') || isAdmin.value

// ==================== 文本格 DIY 编辑（照 Schedule.vue 模式） ====================
// 同一时刻仅一个编辑格：key = 行id|字段名
const editing = reactive<{ key: string; value: string }>({ key: '', value: '' })
const cellKey = (row: BugListItem, field: string) => `${row.id}|${field}`
const isEditing = (row: BugListItem, field: string) => editing.key === cellKey(row, field)

// 本地指令：编辑输入框挂载后自动聚焦；textarea 自适应内容高度
const autoSizeTextarea = (el: HTMLTextAreaElement) => {
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}
const vCellFocus = {
  mounted: (el: HTMLElement) => {
    el.focus()
    if (el instanceof HTMLTextAreaElement) {
      autoSizeTextarea(el)
      el.addEventListener('input', () => autoSizeTextarea(el))
    }
  }
}

// 点击单元格进入编辑（仅未归档行）
const startCellEdit = (row: BugListItem, field: string) => {
  if (row.archived !== 0) return
  editing.key = cellKey(row, field)
  editing.value = (row[field as keyof BugListItem] as string | null) ?? ''
}

// 提交编辑（blur / Enter / Ctrl+Enter 都会调，重复调用安全）
const commitCellEdit = (row: BugListItem, field: TextCellField) => {
  if (editing.key !== cellKey(row, field)) return
  const value = editing.value.trim()
  editing.key = ''
  saveText(row, field, value)
}

// 取消编辑（Esc）
const cancelCellEdit = () => {
  editing.key = ''
}

// 可编辑的文本格字段
type TextCellField = 'version' | 'feature' | 'description' | 'solution' | 'devRemark' | 'testRemark'

// 文本格保存：'' 清空（哨兵）→ 本地置 null，PUT 提交失败回滚
const saveText = async (row: BugListItem, field: TextCellField, value: string) => {
  const snapshot = row[field]
  const local = value === '' ? null : value
  if ((snapshot ?? '') === value) return
  row[field] = local
  try {
    await updateBug(row.id, { [field]: value } as UpdateBugParams)
  } catch {
    row[field] = snapshot
  }
}

// ==================== 下拉格保存 ====================
type NumField = 'ownerId' | 'testerId' | 'priority'
type StatusField = 'handleStatus' | 'acceptStatus'

// 数值/ID 下拉保存：清空传 0（哨兵）→ 本地置 null
const saveSelectNum = async (row: BugListItem, field: NumField, value: number | '' | undefined) => {
  const snapshot = row[field]
  const local = value == null || value === '' ? null : value
  if (snapshot === local) return
  ;(row as unknown as Record<NumField, number | null>)[field] = local
  try {
    await updateBug(row.id, { [field]: local === null ? 0 : local } as UpdateBugParams)
  } catch {
    ;(row as unknown as Record<NumField, number | null>)[field] = snapshot as number | null
  }
}

// 状态下拉保存：清空传 ''（哨兵）→ 本地置 null
const saveSelectStatus = async (row: BugListItem, field: StatusField, value: string | '' | undefined) => {
  const snapshot = row[field]
  const local = value ? value : null
  if (snapshot === local) return
  ;(row as unknown as Record<StatusField, string | null>)[field] = local
  try {
    await updateBug(row.id, { [field]: local ?? '' } as UpdateBugParams)
  } catch {
    ;(row as unknown as Record<StatusField, string | null>)[field] = snapshot as string | null
  }
}

// ==================== 添加 Bug ====================
const adding = ref(false)

// 新增行：编号自动生成、测试人员=当前用户，新行置顶，其余留空单元格编辑
const handleAdd = async () => {
  if (projectId.value === '') return
  adding.value = true
  try {
    const res = await createBug({ projectId: projectId.value })
    bugList.value.unshift(res.data.data)
    ElMessage.success('已添加 Bug')
  } finally {
    adding.value = false
  }
}

// ==================== 归档 / 重新打开 / 删除 ====================
const confirmArchive = (row: BugListItem) => {
  ElMessageBox.confirm(`确定归档 Bug「${row.bugNo}」？归档后可在归档表查看（只读）`, '归档确认', {
    type: 'warning',
    confirmButtonText: '归档',
    cancelButtonText: '取消'
  }).then(async () => {
    await archiveBug(row.id)
    ElMessage.success('归档成功')
    refreshBoth()
  }).catch(() => {})
}

const confirmReopen = (row: BugListItem) => {
  ElMessageBox.confirm(
    `确定重新打开 Bug「${row.bugNo}」？处理进度与验收进度将被置空`,
    '重新打开确认',
    { type: 'warning', confirmButtonText: '重新打开', cancelButtonText: '取消' }
  ).then(async () => {
    await reopenBug(row.id)
    ElMessage.success('已重新打开')
    refreshBoth()
  }).catch(() => {})
}

const confirmDelete = (row: BugListItem) => {
  ElMessageBox.confirm(`确定删除 Bug「${row.bugNo}」？删除后不可恢复`, '删除确认', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  }).then(async () => {
    await deleteBug(row.id)
    ElMessage.success('删除成功')
    refreshBoth()
  }).catch(() => {})
}

// ==================== 截图列 ====================
// 视频名称兜底：无名称时取 URL 尾段
const videoText = (row: BugListItem) => {
  if (row.videoName) return row.videoName
  if (row.videoUrl) {
    const tail = row.videoUrl.split('/').pop() || row.videoUrl
    try {
      return decodeURIComponent(tail)
    } catch {
      return tail
    }
  }
  return ''
}

// 行截图大图列表（走马灯预览用）
const imageUrlsOf = (row: BugListItem) => row.images.map((i) => i.url)

// ==================== 截图管理弹窗（预览 / 上传 / 粘贴 / 删除） ====================
const imageVisible = ref(false)
const imageTarget = ref<BugListItem | null>(null)
const dialogImages = ref<BugImage[]>([])
// 归档行只可预览
const imageManageable = computed(() => !!imageTarget.value && imageTarget.value.archived === 0)

// 图片类型白名单：png/jpg/jpeg/gif/webp
const IMAGE_EXTS = ['.png', '.jpg', '.jpeg', '.gif', '.webp']
const imageUploading = ref(false)

const openImageDialog = (row: BugListItem) => {
  imageTarget.value = row
  dialogImages.value = row.images.map((i) => ({ ...i }))
  imageVisible.value = true
}

// 上传前校验：类型白名单 + 10MB 上限
const beforeImageUpload = (file: File) => {
  const ext = file.name.slice(file.name.lastIndexOf('.')).toLowerCase()
  if (!IMAGE_EXTS.includes(ext)) {
    ElMessage.error('仅支持 png / jpg / jpeg / gif / webp 格式图片')
    return false
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('图片大小不超过 10MB')
    return false
  }
  return true
}

// 上传单张并落库（上传文件接口 + 追加截图接口），同步弹窗与行数据
const uploadOneImage = async (file: File) => {
  const res = await uploadFile(file)
  const img: BugImage = {
    url: res.data.data.url,
    fileName: res.data.data.fileName,
    fileType: 'IMAGE',
    fileSize: res.data.data.fileSize
  }
  const ids = (await addBugImages(imageTarget.value!.id, [img])).data.data
  const withId = { ...img, id: ids[0] }
  dialogImages.value.push(withId)
  imageTarget.value!.images.push(withId)
}

// 自定义上传请求（el-upload 多选逐张回调）
const handleImageUploadRequest = async (options: UploadRequestOptions) => {
  imageUploading.value = true
  try {
    await uploadOneImage(options.file)
  } catch {
    // 上传失败：拦截器已统一弹错，此处无需处理
  } finally {
    imageUploading.value = false
  }
}

// 弹窗内粘贴上传：监听窗口 paste 事件（打开时挂载、关闭时移除）
const handlePaste = async (e: ClipboardEvent) => {
  if (!imageManageable.value) return
  const files = Array.from(e.clipboardData?.files || []).filter((f) => f.type.startsWith('image/'))
  if (files.length === 0) return
  imageUploading.value = true
  try {
    for (const f of files) {
      await uploadOneImage(f)
    }
    ElMessage.success('粘贴图片已上传')
  } catch {
    // 上传失败：拦截器已统一弹错
  } finally {
    imageUploading.value = false
  }
}

watch(imageVisible, (v) => {
  if (v) {
    window.addEventListener('paste', handlePaste)
  } else {
    window.removeEventListener('paste', handlePaste)
  }
})

// 删除单张截图（同步弹窗与行数据）
const removeImage = (img: BugImage, index: number) => {
  const attachmentId = img.id
  if (attachmentId == null) return
  ElMessageBox.confirm('确定删除该截图？', '删除确认', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  }).then(async () => {
    await deleteBugImage(imageTarget.value!.id, attachmentId)
    dialogImages.value.splice(index, 1)
    const rowIndex = imageTarget.value!.images.findIndex((i) => i.id === attachmentId)
    if (rowIndex >= 0) imageTarget.value!.images.splice(rowIndex, 1)
    ElMessage.success('删除成功')
  }).catch(() => {})
}

// 预览大图列表
const previewUrls = computed(() => dialogImages.value.map((i) => i.url))

// 文件大小格式化
const formatSize = (size: number) => {
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  if (size >= 1024) return `${(size / 1024).toFixed(0)} KB`
  return `${size} B`
}

// ==================== 视频弹窗（播放 / 上传 / 粘贴 / 清除） ====================
const videoVisible = ref(false)
const videoTarget = ref<BugListItem | null>(null)
const videoUrlInput = ref('')
// 本次上传的原始文件名（保存时作为视频名称，外链则取 URL 尾段）
const uploadedVideoName = ref('')
// 视频类型白名单：mp4/webm/mov
const VIDEO_EXTS = ['.mp4', '.webm', '.mov']
const videoUploading = ref(false)

const openVideoDialog = (row: BugListItem) => {
  videoTarget.value = row
  videoUrlInput.value = row.videoUrl ?? ''
  uploadedVideoName.value = ''
  videoVisible.value = true
}

// 上传前校验：类型白名单 + 50MB 上限
const beforeVideoUpload = (file: File) => {
  const ext = file.name.slice(file.name.lastIndexOf('.')).toLowerCase()
  if (!VIDEO_EXTS.includes(ext)) {
    ElMessage.error('仅支持 mp4 / webm / mov 格式视频')
    return false
  }
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.error('视频大小不超过 50MB')
    return false
  }
  return true
}

// 上传视频：拿到 url 与原始文件名后回填输入框（确认时一并保存）
const handleVideoUploadRequest = async (options: UploadRequestOptions) => {
  videoUploading.value = true
  try {
    const res = await uploadFile(options.file)
    videoUrlInput.value = res.data.data.url
    uploadedVideoName.value = res.data.data.fileName
    ElMessage.success('视频上传成功，点击确定保存')
  } catch {
    // 上传失败：拦截器已统一弹错
  } finally {
    videoUploading.value = false
  }
}

// 外链 URL 的显示名兜底：取 URL 尾段
const videoNameFromUrl = (url: string) => {
  const tail = url.split('/').pop() || url
  let name = tail
  try {
    name = decodeURIComponent(tail)
  } catch {
    // 保持原样
  }
  return name.slice(0, 200)
}

// 保存视频（上传或粘贴后点确定）：一次 PUT 两个字段
const saveVideo = async () => {
  const row = videoTarget.value
  if (!row) return
  const url = videoUrlInput.value.trim()
  if (!url) {
    ElMessage.warning('请先上传或粘贴视频链接')
    return
  }
  // 名称优先取本次上传的原始文件名，否则取 URL 尾段
  const name = uploadedVideoName.value || videoNameFromUrl(url)
  await updateBug(row.id, { videoUrl: url, videoName: name })
  row.videoUrl = url
  row.videoName = name
  videoVisible.value = false
  ElMessage.success('视频已保存')
}

// 清除视频：双清 URL 与名称
const clearVideo = async () => {
  const row = videoTarget.value
  if (!row) return
  await updateBug(row.id, { videoUrl: '', videoName: '' })
  row.videoUrl = null
  row.videoName = null
  videoUrlInput.value = ''
  videoVisible.value = false
  ElMessage.success('视频已清除')
}

// 页面挂载后加载项目下拉
onMounted(() => {
  fetchProjects()
})
</script>

<template>
  <div class="bugs-page">
    <!-- 顶部：项目选择（必选） + 添加Bug -->
    <div class="page-toolbar">
      <span class="toolbar-label">项目</span>
      <el-select
        v-model="projectId"
        class="toolbar-project"
        placeholder="请选择项目"
        filterable
        @change="handleProjectChange"
      >
        <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <el-tooltip content="请先选择项目" :disabled="projectId !== ''">
        <span>
          <el-button type="primary" :disabled="projectId === ''" :loading="adding" @click="handleAdd">
            添加Bug
          </el-button>
        </span>
      </el-tooltip>
    </div>

    <!-- 未选择项目：引导 -->
    <el-card v-if="projectId === ''" shadow="never" class="table-card">
      <el-empty description="请先选择项目，查看该项目的 Bug 列表与归档表" :image-size="100" />
    </el-card>

    <!-- 选择项目后：页签切换 Bug 列表 / 归档表 -->
    <el-tabs v-if="projectId !== ''" v-model="activeTab" class="bugs-tabs" @tab-change="handleTabChange">
      <el-tab-pane label="Bug 列表" name="list">
      <el-card shadow="never" class="table-card">
        <vxe-table :data="bugList" v-loading="bugLoading" border size="small" :row-config="{ keyField: 'id', isHover: true }" :column-config="{ resizable: true }">
          <vxe-column field="bugNo" title="编号" width="140">
            <template #default="{ row }">
              <span class="bug-no">{{ row.bugNo }}</span>
            </template>
          </vxe-column>
          <vxe-column field="projectName" title="项目" width="110" show-overflow />
          <vxe-column field="version" title="版本" width="90">
            <template #default="{ row }">
              <input
                v-if="isEditing(row, 'version')"
                v-cell-focus
                v-model="editing.value"
                class="cell-input"
                @keyup.enter="commitCellEdit(row, 'version')"
                @keyup.esc="cancelCellEdit"
                @blur="commitCellEdit(row, 'version')"
              />
              <div v-else class="cell-text" :class="{ editable: row.archived === 0 }" @click="startCellEdit(row, 'version')">
                {{ row.version }}
              </div>
            </template>
          </vxe-column>
          <vxe-column field="feature" title="功能" width="140">
            <template #default="{ row }">
              <input
                v-if="isEditing(row, 'feature')"
                v-cell-focus
                v-model="editing.value"
                class="cell-input"
                @keyup.enter="commitCellEdit(row, 'feature')"
                @keyup.esc="cancelCellEdit"
                @blur="commitCellEdit(row, 'feature')"
              />
              <div v-else class="cell-text" :class="{ editable: row.archived === 0 }" @click="startCellEdit(row, 'feature')">
                {{ row.feature }}
              </div>
            </template>
          </vxe-column>
          <vxe-column field="description" title="描述" width="220">
            <template #default="{ row }">
              <textarea
                v-if="isEditing(row, 'description')"
                v-cell-focus
                v-model="editing.value"
                class="cell-textarea"
                @keyup.esc="cancelCellEdit"
                @blur="commitCellEdit(row, 'description')"
                @keydown.ctrl.enter.prevent="commitCellEdit(row, 'description')"
              />
              <div v-else class="cell-text" :class="{ editable: row.archived === 0 }" @click="startCellEdit(row, 'description')">
                {{ row.description }}
              </div>
            </template>
          </vxe-column>
          <vxe-column field="images" title="截图" width="110">
            <template #default="{ row }">
              <div class="img-cell">
                <template v-if="row.images.length">
                  <el-image
                    :src="row.images[0].url"
                    fit="cover"
                    class="img-thumb"
                    :preview-src-list="imageUrlsOf(row)"
                    :initial-index="0"
                    preview-teleported
                    hide-on-click-modal
                  />
                  <span class="img-count">{{ row.images.length }}</span>
                </template>
                <span v-else class="cell-empty"></span>
                <el-icon
                  v-if="row.archived === 0"
                  class="img-manage"
                  title="管理截图"
                  @click="openImageDialog(row)"
                ><Plus /></el-icon>
              </div>
            </template>
          </vxe-column>
          <vxe-column field="videoName" title="视频" width="130">
            <template #default="{ row }">
              <el-button v-if="row.videoUrl" link type="primary" class="video-link" @click="openVideoDialog(row)">
                <el-icon><VideoPlay /></el-icon>
                <span class="video-name" :title="videoText(row)">{{ videoText(row) }}</span>
              </el-button>
              <el-icon
                v-else-if="row.archived === 0"
                class="video-add"
                title="添加视频"
                @click="openVideoDialog(row)"
              ><Plus /></el-icon>
            </template>
          </vxe-column>
          <vxe-column field="solution" title="处理方案" width="180">
            <template #default="{ row }">
              <textarea
                v-if="isEditing(row, 'solution')"
                v-cell-focus
                v-model="editing.value"
                class="cell-textarea"
                @keyup.esc="cancelCellEdit"
                @blur="commitCellEdit(row, 'solution')"
                @keydown.ctrl.enter.prevent="commitCellEdit(row, 'solution')"
              />
              <div v-else class="cell-text" :class="{ editable: row.archived === 0 }" @click="startCellEdit(row, 'solution')">
                {{ row.solution }}
              </div>
            </template>
          </vxe-column>
          <vxe-column field="ownerName" title="负责人" width="150">
            <template #default="{ row }">
              <el-select
                v-if="row.archived === 0"
                :model-value="row.ownerId"
                class="cell-select"
                size="small"
                filterable
                clearable
                placeholder="未指定"
                @change="(v: number | '' | undefined) => saveSelectNum(row, 'ownerId', v)"
              >
                <el-option v-for="m in memberOptions" :key="m.userId" :label="m.label" :value="m.userId" />
              </el-select>
              <span v-else>{{ row.ownerName }}</span>
            </template>
          </vxe-column>
          <vxe-column field="handleStatus" title="处理进度" width="110">
            <template #default="{ row }">
              <el-select
                v-if="row.archived === 0"
                :model-value="row.handleStatus"
                class="cell-select"
                size="small"
                clearable
                placeholder="未处理"
                @change="(v: string | '' | undefined) => saveSelectStatus(row, 'handleStatus', v)"
              >
                <el-option v-for="s in HANDLE_STATUS_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
              </el-select>
              <el-tag v-else :type="handleStatusTagType(row.handleStatus)">
                {{ handleStatusLabel(row.handleStatus) }}
              </el-tag>
            </template>
          </vxe-column>
          <vxe-column field="testerName" title="测试人员" width="150">
            <template #default="{ row }">
              <el-select
                v-if="row.archived === 0"
                :model-value="row.testerId"
                class="cell-select"
                size="small"
                filterable
                clearable
                placeholder="未指定"
                @change="(v: number | '' | undefined) => saveSelectNum(row, 'testerId', v)"
              >
                <el-option v-for="m in memberOptions" :key="m.userId" :label="m.label" :value="m.userId" />
              </el-select>
              <span v-else>{{ row.testerName }}</span>
            </template>
          </vxe-column>
          <vxe-column field="acceptStatus" title="验收进度" width="110">
            <template #default="{ row }">
              <el-select
                v-if="row.archived === 0"
                :model-value="row.acceptStatus"
                class="cell-select"
                size="small"
                clearable
                placeholder="未验收"
                @change="(v: string | '' | undefined) => saveSelectStatus(row, 'acceptStatus', v)"
              >
                <el-option v-for="s in ACCEPT_STATUS_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
              </el-select>
              <el-tag v-else :type="acceptStatusTagType(row.acceptStatus)">
                {{ acceptStatusLabel(row.acceptStatus) }}
              </el-tag>
            </template>
          </vxe-column>
          <vxe-column field="priority" title="优先级" width="90">
            <template #default="{ row }">
              <el-select
                v-if="row.archived === 0"
                :model-value="row.priority"
                class="cell-select"
                size="small"
                clearable
                placeholder=""
                @change="(v: number | '' | undefined) => saveSelectNum(row, 'priority', v)"
              >
                <el-option v-for="p in PRIORITY_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
              </el-select>
              <span v-else>{{ row.priority ? priorityLabel(row.priority) : "" }}</span>
            </template>
          </vxe-column>
          <vxe-column field="devRemark" title="开发备注" width="160">
            <template #default="{ row }">
              <textarea
                v-if="isEditing(row, 'devRemark')"
                v-cell-focus
                v-model="editing.value"
                class="cell-textarea"
                @keyup.esc="cancelCellEdit"
                @blur="commitCellEdit(row, 'devRemark')"
                @keydown.ctrl.enter.prevent="commitCellEdit(row, 'devRemark')"
              />
              <div v-else class="cell-text" :class="{ editable: row.archived === 0 }" @click="startCellEdit(row, 'devRemark')">
                {{ row.devRemark }}
              </div>
            </template>
          </vxe-column>
          <vxe-column field="testRemark" title="测试备注" width="160">
            <template #default="{ row }">
              <textarea
                v-if="isEditing(row, 'testRemark')"
                v-cell-focus
                v-model="editing.value"
                class="cell-textarea"
                @keyup.esc="cancelCellEdit"
                @blur="commitCellEdit(row, 'testRemark')"
                @keydown.ctrl.enter.prevent="commitCellEdit(row, 'testRemark')"
              />
              <div v-else class="cell-text" :class="{ editable: row.archived === 0 }" @click="startCellEdit(row, 'testRemark')">
                {{ row.testRemark }}
              </div>
            </template>
          </vxe-column>
          <vxe-column title="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canArchiveRow(row)" link type="warning" @click="confirmArchive(row)">归档</el-button>
              <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
            </template>
          </vxe-column>
        </vxe-table>
      </el-card>
      </el-tab-pane>

      <!-- 页签二：归档表（只读） -->
      <el-tab-pane label="归档表" name="archive">
      <el-card shadow="never" class="table-card">
        <vxe-table :data="archList" v-loading="archLoading" border size="small" :row-config="{ keyField: 'id', isHover: true }" :column-config="{ resizable: true }">
          <vxe-column field="bugNo" title="编号" width="140">
            <template #default="{ row }">
              <span class="bug-no">{{ row.bugNo }}</span>
            </template>
          </vxe-column>
          <vxe-column field="projectName" title="项目" width="110" show-overflow />
          <vxe-column field="version" title="版本" width="90">
            <template #default="{ row }">{{ row.version }}</template>
          </vxe-column>
          <vxe-column field="feature" title="功能" width="140">
            <template #default="{ row }">{{ row.feature }}</template>
          </vxe-column>
          <vxe-column field="description" title="描述" width="220">
            <template #default="{ row }">
              <div class="cell-text">{{ row.description }}</div>
            </template>
          </vxe-column>
          <vxe-column field="images" title="截图" width="110">
            <template #default="{ row }">
              <div class="img-cell">
                <template v-if="row.images.length">
                  <el-image
                    :src="row.images[0].url"
                    fit="cover"
                    class="img-thumb"
                    :preview-src-list="imageUrlsOf(row)"
                    :initial-index="0"
                    preview-teleported
                    hide-on-click-modal
                  />
                  <span class="img-count">{{ row.images.length }}</span>
                </template>
                <span v-else class="cell-empty"></span>
              </div>
            </template>
          </vxe-column>
          <vxe-column field="videoName" title="视频" width="130">
            <template #default="{ row }">
              <el-button v-if="row.videoUrl" link type="primary" class="video-link" @click="openVideoDialog(row)">
                <el-icon><VideoPlay /></el-icon>
                <span class="video-name" :title="videoText(row)">{{ videoText(row) }}</span>
              </el-button>
              <span v-else class="cell-empty"></span>
            </template>
          </vxe-column>
          <vxe-column field="solution" title="处理方案" width="180">
            <template #default="{ row }">{{ row.solution }}</template>
          </vxe-column>
          <vxe-column field="ownerName" title="负责人" width="120">
            <template #default="{ row }">{{ row.ownerName }}</template>
          </vxe-column>
          <vxe-column field="handleStatus" title="处理进度" width="100">
            <template #default="{ row }">
              <el-tag :type="handleStatusTagType(row.handleStatus)">{{ handleStatusLabel(row.handleStatus) }}</el-tag>
            </template>
          </vxe-column>
          <vxe-column field="testerName" title="测试人员" width="120">
            <template #default="{ row }">{{ row.testerName }}</template>
          </vxe-column>
          <vxe-column field="acceptStatus" title="验收进度" width="100">
            <template #default="{ row }">
              <el-tag :type="acceptStatusTagType(row.acceptStatus)">{{ acceptStatusLabel(row.acceptStatus) }}</el-tag>
            </template>
          </vxe-column>
          <vxe-column field="priority" title="优先级" width="80">
            <template #default="{ row }">{{ row.priority ? priorityLabel(row.priority) : '' }}</template>
          </vxe-column>
          <vxe-column field="devRemark" title="开发备注" width="160">
            <template #default="{ row }">{{ row.devRemark }}</template>
          </vxe-column>
          <vxe-column field="testRemark" title="测试备注" width="160">
            <template #default="{ row }">{{ row.testRemark }}</template>
          </vxe-column>
          <vxe-column title="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canArchiveRow(row)" link type="primary" @click="confirmReopen(row)">重新打开</el-button>
              <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
            </template>
          </vxe-column>
        </vxe-table>
      </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 截图管理弹窗（预览 / 上传 / 粘贴 / 删除） -->
    <el-dialog
      v-model="imageVisible"
      title="问题截图"
      width="640px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="image-manager">
        <div v-if="dialogImages.length" class="image-grid">
          <div v-for="(img, idx) in dialogImages" :key="img.id ?? img.url" class="image-item">
            <el-image
              :src="img.url"
              fit="cover"
              class="image-view"
              :preview-src-list="previewUrls"
              :initial-index="idx"
              preview-teleported
              hide-on-click-modal
            />
            <el-icon
              v-if="imageManageable"
              class="image-remove"
              title="删除截图"
              @click="removeImage(img, idx)"
            ><CircleCloseFilled /></el-icon>
            <div class="image-name" :title="img.fileName">{{ img.fileName }}</div>
            <div class="image-size">{{ formatSize(img.fileSize) }}</div>
          </div>
        </div>
        <el-empty v-else description="暂无截图" :image-size="80" />
        <div v-if="imageManageable" class="image-upload">
          <el-upload
            action="#"
            multiple
            accept=".png,.jpg,.jpeg,.gif,.webp"
            :show-file-list="false"
            :before-upload="beforeImageUpload"
            :http-request="handleImageUploadRequest"
          >
            <el-button type="primary" :loading="imageUploading">添加截图</el-button>
          </el-upload>
          <span class="image-upload-tip">支持多选 / 直接粘贴图片，png / jpg / jpeg / gif / webp，单张不超过 10MB</span>
        </div>
      </div>
    </el-dialog>

    <!-- 视频弹窗（播放 / 上传 / 粘贴 / 清除） -->
    <el-dialog
      v-model="videoVisible"
      :title="videoTarget ? `问题视频 - ${videoTarget.bugNo}` : '问题视频'"
      width="720px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <video v-if="videoTarget?.videoUrl" :src="videoTarget.videoUrl" controls class="video-player">
        您的浏览器不支持视频播放
      </video>
      <div v-else class="video-empty">该 Bug 暂无视频</div>
      <el-form label-width="90px" class="video-form" @submit.prevent>
        <el-form-item label="视频链接">
          <el-input v-model="videoUrlInput" placeholder="可上传视频或粘贴外链 URL" clearable />
        </el-form-item>
        <el-form-item label="上传视频">
          <el-upload
            action="#"
            accept=".mp4,.webm,.mov"
            :show-file-list="false"
            :before-upload="beforeVideoUpload"
            :http-request="handleVideoUploadRequest"
          >
            <el-button :loading="videoUploading">上传视频</el-button>
          </el-upload>
          <span class="image-upload-tip">支持 mp4 / webm / mov，不超过 50MB</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="videoTarget?.videoUrl" type="danger" plain @click="clearVideo">清除视频</el-button>
        <el-button @click="videoVisible = false">取消</el-button>
        <el-button type="primary" @click="saveVideo">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页面：纵向排布工具栏与两个表格区块 */
.bugs-page {
  display: flex;
  flex-direction: column;
}

/* 顶部工具栏：白底圆角，添加按钮靠右 */
.page-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;
  background: #fff;
  border-radius: 4px;
}

/* 工具栏标签 */
.toolbar-label {
  font-size: 14px;
  color: #606266;
}

/* 项目下拉宽度 */
.toolbar-project {
  width: 260px;
}

/* 工具栏按钮推到最右 */
.page-toolbar > :last-child {
  margin-left: auto;
}

/* 页签头与内容间距 */
.bugs-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

/* 表格卡片与页签间距 */
.table-card {
  margin-bottom: 20px;
}

/* Bug 编号：等宽字体 */
.bug-no {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 13px;
  color: #606266;
}

/* ===== 单元格 DIY 编辑：输入框即单元格 ===== */
/* 编辑态单行输入框：铺满单元格、无边框白色背景，视觉上就是在单元格里输入 */
.cell-input {
  display: block;
  width: 100%;
  padding: 0;
  border: none;
  background: #fff;
  font-size: 13px;
  color: #606266;
  outline: none;
}

/* 编辑态多行文本框：铺满单元格，高度随内容自适应增长 */
.cell-textarea {
  display: block;
  width: 100%;
  min-height: 20px;
  padding: 0;
  border: none;
  background: #fff;
  font-size: 13px;
  line-height: 1.5;
  color: #606266;
  resize: none;
  overflow: hidden;
  outline: none;
}

/* 展示态文本：按单元格宽度自适应换行，行高随内容增高，保留换行 */
.cell-text {
  max-width: 100%;
  min-height: 20px;
  font-size: 13px;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 可编辑格 hover 背景 */
.cell-text.editable {
  cursor: text;
}

.cell-text.editable:hover {
  background: #f5f7fa;
}

/* ===== 截图列 ===== */
.img-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 缩略图尺寸 */
.img-thumb {
  display: block;
  width: 44px;
  height: 44px;
  border-radius: 4px;
  cursor: zoom-in;
}

/* 截图数量 */
.img-count {
  font-size: 12px;
  color: #909399;
}

/* 管理截图入口 */
.img-manage {
  color: #409eff;
  cursor: pointer;
}

.img-manage:hover {
  color: #66b1ff;
}

/* ===== 视频列 ===== */
.video-link {
  max-width: 100%;
  padding: 0;
}

/* 视频名单行省略 */
.video-name {
  max-width: 90px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 添加视频入口 */
.video-add {
  color: #409eff;
  cursor: pointer;
}

.video-add:hover {
  color: #66b1ff;
}

/* ===== 下拉格：透明边框模拟 Excel，hover 显示边框 ===== */
.bugs-page :deep(.cell-select .el-select__wrapper) {
  min-height: 26px;
  padding: 0 6px;
  background: transparent;
  box-shadow: none;
  font-size: 13px;
}

.bugs-page :deep(.cell-select:hover .el-select__wrapper),
.bugs-page :deep(.cell-select.is-focus .el-select__wrapper) {
  background: #fff;
  box-shadow: 0 0 0 1px #dcdfe6 inset;
}

/* ===== 截图管理弹窗 ===== */
.image-manager {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 图片网格 */
.image-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  max-height: 400px;
  overflow-y: auto;
}

/* 单张图片卡片 */
.image-item {
  position: relative;
}

/* 图片预览区 */
.image-view {
  width: 100%;
  height: 110px;
  border-radius: 4px;
  cursor: zoom-in;
}

/* 删除角标 */
.image-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  font-size: 18px;
  color: #909399;
  background: #fff;
  border-radius: 50%;
  cursor: pointer;
}

.image-remove:hover {
  color: #f56c6c;
}

/* 图片名超长省略 */
.image-name {
  overflow: hidden;
  margin-top: 4px;
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 图片大小 */
.image-size {
  font-size: 11px;
  color: #c0c4cc;
}

/* 上传行 */
.image-upload {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 上传提示 */
.image-upload-tip {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}

/* ===== 视频弹窗 ===== */
.video-player {
  width: 100%;
  max-height: 55vh;
  margin-bottom: 12px;
  border-radius: 4px;
  background: #000;
}

/* 无视频占位 */
.video-empty {
  padding: 24px 0;
  margin-bottom: 12px;
  font-size: 13px;
  color: #909399;
  text-align: center;
  background: #f5f7fa;
  border-radius: 4px;
}

/* 视频表单 */
.video-form {
  margin-top: 4px;
}
</style>
