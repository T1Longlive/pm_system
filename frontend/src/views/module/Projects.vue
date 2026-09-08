<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { listProjects, createProject, type ProjectItem } from '@/api/project'
import { useUserStore } from '@/stores/user'
import { craftLabel } from '@/constants/craft'

const router = useRouter()
const userStore = useUserStore()

// 是否管理员（仅管理员可创建项目）
const isAdmin = computed(() => userStore.userInfo?.isAdmin === 1)

// 查询条件与分页参数（卡片网格按 12 的倍数展示）
const query = reactive({
  keyword: '',
  page: 1,
  size: 12
})
// 列表数据
const list = ref<ProjectItem[]>([])
// 总条数
const total = ref(0)
// 列表 loading
const loading = ref(false)

// 查询项目列表（keyword 为空时不传）
const fetchList = async () => {
  loading.value = true
  try {
    const params: { page: number; size: number; keyword?: string } = {
      page: query.page,
      size: query.size
    }
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    const res = await listProjects(params)
    list.value = res.data.data.records
    total.value = res.data.data.total
  } finally {
    loading.value = false
  }
}

// 搜索（回车 / 搜索按钮 / 清空）：回到第一页查询
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

// 点击卡片进入项目详情
const goDetail = (id: number) => {
  router.push(`/projects/${id}`)
}

// 角色展示文案：PM 显示项目经理，其余显示工种名
const roleText = (role: string) => (role === 'PM' ? '项目经理' : craftLabel(role))

// ===== 创建项目弹窗 =====
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
// 提交按钮 loading
const submitting = ref(false)
// 弹窗表单数据
const form = reactive({
  name: '',
  code: '',
  description: ''
})

// 表单校验规则
const formRules: FormRules = {
  name: [
    { required: true, message: '请输入项目名称', trigger: 'blur' },
    { max: 50, message: '项目名称不超过 50 个字', trigger: 'blur' }
  ],
  code: [
    { required: true, message: '请输入项目缩写', trigger: 'blur' },
    { pattern: /^[A-Z]{2,6}$/, message: '缩写为 2-6 位大写字母', trigger: 'blur' }
  ]
}

// 打开创建弹窗
const openCreate = () => {
  form.name = ''
  form.code = ''
  form.description = ''
  dialogVisible.value = true
  // 等表单挂载后清除上一次的校验提示
  nextTick(() => formRef.value?.clearValidate())
}

// 提交创建
const handleSubmit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await createProject({
      name: form.name.trim(),
      code: form.code.trim(),
      description: form.description.trim() || undefined
    })
    ElMessage.success('项目创建成功')
    dialogVisible.value = false
    // 回到第一页展示新项目
    query.page = 1
    fetchList()
  } finally {
    submitting.value = false
  }
}

// 页面挂载后加载列表
onMounted(fetchList)
</script>

<template>
  <div class="projects-page">
    <!-- 顶部：搜索框 + 创建项目按钮（仅管理员） -->
    <div class="page-toolbar">
      <el-input
        v-model="query.keyword"
        class="toolbar-search"
        placeholder="搜索项目名称"
        clearable
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #append>
          <el-button @click="handleSearch">搜索</el-button>
        </template>
      </el-input>
      <el-button v-if="isAdmin" type="primary" class="toolbar-add" @click="openCreate">
        创建项目
      </el-button>
    </div>

    <!-- 项目卡片网格 -->
    <div v-loading="loading" class="card-grid">
      <template v-if="list.length">
        <el-card
          v-for="item in list"
          :key="item.id"
          shadow="hover"
          class="project-card"
          @click="goDetail(item.id)"
        >
          <!-- 名称 + 缩写 -->
          <div class="card-head">
            <span class="card-name" :title="item.name">{{ item.name }}</span>
            <el-tag size="small">{{ item.code }}</el-tag>
          </div>
          <!-- 描述（最多两行，超出截断） -->
          <div class="card-desc" :title="item.description">
            {{ item.description || '暂无描述' }}
          </div>
          <!-- 创建人 + 成员数 -->
          <div class="card-meta">
            <span>创建人：{{ item.creatorName }}</span>
            <span>{{ item.memberCount }} 名成员</span>
          </div>
          <!-- 我的角色：PM 用品牌色标签，工种用普通标签 -->
          <div class="card-roles">
            <el-tag
              v-for="role in item.myRole"
              :key="role"
              size="small"
              :type="role === 'PM' ? 'primary' : 'info'"
              :effect="role === 'PM' ? 'dark' : 'light'"
            >
              {{ roleText(role) }}
            </el-tag>
          </div>
        </el-card>
      </template>
      <!-- 空状态 -->
      <el-empty v-else-if="!loading" description="暂无项目" class="grid-empty" />
    </div>

    <!-- 分页 -->
    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[12, 24, 48]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </div>

    <!-- 创建项目弹窗 -->
    <el-dialog v-model="dialogVisible" title="创建项目" width="480px">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="80px">
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入项目名称" maxlength="50" show-word-limit />
        </el-form-item>
        <el-form-item label="项目缩写" prop="code">
          <el-input v-model="form.code" placeholder="2-6 位大写字母，如 ACE" maxlength="6" />
        </el-form-item>
        <el-form-item label="项目描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            placeholder="请输入项目描述（选填）"
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
/* 页面：纵向排布工具栏、卡片网格、分页 */
.projects-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 顶部工具栏：白底圆角，创建按钮靠右 */
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

/* 创建按钮推到最右 */
.toolbar-add {
  margin-left: auto;
}

/* 卡片网格：自适应列数 */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
  min-height: 200px;
}

/* 空状态居中占满网格 */
.grid-empty {
  grid-column: 1 / -1;
}

/* 卡片可点击 */
.project-card {
  cursor: pointer;
  transition: transform 0.2s;
}

/* 卡片悬停轻微上浮 */
.project-card:hover {
  transform: translateY(-2px);
}

/* 名称 + 缩写：单行布局，名称超长省略 */
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-name {
  overflow: hidden;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 描述：最多两行截断，固定高度保证卡片对齐 */
.card-desc {
  display: -webkit-box;
  height: 40px;
  margin-top: 8px;
  overflow: hidden;
  font-size: 13px;
  line-height: 20px;
  color: #909399;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

/* 创建人 + 成员数：两端分布 */
.card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  font-size: 13px;
  color: #606266;
}

/* 我的角色标签区 */
.card-roles {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #f0f2f5;
}

/* 分页：右对齐，白底圆角 */
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding: 14px 16px;
  background: #fff;
  border-radius: 4px;
}
</style>
