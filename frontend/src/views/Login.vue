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
    <!-- 左侧品牌区：渐变 + 几何装饰，承担视觉重心 -->
    <aside class="brand">
      <div class="brand-grid" aria-hidden="true"></div>
      <div class="brand-orb brand-orb--a" aria-hidden="true"></div>
      <div class="brand-orb brand-orb--b" aria-hidden="true"></div>
      <div class="brand-ring" aria-hidden="true"></div>

      <div class="brand-inner">
        <div class="brand-logo">
          <span class="brand-logo__mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none">
              <path d="M4 7.5 12 3l8 4.5v9L12 21l-8-4.5v-9Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
              <path d="m4.4 7.7 7.6 4.3 7.6-4.3M12 12v9" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
            </svg>
          </span>
          <span class="brand-logo__text">PM System</span>
        </div>

        <div class="brand-copy">
          <p class="brand-eyebrow">PROJECT MANAGEMENT</p>
          <h1 class="brand-heading">让项目协作<br /><em>更简单</em>，更高效</h1>
          <p class="brand-desc">需求、排期、缺陷一站式管理，<br />团队的每一步都清晰可见。</p>

          <ul class="brand-points">
            <li>
              <span class="brand-point-icon" aria-hidden="true">
                <svg viewBox="0 0 20 20" fill="none"><path d="m4 10.5 4 4 8-9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </span>
              <span>需求到上线，全流程可追踪</span>
            </li>
            <li>
              <span class="brand-point-icon" aria-hidden="true">
                <svg viewBox="0 0 20 20" fill="none"><path d="m4 10.5 4 4 8-9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </span>
              <span>进度与风险，一屏尽掌握</span>
            </li>
            <li>
              <span class="brand-point-icon" aria-hidden="true">
                <svg viewBox="0 0 20 20" fill="none"><path d="m4 10.5 4 4 8-9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </span>
              <span>团队协作，沟通更顺畅</span>
            </li>
          </ul>
        </div>

        <p class="brand-footer">© 2026 PM System · 项目管理系统</p>
      </div>
    </aside>

    <!-- 右侧表单区：浅色留白 + 悬浮卡片 -->
    <main class="panel">
      <div class="panel-inner">
        <header class="form-heading">
          <span class="form-tag">WELCOME BACK</span>
          <h2>欢迎回来</h2>
          <p>登录账号，继续你的工作</p>
        </header>

        <div class="login-card">
          <el-form ref="formRef" :model="loginForm" :rules="loginRules" size="large" class="login-form">
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
        </div>

        <p class="form-hint">无法登录？<span>请联系系统管理员</span></p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  height: 100%;
  overflow: hidden;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* ---------- 左侧品牌区 ---------- */
.brand {
  position: relative;
  flex: 0 0 46%;
  max-width: 46%;
  overflow: hidden;
  color: #fff;
  background: linear-gradient(145deg, #0b1e3d 0%, #123a75 48%, #1a63c2 100%);
}
.brand::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent 55%, rgba(4, 14, 33, .35) 100%);
  pointer-events: none;
}
.brand-grid {
  position: absolute;
  inset: -40%;
  background-image:
    linear-gradient(rgba(255, 255, 255, .05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, .05) 1px, transparent 1px);
  background-size: 46px 46px;
  transform: rotate(-12deg);
  opacity: .55;
  pointer-events: none;
}
.brand-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  pointer-events: none;
}
.brand-orb--a {
  top: -120px;
  right: -80px;
  width: 360px;
  height: 360px;
  background: rgba(64, 180, 255, .35);
}
.brand-orb--b {
  bottom: -110px;
  left: -60px;
  width: 320px;
  height: 320px;
  background: rgba(99, 102, 241, .28);
}
.brand-ring {
  position: absolute;
  right: -190px;
  bottom: -120px;
  width: 420px;
  height: 420px;
  border: 1px solid rgba(255, 255, 255, .14);
  border-radius: 50%;
  box-shadow: 0 0 0 60px rgba(255, 255, 255, .03), 0 0 0 120px rgba(255, 255, 255, .02);
  pointer-events: none;
}
.brand-inner {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 48px clamp(32px, 5vw, 72px) 36px;
  box-sizing: border-box;
}
.brand-logo { display: flex; align-items: center; gap: 12px; }
.brand-logo__mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 1px solid rgba(255, 255, 255, .22);
  border-radius: 11px;
  background: rgba(255, 255, 255, .14);
  color: #e8f4ff;
}
.brand-logo__mark svg { width: 22px; height: 22px; }
.brand-logo__text { font-size: 19px; font-weight: 700; letter-spacing: .5px; }

.brand-copy { margin: auto 0; padding: 32px 0; }
.brand-eyebrow {
  margin: 0 0 18px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 3px;
  color: rgba(160, 214, 255, .9);
}
.brand-heading {
  margin: 0;
  font-size: clamp(32px, 3.4vw, 46px);
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: .5px;
}
.brand-heading em { font-style: normal; color: #7dd3fc; }
.brand-desc {
  margin: 20px 0 0;
  font-size: 15px;
  line-height: 1.9;
  color: rgba(226, 238, 255, .78);
}
.brand-points {
  display: grid;
  gap: 16px;
  margin: 32px 0 0;
  padding: 0;
  list-style: none;
}
.brand-points li {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  color: rgba(233, 242, 255, .88);
}
.brand-point-icon {
  display: grid;
  place-items: center;
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: rgba(125, 211, 252, .16);
  color: #7dd3fc;
}
.brand-point-icon svg { width: 14px; height: 14px; }
.brand-footer { margin: 0; font-size: 12px; color: rgba(210, 228, 250, .5); }

/* ---------- 右侧表单区 ---------- */
.panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  box-sizing: border-box;
  background: linear-gradient(180deg, #f8fbff 0%, #eef4fc 100%);
}
.panel-inner { width: 100%; max-width: 400px; }
.form-heading { margin-bottom: 28px; }
.form-tag {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 2.5px;
  color: #1976d2;
}
.form-tag::before {
  content: '';
  width: 22px;
  height: 2px;
  border-radius: 2px;
  background: #1976d2;
}
.form-heading h2 {
  margin: 14px 0 8px;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: .5px;
  color: #16233c;
}
.form-heading p { margin: 0; font-size: 14px; color: #8a97ab; }

.login-card {
  padding: 36px 36px 32px;
  border: 1px solid rgba(25, 118, 210, .06);
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 24px 60px rgba(16, 42, 84, .12);
}
.login-form :deep(.el-form-item) { margin-bottom: 22px; }
.login-form :deep(.el-input__wrapper) {
  height: 50px;
  border-radius: 12px;
  background: #f7f9fc;
  box-shadow: 0 0 0 1px transparent inset;
  transition: background .2s, box-shadow .2s;
}
.login-form :deep(.el-input__wrapper:hover) { background: #f2f6fc; }
.login-form :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px #1976d2 inset, 0 0 0 3px rgba(25, 118, 210, .1);
}
.login-form :deep(.el-input__inner) { font-size: 15px; color: #16233c; }
.login-form :deep(.el-input__inner::placeholder) { color: #a8b4c6; }
.login-form :deep(.el-input__prefix) { color: #7c8ba3; font-size: 17px; }

.login-btn-item { margin-bottom: 0 !important; }
.login-btn {
  width: 100%;
  height: 50px;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #2b86e8 0%, #125fc0 100%);
  box-shadow: 0 10px 22px rgba(25, 118, 210, .28);
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 4px;
  transition: transform .2s, box-shadow .2s;
}
.login-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 14px 28px rgba(25, 118, 210, .36);
}
.login-btn:active { transform: translateY(0); }

.form-hint { margin: 22px 0 0; text-align: center; font-size: 13px; color: #8a97ab; }
.form-hint span { color: #1976d2; font-weight: 500; }

/* ---------- 响应式 ---------- */
@media (max-width: 960px) {
  .brand { display: none; }
  .panel { width: 100%; }
}
@media (max-width: 480px) {
  .panel { padding: 24px 18px; }
  .login-card { padding: 28px 22px 26px; }
  .form-heading h2 { font-size: 25px; }
}
@media (max-height: 640px) and (min-width: 961px) {
  .brand-points, .brand-footer { display: none; }
}
</style>
