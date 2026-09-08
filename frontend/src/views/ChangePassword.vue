<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { changePassword } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

// 表单实例引用
const formRef = ref<FormInstance>()
// 提交按钮 loading 状态
const loading = ref(false)

// 改密表单数据
const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

// 表单校验规则
const pwdRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '新密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== pwdForm.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

// 卡片副标题：显示当前账号
const accountTip = computed(() => `当前账号：${userStore.userInfo?.realName || userStore.userInfo?.username || ''}`)

// 提交改密：成功后清除强制改密标记并进入工作台
const handleSubmit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    userStore.markPwdChanged()
    ElMessage.success('密码修改成功')
    router.push('/dashboard')
  } catch (error) {
    // 优先展示后端返回的错误信息（原密码错误等）
    const msg = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '密码修改失败')
  } finally {
    loading.value = false
  }
}

// 暂不修改，直接退出登录
const handleLogout = () => {
  userStore.logout()
  router.push('/login')
}
</script>

<template>
  <div class="change-pwd-page">
    <!-- 居中改密卡片 -->
    <el-card class="pwd-card">
      <template #header>
        <div class="pwd-title">修改密码</div>
        <div class="pwd-sub">{{ accountTip }}</div>
      </template>
      <el-alert
        class="pwd-alert"
        type="warning"
        :closable="false"
        show-icon
        title="首次登录或密码被重置后，需要先修改密码才能继续使用系统"
      />
      <el-form ref="formRef" :model="pwdForm" :rules="pwdRules" size="large">
        <!-- 原密码 -->
        <el-form-item prop="oldPassword">
          <el-input
            v-model="pwdForm.oldPassword"
            type="password"
            placeholder="原密码"
            show-password
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <!-- 新密码 -->
        <el-form-item prop="newPassword">
          <el-input
            v-model="pwdForm.newPassword"
            type="password"
            placeholder="新密码（至少 6 位）"
            show-password
          >
            <template #prefix>
              <el-icon><Key /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <!-- 确认新密码 -->
        <el-form-item prop="confirmPassword">
          <el-input
            v-model="pwdForm.confirmPassword"
            type="password"
            placeholder="确认新密码"
            show-password
            @keyup.enter="handleSubmit"
          >
            <template #prefix>
              <el-icon><Key /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <!-- 提交按钮 -->
        <el-form-item class="pwd-btn-item">
          <el-button
            type="primary"
            class="pwd-btn"
            :loading="loading"
            @click="handleSubmit"
          >
            修改密码
          </el-button>
        </el-form-item>
        <!-- 暂不修改：退出登录 -->
        <el-button link type="info" class="pwd-logout" @click="handleLogout">
          暂不修改，退出登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
/* 改密页：全屏居中（与登录页同风格） */
.change-pwd-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background: linear-gradient(135deg, #1f3b73 0%, #2f5cad 100%);
}

/* 卡片宽度 */
.pwd-card {
  width: 400px;
}

/* 卡片标题居中 */
.pwd-title {
  font-size: 18px;
  font-weight: 600;
  text-align: center;
}

/* 副标题：当前账号 */
.pwd-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  text-align: center;
}

/* 提示条与表单间距 */
.pwd-alert {
  margin-bottom: 18px;
}

/* 提交按钮行：去除底部多余间距 */
.pwd-btn-item {
  margin-bottom: 8px;
}

/* 提交按钮铺满 */
.pwd-btn {
  width: 100%;
}

/* 退出登录链接居中 */
.pwd-logout {
  display: block;
  margin: 0 auto;
}
</style>
