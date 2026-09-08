<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  listUsers,
  createUser,
  updateUser,
  resetPassword,
  updateUserStatus,
  type UserItem
} from '@/api/user'

// 查询条件与分页参数（status 为空串表示全部）
const query = reactive({
  keyword: '',
  status: '' as '' | 0 | 1,
  page: 1,
  size: 10
})
// 列表数据
const list = ref<UserItem[]>([])
// 总条数
const total = ref(0)
// 表格 loading
const loading = ref(false)

// 查询人员列表（keyword / status 为空时不传对应参数）
const fetchList = async () => {
  loading.value = true
  try {
    const params: { page: number; size: number; keyword?: string; status?: number } = {
      page: query.page,
      size: query.size
    }
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    if (query.status !== '') params.status = query.status
    const res = await listUsers(params)
    list.value = res.data.data.records
    total.value = res.data.data.total
  } finally {
    loading.value = false
  }
}

// 搜索（回车 / 搜索按钮 / 清空 / 切换状态）：回到第一页查询
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

// 停用的行附加淡灰样式类
const rowClassName = ({ row }: { row: UserItem }) => (row.status === 0 ? 'disabled-row' : '')

// ===== 新增 / 编辑弹窗 =====
const dialogVisible = ref(false)
// 是否编辑模式（编辑仅可改姓名）
const isEdit = ref(false)
const formRef = ref<FormInstance>()
// 提交按钮 loading
const submitting = ref(false)
// 弹窗表单数据
const form = reactive({
  id: 0,
  username: '',
  realName: '',
  password: ''
})

// 表单校验规则
const formRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9]{3,30}$/, message: '用户名为 3-30 位字母或数字', trigger: 'blur' }
  ],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }]
}

// 打开新增弹窗
const openCreate = () => {
  isEdit.value = false
  form.id = 0
  form.username = ''
  form.realName = ''
  form.password = ''
  dialogVisible.value = true
  // 等表单挂载后清除上一次的校验提示
  nextTick(() => formRef.value?.clearValidate())
}

// 打开编辑弹窗（回填当前行数据，用户名不可改）
const openEdit = (row: UserItem) => {
  isEdit.value = true
  form.id = row.id
  form.username = row.username
  form.realName = row.realName
  form.password = ''
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

// 提交新增 / 编辑
const handleSubmit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (isEdit.value) {
      // 编辑：仅修改姓名，刷新当前页
      await updateUser(form.id, { realName: form.realName.trim() })
      ElMessage.success('修改成功')
      fetchList()
    } else {
      // 新增：密码留空则后端使用默认密码，回到第一页展示新数据
      await createUser({
        username: form.username.trim(),
        realName: form.realName.trim(),
        password: form.password.trim() || undefined
      })
      ElMessage.success('新增成功')
      query.page = 1
      fetchList()
    }
    dialogVisible.value = false
  } finally {
    submitting.value = false
  }
}

// 重置密码：确认框可输入自定义新密码，留空则重置为默认密码 Pm@123456
const handleResetPwd = async (row: UserItem) => {
  try {
    const { value } = await ElMessageBox.prompt(
      `即将重置「${row.realName}」的密码，重置后该账号下次登录需强制改密`,
      '重置密码',
      {
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        inputType: 'password',
        inputPlaceholder: '自定义新密码（留空则重置为 Pm@123456）'
      }
    )
    await resetPassword(row.id, { password: (value || '').trim() || undefined })
    ElMessage.success('重置成功，新密码已生效')
    fetchList()
  } catch {
    // 用户取消确认或接口失败（失败信息已由拦截器统一提示）
  }
}

// 启用 / 停用（停用自己等场景后端会拒绝，错误信息由拦截器统一提示）
const handleToggleStatus = async (row: UserItem) => {
  const target = row.status === 1 ? 0 : 1
  const actionText = target === 1 ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(`确认${actionText}账号「${row.realName}」？`, '提示', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    await updateUserStatus(row.id, { status: target })
    ElMessage.success(`${actionText}成功`)
    fetchList()
  } catch {
    // 用户取消确认或接口失败（失败信息已由拦截器统一提示）
  }
}

// 页面挂载后加载列表
onMounted(fetchList)
</script>

<template>
  <div class="admin-users-page">
    <!-- 顶部：搜索框 + 状态筛选 + 新增按钮 -->
    <div class="page-toolbar">
      <el-input
        v-model="query.keyword"
        class="toolbar-search"
        placeholder="搜索姓名 / 用户名"
        clearable
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #append>
          <el-button @click="handleSearch">搜索</el-button>
        </template>
      </el-input>
      <el-select v-model="query.status" class="toolbar-status" @change="handleSearch">
        <el-option label="全部" value="" />
        <el-option label="正常" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button type="primary" class="toolbar-add" @click="openCreate">新增人员</el-button>
    </div>

    <!-- 人员表格 + 分页 -->
    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="list" :row-class-name="rowClassName">
        <el-table-column prop="realName" label="姓名" min-width="120" />
        <el-table-column prop="username" label="用户名" min-width="150" />
        <el-table-column label="角色" min-width="100">
          <template #default="{ row }">
            {{ row.isAdmin === 1 ? '管理员' : '普通成员' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" @click="handleResetPwd(row)">重置密码</el-button>
            <el-button
              link
              :type="row.status === 1 ? 'danger' : 'success'"
              @click="handleToggleStatus(row)"
            >
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
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
    </el-card>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑人员' : '新增人员'" width="460px">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            :disabled="isEdit"
            placeholder="3-30 位字母或数字"
            maxlength="30"
          />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="初始密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="留空则默认为 Pm@123456"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页面：纵向排布工具栏与表格卡片 */
.admin-users-page {
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

/* 搜索框宽度 */
.toolbar-search {
  width: 300px;
}

/* 状态下拉宽度 */
.toolbar-status {
  width: 110px;
}

/* 新增按钮推到最右 */
.toolbar-add {
  margin-left: auto;
}

/* 停用行整体淡灰 */
:deep(.disabled-row) .cell {
  color: #a8abb2;
}

:deep(.disabled-row) .el-tag {
  opacity: 0.6;
}

/* 分页：右对齐 */
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
