<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

// 表单实例引用
const formRef = ref<FormInstance>()
// 登录按钮 loading 状态
const loading = ref(false)

// 登录表单数据
const loginForm = reactive({
  username: '',
  password: ''
})

// 表单校验规则
const loginRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

// 登录提交：校验通过后调登录接口，成功保存 token；需强制改密则进入改密页，否则跳转工作台
const handleLogin = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.login(loginForm)
    if (userStore.forceChangePwd) {
      ElMessage.warning('首次登录或密码已被重置，请先修改密码')
      router.push('/change-password')
    } else {
      ElMessage.success('登录成功')
      router.push('/dashboard')
    }
  } catch (error) {
    // 优先展示后端返回的错误信息（账号不存在 / 密码错误 / 账号已停用）
    const msg = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '登录失败，请检查用户名或密码')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 居中登录卡片 -->
    <el-card class="login-card">
      <template #header>
        <div class="login-title">项目管理系统</div>
      </template>
      <el-form ref="formRef" :model="loginForm" :rules="loginRules" size="large">
        <!-- 用户名 -->
        <el-form-item prop="username">
          <el-input v-model="loginForm.username" placeholder="用户名" clearable>
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <!-- 密码 -->
        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="密码"
            show-password
            @keyup.enter="handleLogin"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <!-- 登录按钮 -->
        <el-form-item class="login-btn-item">
          <el-button
            type="primary"
            class="login-btn"
            :loading="loading"
            @click="handleLogin"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
/* 登录页：全屏居中 */
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background: linear-gradient(135deg, #1f3b73 0%, #2f5cad 100%);
}

/* 登录卡片宽度 */
.login-card {
  width: 400px;
}

/* 卡片标题居中 */
.login-title {
  font-size: 18px;
  font-weight: 600;
  text-align: center;
}

/* 登录按钮行：去除底部多余间距 */
.login-btn-item {
  margin-bottom: 0;
}

/* 登录按钮铺满 */
.login-btn {
  width: 100%;
}
</style>
