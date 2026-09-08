<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules, UploadRequestOptions } from 'element-plus'
import '@wangeditor/editor/dist/css/style.css'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'
import type { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor'
import {
  createRequirement,
  updateRequirement,
  type ReqAttachment,
  type ReqDetail
} from '@/api/requirement'
import { getProject, type ProjectItem, type ProjectMember } from '@/api/project'
import { uploadFile } from '@/api/file'
import { CRAFT_OPTIONS } from '@/constants/craft'
import { PRIORITY_OPTIONS } from '@/constants/requirement'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  // 弹窗显隐（v-model）
  modelValue: boolean
  // 可选项目列表（新增模式的项目下拉）
  projects: ProjectItem[]
  // 编辑模式传入需求详情；不传或为 null 表示新增
  editData?: ReqDetail | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  // 提交成功（父页面刷新列表/详情）
  success: []
  // 乐观锁冲突（后端 code=409）：父页面需刷新详情
  conflict: []
}>()

const userStore = useUserStore()

// 弹窗显隐双向绑定
const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

// 是否编辑模式
const isEdit = computed(() => !!props.editData)

// 新增模式下可选项目：用户担任 PM 或策划的项目（权限矩阵：新增需求限 PM/策划）
const creatableProjects = computed(() =>
  props.projects.filter((p) => p.myRole.includes('PM') || p.myRole.includes('PLANNER'))
)

// ===== 表单数据 =====
const formRef = ref<FormInstance>()
// 提交按钮 loading
const submitting = ref(false)

const form = reactive<{
  projectId?: number
  title: string
  priority: 1 | 2 | 3
  plannerId?: number
}>({
  projectId: undefined,
  title: '',
  priority: 2,
  plannerId: undefined
})

// 表单校验规则
const formRules: FormRules = {
  projectId: [{ required: true, message: '请选择项目', trigger: 'change' }],
  title: [
    { required: true, message: '请输入需求名称', trigger: 'blur' },
    { max: 100, message: '需求名称不超过 100 个字', trigger: 'blur' }
  ]
}

// 富文本描述（html）
const descriptionHtml = ref('')

// 已上传附件列表（编辑时回显，提交时整体提交）
const attachments = ref<ReqAttachment[]>([])

// 参与人行：工种 + 该工种成员多选
interface ParticipantRow {
  craft: string
  userIds: number[]
}
const participantRows = ref<ParticipantRow[]>([])

// 所选项目的成员列表（参与人/策划下拉数据源）
const members = ref<ProjectMember[]>([])
// 项目创建人（即 PM，策划下拉候选）
const projectCreator = ref<{ userId: number; realName: string } | null>(null)
// 成员加载状态
const membersLoading = ref(false)

// 成员选项源：项目成员接口数据 + 编辑数据自带参与人兜底
// （编辑模式成员接口返回前，参与人/策划下拉也能正确显示姓名而非裸 id）
const allMembers = computed<ProjectMember[]>(() => {
  const list = members.value.map((m) => ({ ...m, crafts: [...m.crafts] }))
  const edit = props.editData
  if (edit) {
    edit.participants.forEach((p) => {
      const existing = list.find((m) => m.userId === p.userId)
      if (existing) {
        if (!existing.crafts.includes(p.craft)) existing.crafts.push(p.craft)
      } else {
        list.push({ userId: p.userId, realName: p.realName, username: '', crafts: [p.craft] })
      }
    })
  }
  return list
})

// 策划下拉选项：编辑数据策划兜底 + 项目 PM + 项目 PLANNER 工种成员，按用户去重
const plannerOptions = computed(() => {
  const list: { id: number; realName: string }[] = []
  const edit = props.editData
  if (edit && edit.plannerId != null && edit.plannerName) {
    list.push({ id: edit.plannerId, realName: edit.plannerName })
  }
  if (projectCreator.value && !list.some((o) => o.id === projectCreator.value!.userId)) {
    list.push({ id: projectCreator.value.userId, realName: projectCreator.value.realName })
  }
  allMembers.value
    .filter((m) => m.crafts.includes('PLANNER'))
    .forEach((m) => {
      if (!list.some((o) => o.id === m.userId)) list.push({ id: m.userId, realName: m.realName })
    })
  return list
})

// 某工种行可选成员：成员选项源中兼任该工种的人
const membersOfCraft = (craft: string) => allMembers.value.filter((m) => m.crafts.includes(craft))

// 拉取项目成员（编辑模式回显用，不重置已回显数据）
const loadMembers = async (projectId: number) => {
  membersLoading.value = true
  try {
    const res = await getProject(projectId)
    members.value = res.data.data.members
    projectCreator.value = {
      userId: res.data.data.creatorId,
      realName: res.data.data.creatorName
    }
  } finally {
    membersLoading.value = false
  }
}

// 新增模式切换项目：成员随项目变化，重置策划与参与人后重新拉取
const handleProjectChange = (projectId: number) => {
  form.plannerId = undefined
  participantRows.value = []
  loadMembers(projectId).then(() => {
    // 策划默认当前用户（若在候选列表中）
    const me = userStore.userInfo?.userId
    if (me && plannerOptions.value.some((o) => o.id === me)) {
      form.plannerId = me
    }
  })
}

// 弹窗打开：初始化表单（编辑回显 / 新增重置）
const initForm = () => {
  const edit = props.editData
  if (edit) {
    form.projectId = edit.projectId
    form.title = edit.title
    form.priority = edit.priority
    form.plannerId = edit.plannerId ?? undefined
    descriptionHtml.value = edit.description || ''
    attachments.value = edit.attachments.map((a) => ({ ...a }))
    // 参与人按工种分组回显
    const grouped = new Map<string, number[]>()
    edit.participants.forEach((p) => {
      grouped.set(p.craft, [...(grouped.get(p.craft) || []), p.userId])
    })
    participantRows.value = [...grouped.entries()].map(([craft, userIds]) => ({ craft, userIds }))
    loadMembers(edit.projectId)
  } else {
    form.projectId = undefined
    form.title = ''
    form.priority = 2
    form.plannerId = undefined
    descriptionHtml.value = ''
    attachments.value = []
    participantRows.value = []
    members.value = []
    projectCreator.value = null
  }
  nextTick(() => formRef.value?.clearValidate())
}

// 监听弹窗显隐：打开初始化；关闭销毁编辑器实例，避免重开产生多实例
watch(
  () => props.modelValue,
  (v) => {
    if (v) {
      initForm()
    } else {
      editorRef.value?.destroy()
    }
  }
)

// 参与人行：追加 / 删除
const addRow = () => {
  participantRows.value.push({ craft: '', userIds: [] })
}

const removeRow = (index: number) => {
  participantRows.value.splice(index, 1)
}

// 行内切换工种：清空已选成员（候选成员随工种变化，避免旧成员错挂新工种）
const handleRowCraftChange = (index: number) => {
  participantRows.value[index].userIds = []
}

// ===== wangEditor 富文本 =====
// 编辑器实例（shallowRef 避免深层响应式包裹）
const editorRef = shallowRef<IDomEditor>()

// 工具栏配置：默认菜单
const toolbarConfig: Partial<IToolbarConfig> = {}

// 编辑器配置：图片自定义上传，走后端 /files/upload
const editorConfig: Partial<IEditorConfig> = {
  placeholder: '请输入需求描述（支持图文混排）…',
  MENU_CONF: {
    uploadImage: {
      // 自定义上传：拿到 url 后插入编辑器
      customUpload: async (file: File, insertFn: (url: string, alt: string, href: string) => void) => {
        try {
          const res = await uploadFile(file)
          insertFn(res.data.data.url, file.name, res.data.data.url)
        } catch {
          // 上传失败：拦截器已统一弹错，此处无需处理
        }
      }
    }
  }
}

// 编辑器创建完成：保存实例
const handleEditorCreated = (editor: IDomEditor) => {
  editorRef.value = editor
}

// 编辑器销毁回调：清空实例引用
const handleEditorDestroyed = () => {
  editorRef.value = undefined
}

// 组件卸载兜底销毁（弹窗打开时离开页面等场景）
onBeforeUnmount(() => {
  editorRef.value?.destroy()
})

// ===== 附件上传 =====
// 文档类型白名单：pdf/doc/docx/xlsx/zip
const DOC_EXTS = ['.pdf', '.doc', '.docx', '.xlsx', '.zip']

// 附件上传中
const uploading = ref(false)

// 上传前校验：类型白名单 + 50MB 上限
const beforeUpload = (file: File) => {
  const ext = file.name.slice(file.name.lastIndexOf('.')).toLowerCase()
  if (!DOC_EXTS.includes(ext)) {
    ElMessage.error('仅支持 pdf / doc / docx / xlsx / zip 格式文档')
    return false
  }
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.error('文档大小不超过 50MB')
    return false
  }
  return true
}

// 自定义上传请求：调后端接口拿 url 后存入本地数组（不走 el-upload 默认 action）
const handleUploadRequest = async (options: UploadRequestOptions) => {
  uploading.value = true
  try {
    const res = await uploadFile(options.file)
    // 上传区仅接受文档类型（beforeUpload 已校验），此处断言为需求附件类型
    attachments.value.push(res.data.data as ReqAttachment)
  } catch {
    // 上传失败：拦截器已统一弹错，此处无需处理
  } finally {
    uploading.value = false
  }
}

// 移除已上传附件
const removeAttachment = (index: number) => {
  attachments.value.splice(index, 1)
}

// 文件大小格式化
const formatSize = (size: number) => {
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  if (size >= 1024) return `${(size / 1024).toFixed(0)} KB`
  return `${size} B`
}

// ===== 提交 =====
// 提交新增 / 编辑
const handleSubmit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  // 参与人行：剔除未选人员的空行，展平为 userId × craft 列表（同人同工种去重）
  const seen = new Set<string>()
  const participants = participantRows.value
    .filter((row) => row.craft && row.userIds.length)
    .flatMap((row) => row.userIds.map((userId) => ({ userId, craft: row.craft })))
    .filter((p) => {
      const key = `${p.craft}-${p.userId}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
  // 附件提交时仅传契约字段（不带 id）
  const submitAttachments = attachments.value.map(({ url, fileName, fileType, fileSize }) => ({
    url,
    fileName,
    fileType,
    fileSize
  }))
  submitting.value = true
  try {
    if (isEdit.value && props.editData) {
      await updateRequirement(props.editData.id, {
        title: form.title.trim(),
        priority: form.priority,
        description: descriptionHtml.value || undefined,
        plannerId: form.plannerId ?? null,
        participants,
        attachments: submitAttachments,
        updatedAt: props.editData.updatedAt
      })
      ElMessage.success('修改成功')
      visible.value = false
      emit('success')
    } else {
      await createRequirement({
        projectId: form.projectId!,
        title: form.title.trim(),
        priority: form.priority,
        description: descriptionHtml.value || undefined,
        plannerId: form.plannerId,
        participants,
        attachments: submitAttachments
      })
      ElMessage.success('需求创建成功')
      visible.value = false
      emit('success')
    }
  } catch (error) {
    // 乐观锁冲突（后端 code=409）：错误信息已由拦截器弹出，关闭弹窗并通知父页面刷新详情
    if (error instanceof Error && error.message.includes('已被他人修改')) {
      visible.value = false
      emit('conflict')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <!-- destroy-on-close：内容（含编辑器）随弹窗关闭销毁，重开时重新创建，避免多实例 -->
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑需求' : '新增需求'"
    width="860px"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
      <el-form-item label="所属项目" prop="projectId">
        <!-- 新增模式：可选担任 PM/策划的项目；编辑模式：项目不可改 -->
        <el-select
          v-if="!isEdit"
          v-model="form.projectId"
          placeholder="请选择项目"
          filterable
          style="width: 100%"
          @change="handleProjectChange"
        >
          <el-option v-for="p in creatableProjects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-input v-else :model-value="editData?.projectName" disabled />
      </el-form-item>
      <el-form-item label="需求名称" prop="title">
        <el-input v-model="form.title" placeholder="请输入需求名称" maxlength="100" show-word-limit />
      </el-form-item>
      <el-form-item label="优先级">
        <el-radio-group v-model="form.priority">
          <el-radio v-for="p in PRIORITY_OPTIONS" :key="p.value" :value="p.value">{{ p.label }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="策划">
        <el-select
          v-model="form.plannerId"
          placeholder="默认本人，可选其他策划 / PM"
          clearable
          :loading="membersLoading"
          style="width: 100%"
        >
          <el-option v-for="o in plannerOptions" :key="o.id" :label="o.realName" :value="o.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="需求描述">
        <div class="editor-wrap">
          <Toolbar class="editor-toolbar" :editor="editorRef" :default-config="toolbarConfig" mode="default" />
          <Editor
            v-model="descriptionHtml"
            class="editor-content"
            :default-config="editorConfig"
            mode="default"
            @onCreated="handleEditorCreated"
            @onDestroyed="handleEditorDestroyed"
          />
        </div>
      </el-form-item>
      <el-form-item label="附件">
        <div class="upload-area">
          <!-- 拖拽上传文档：拿到 url 后存本地数组；图片走富文本内嵌，无需单独图片区 -->
          <el-upload
            drag
            action="#"
            accept=".pdf,.doc,.docx,.xlsx,.zip"
            :show-file-list="false"
            :before-upload="beforeUpload"
            :http-request="handleUploadRequest"
          >
            <el-icon class="upload-icon"><UploadFilled /></el-icon>
            <div class="upload-text">拖拽文档到此处，或点击上传</div>
            <div class="upload-tip">支持 pdf / doc / docx / xlsx / zip，单个不超过 50MB</div>
          </el-upload>
          <div v-if="uploading" class="upload-loading">附件上传中…</div>
          <!-- 已上传附件列表 -->
          <div v-for="(f, i) in attachments" :key="f.url" class="att-item">
            <el-icon><Document /></el-icon>
            <span class="att-name" :title="f.fileName">{{ f.fileName }}</span>
            <span class="att-size">{{ formatSize(f.fileSize) }}</span>
            <el-icon class="att-remove" @click="removeAttachment(i)"><Close /></el-icon>
          </div>
        </div>
      </el-form-item>
      <el-form-item label="参与人">
        <div class="participants">
          <!-- 工种动态行：同一工种可多人，不同工种可同一人 -->
          <div v-for="(row, index) in participantRows" :key="index" class="participant-row">
            <el-select
              v-model="row.craft"
              placeholder="选择工种"
              style="width: 150px"
              @change="handleRowCraftChange(index)"
            >
              <el-option v-for="c in CRAFT_OPTIONS" :key="c.value" :label="c.label" :value="c.value" />
            </el-select>
            <el-select
              v-model="row.userIds"
              placeholder="选择该工种成员（可多选）"
              multiple
              collapse-tags
              filterable
              :loading="membersLoading"
              :disabled="!row.craft"
              style="flex: 1"
            >
              <el-option
                v-for="m in membersOfCraft(row.craft)"
                :key="m.userId"
                :label="m.realName"
                :value="m.userId"
              />
            </el-select>
            <el-button link type="danger" @click="removeRow(index)">删除</el-button>
          </div>
          <el-button v-if="form.projectId || isEdit" link type="primary" @click="addRow">
            + 添加参与人
          </el-button>
          <span v-else class="participants-tip">选择项目后可添加参与人</span>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 富文本容器：工具栏 + 编辑区 */
.editor-wrap {
  width: 100%;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}

/* 工具栏底边框 */
.editor-toolbar {
  border-bottom: 1px solid #dcdfe6;
}

/* 编辑区高度 300px */
.editor-content {
  height: 300px;
  overflow-y: hidden;
}

/* 附件上传区域：纵向排列拖拽区与文件列表 */
.upload-area {
  width: 100%;
}

/* 拖拽上传区内部文案 */
.upload-icon {
  margin-bottom: 8px;
  font-size: 40px;
  color: #c0c4cc;
}

.upload-text {
  font-size: 14px;
  color: #606266;
}

.upload-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

/* 上传中提示 */
.upload-loading {
  margin-top: 8px;
  font-size: 13px;
  color: #409eff;
}

/* 已上传附件行 */
.att-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  margin-top: 8px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #f8f9fb;
  font-size: 13px;
  color: #606266;
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

/* 删除附件按钮 */
.att-remove {
  flex-shrink: 0;
  cursor: pointer;
  color: #909399;
}

.att-remove:hover {
  color: #f56c6c;
}

/* 参与人区：纵向排列动态行 */
.participants {
  width: 100%;
}

/* 单行参与人：工种下拉 + 成员多选 + 删除 */
.participant-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin-bottom: 8px;
}

/* 未选项目时的提示 */
.participants-tip {
  font-size: 13px;
  color: #909399;
}
</style>
