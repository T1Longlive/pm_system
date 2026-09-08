<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  getProject,
  updateProject,
  addProjectMember,
  removeProjectMember,
  removeProjectMemberCraft,
  type ProjectDetailData,
  type ProjectMember
} from '@/api/project'
import { userOptions, type UserOption } from '@/api/user'
import { CRAFT_OPTIONS, craftLabel } from '@/constants/craft'

const route = useRoute()
const router = useRouter()

// 路由参数中的项目 id
const projectId = Number(route.params.id)

// 项目详情数据
const detail = ref<ProjectDetailData | null>(null)
// 页面 loading
const loading = ref(false)

// 当前用户是否项目经理（编辑项目 / 成员管理操作权限）
const isPM = computed(() => detail.value?.myRole?.includes('PM') ?? false)

// 查询项目详情
const fetchDetail = async () => {
  loading.value = true
  try {
    const res = await getProject(projectId)
    detail.value = res.data.data
  } finally {
    loading.value = false
  }
}

// 返回项目列表
const goBack = () => {
  router.push('/projects')
}

// ===== 编辑项目弹窗（仅 PM，缩写创建后不可修改） =====
const editVisible = ref(false)
const editFormRef = ref<FormInstance>()
// 提交按钮 loading
const editSubmitting = ref(false)
// 编辑表单数据
const editForm = reactive({
  name: '',
  description: ''
})

// 编辑表单校验规则
const editRules: FormRules = {
  name: [
    { required: true, message: '请输入项目名称', trigger: 'blur' },
    { max: 50, message: '项目名称不超过 50 个字', trigger: 'blur' }
  ]
}

// 打开编辑弹窗（回填当前项目数据）
const openEdit = () => {
  if (!detail.value) return
  editForm.name = detail.value.name
  editForm.description = detail.value.description || ''
  editVisible.value = true
  // 等表单挂载后清除上一次的校验提示
  nextTick(() => editFormRef.value?.clearValidate())
}

// 提交编辑
const handleEditSubmit = async () => {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  editSubmitting.value = true
  try {
    await updateProject(projectId, {
      name: editForm.name.trim(),
      description: editForm.description.trim() || undefined
    })
    ElMessage.success('修改成功')
    editVisible.value = false
    fetchDetail()
  } finally {
    editSubmitting.value = false
  }
}

// ===== 添加成员弹窗（仅 PM） =====
const addVisible = ref(false)
const addFormRef = ref<FormInstance>()
// 提交按钮 loading
const addSubmitting = ref(false)
// 人员下拉选项
const userOptionList = ref<UserOption[]>([])
// 人员下拉加载状态
const optionLoading = ref(false)
// 添加成员表单数据
const addForm = reactive<{ userId?: number; crafts: string[] }>({
  userId: undefined,
  crafts: []
})

// 添加成员表单校验规则
const addRules: FormRules = {
  userId: [{ required: true, message: '请选择成员', trigger: 'change' }],
  crafts: [{ type: 'array', required: true, message: '请至少选择一个工种', trigger: 'change' }]
}

// 打开添加成员弹窗：懒加载人员下拉选项
const openAddMember = () => {
  addForm.userId = undefined
  addForm.crafts = []
  addVisible.value = true
  nextTick(() => addFormRef.value?.clearValidate())
  if (userOptionList.value.length === 0) {
    optionLoading.value = true
    userOptions()
      .then((res) => {
        userOptionList.value = res.data.data
      })
      .finally(() => {
        optionLoading.value = false
      })
  }
}

// 提交添加成员（同一用户可一次分配多个工种）
const handleAddMember = async () => {
  const valid = await addFormRef.value?.validate().catch(() => false)
  if (!valid) return
  addSubmitting.value = true
  try {
    await addProjectMember(projectId, {
      userId: addForm.userId!,
      crafts: addForm.crafts
    })
    ElMessage.success('添加成功')
    addVisible.value = false
    fetchDetail()
  } finally {
    addSubmitting.value = false
  }
}

// 移除成员的单个工种（工种标签关闭，二次确认）
const handleRemoveCraft = async (member: ProjectMember, craft: string) => {
  try {
    await ElMessageBox.confirm(
      `确认移除「${member.realName}」的「${craftLabel(craft)}」工种？`,
      '提示',
      {
        type: 'warning',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      }
    )
    await removeProjectMemberCraft(projectId, member.userId, craft)
    ElMessage.success('已移除工种')
    fetchDetail()
  } catch {
    // 用户取消确认或接口失败（失败信息已由拦截器统一提示）
  }
}

// 移除整个成员（二次确认，其全部工种一并移除）
const handleRemoveMember = async (member: ProjectMember) => {
  try {
    await ElMessageBox.confirm(
      `确认将「${member.realName}」移出项目？其全部工种将一并移除`,
      '提示',
      {
        type: 'warning',
        confirmButtonText: '确定',
        cancelButtonText: '取消'
      }
    )
    await removeProjectMember(projectId, member.userId)
    ElMessage.success('已移除成员')
    fetchDetail()
  } catch {
    // 用户取消确认或接口失败（失败信息已由拦截器统一提示）
  }
}

// 页面挂载后加载项目详情
onMounted(fetchDetail)
</script>

<template>
  <div v-loading="loading" class="project-detail-page">
    <!-- 顶部：返回按钮 + 面包屑 -->
    <div class="detail-toolbar">
      <el-button @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
        返回
      </el-button>
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ path: '/projects' }">项目管理</el-breadcrumb-item>
        <el-breadcrumb-item>项目详情</el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <template v-if="detail">
      <!-- 项目基本信息 -->
      <el-card shadow="never" class="info-card">
        <template #header>
          <div class="card-header">
            <span>基本信息</span>
            <!-- 编辑按钮：仅项目经理可见 -->
            <el-button v-if="isPM" type="primary" size="small" @click="openEdit">编辑</el-button>
          </div>
        </template>
        <el-descriptions :column="4">
          <el-descriptions-item label="项目名称">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="项目缩写">
            <el-tag size="small">{{ detail.code }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建人">{{ detail.creatorName }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="项目描述" :span="4">
            {{ detail.description || '暂无描述' }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 成员管理（PM 可操作，非 PM 只读展示） -->
      <el-card shadow="never" class="member-card">
        <template #header>
          <div class="card-header">
            <span>成员管理</span>
            <!-- 添加成员按钮：仅项目经理可见 -->
            <el-button v-if="isPM" type="primary" size="small" @click="openAddMember">
              添加成员
            </el-button>
          </div>
        </template>
        <!-- 成员表格 -->
        <el-table v-if="detail.members.length" :data="detail.members">
          <el-table-column prop="realName" label="姓名" min-width="120" />
          <el-table-column prop="username" label="用户名" min-width="150" />
          <el-table-column label="工种" min-width="280">
            <template #default="{ row }">
              <!-- 工种标签：PM 可关闭标签移除单工种 -->
              <el-tag
                v-for="craft in row.crafts"
                :key="craft"
                class="craft-tag"
                :closable="isPM"
                @close="handleRemoveCraft(row, craft)"
              >
                {{ craftLabel(craft) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="isPM" label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="danger" @click="handleRemoveMember(row)">移除成员</el-button>
            </template>
          </el-table-column>
        </el-table>
        <!-- 空状态：项目无成员 -->
        <el-empty v-else description="暂无成员" />
      </el-card>
    </template>

    <!-- 编辑项目弹窗（同创建表单，去掉缩写字段） -->
    <el-dialog v-model="editVisible" title="编辑项目" width="480px">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="80px">
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="editForm.name" placeholder="请输入项目名称" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="项目描述" prop="description">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="4"
            placeholder="请输入项目描述（选填）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSubmitting" @click="handleEditSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 添加成员弹窗 -->
    <el-dialog v-model="addVisible" title="添加成员" width="480px">
      <el-form ref="addFormRef" :model="addForm" :rules="addRules" label-width="80px">
        <el-form-item label="成员" prop="userId">
          <el-select
            v-model="addForm.userId"
            placeholder="请选择成员"
            filterable
            :loading="optionLoading"
            style="width: 100%"
          >
            <el-option
              v-for="opt in userOptionList"
              :key="opt.id"
              :label="`${opt.realName}（${opt.username}）`"
              :value="opt.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="工种" prop="crafts">
          <el-checkbox-group v-model="addForm.crafts">
            <el-checkbox v-for="c in CRAFT_OPTIONS" :key="c.value" :value="c.value">
              {{ c.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="addSubmitting" @click="handleAddMember">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页面：纵向排布工具栏与信息卡片 */
.project-detail-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 300px;
}

/* 顶部：返回按钮 + 面包屑 */
.detail-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 16px;
  background: #fff;
  border-radius: 4px;
}

/* 卡片头部：标题 + 右侧操作按钮 */
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 工种标签间距 */
.craft-tag {
  margin-right: 6px;
}
</style>
