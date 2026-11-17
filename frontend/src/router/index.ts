import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { authGuard } from './guard'

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
          path: 'operation-logs',
          name: 'OperationLogs',
          component: () => import('@/views/module/OperationLogs.vue'),
          meta: { title: '操作日志', pmOrAdmin: true }
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

// 全局前置守卫：规则见 router/guard.ts（抽成纯函数便于单测覆盖）
router.beforeEach((to) => authGuard(to, useUserStore()))

export default router
