import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

// 路由元信息扩展：admin 标记管理员专属页面（守卫按此拦截非管理员）
declare module 'vue-router' {
  interface RouteMeta {
    admin?: boolean
  }
}

// 路由表：/login 登录页；/ 主布局 + 七个业务模块子路由
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/Login.vue'),
      meta: { title: '登录' }
    },
    {
      // 强制改密页（独立于主布局，改密完成前无法访问其他页面）
      path: '/change-password',
      name: 'ChangePassword',
      component: () => import('@/views/ChangePassword.vue'),
      meta: { title: '修改密码' }
    },
    {
      path: '/',
      component: () => import('@/layout/MainLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/module/Dashboard.vue'),
          meta: { title: '工作台' }
        },
        {
          path: 'projects',
          name: 'Projects',
          component: () => import('@/views/module/Projects.vue'),
          meta: { title: '项目管理' }
        },
        {
          path: 'projects/:id',
          name: 'ProjectDetail',
          component: () => import('@/views/module/ProjectDetail.vue'),
          meta: { title: '项目详情' }
        },
        {
          path: 'requirements',
          name: 'Requirements',
          component: () => import('@/views/module/Requirements.vue'),
          meta: { title: '需求管理' }
        },
        {
          path: 'requirements/:id',
          name: 'RequirementDetail',
          component: () => import('@/views/module/RequirementDetail.vue'),
          meta: { title: '需求详情' }
        },
        {
          path: 'schedule',
          name: 'Schedule',
          component: () => import('@/views/module/Schedule.vue'),
          meta: { title: '排期管理' }
        },
        {
          path: 'bugs',
          name: 'Bugs',
          component: () => import('@/views/module/Bugs.vue'),
          meta: { title: 'Bug 管理' }
        },
        {
          path: 'reports',
          name: 'Reports',
          component: () => import('@/views/module/Reports.vue'),
          meta: { title: '统计报表' }
        },
        {
          path: 'admin/users',
          name: 'AdminUsers',
          component: () => import('@/views/module/AdminUsers.vue'),
          meta: { title: '人员管理', admin: true }
        },
        {
          path: 'admin/calendar',
          name: 'AdminCalendar',
          component: () => import('@/views/module/AdminCalendar.vue'),
          meta: { title: '日历管理', admin: true }
        }
      ]
    }
  ]
})

// 全局前置守卫：无 token（未登录）一律重定向到登录页；刷新后 userInfo 缺失时回填
router.beforeEach(async (to) => {
  const userStore = useUserStore()
  if (to.path !== '/login' && !userStore.token) {
    return { path: '/login' }
  }
  // 有 token 但用户信息丢失（如旧会话升级），调 /auth/me 回填
  if (to.path !== '/login' && userStore.token && !userStore.userInfo) {
    try {
      await userStore.fetchUserInfo()
    } catch {
      // 回填失败（token 失效等）由响应拦截器统一处理跳登录
    }
  }
  // 管理员专属页面（meta.admin）：非管理员重定向回工作台
  if (to.meta.admin && userStore.userInfo?.isAdmin !== 1) {
    return { path: '/dashboard' }
  }
  // 强制改密拦截：需改密用户只能停留在改密页（改密完成或登出前无法访问其他页面）
  if (to.path !== '/change-password' && userStore.token && userStore.forceChangePwd) {
    return { path: '/change-password' }
  }
  // 已无强制改密需求的用户访问改密页：回工作台
  if (to.path === '/change-password' && !userStore.forceChangePwd) {
    return { path: '/dashboard' }
  }
})

export default router
