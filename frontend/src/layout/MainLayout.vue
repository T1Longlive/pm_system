<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import {
  listNotifications,
  getUnreadCount,
  readAllNotifications,
  type NotificationItem
} from '@/api/notification'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 侧边栏菜单配置：图标（全局注册的 Element Plus 图标名）+ 标题 + 路由路径
// 「日历管理」仅管理员可见，menus 转为 computed 按身份过滤
const allMenus = [
  { path: '/dashboard', title: '工作台', icon: 'Odometer' },
  { path: '/projects', title: '项目管理', icon: 'Folder' },
  { path: '/requirements', title: '需求管理', icon: 'Tickets' },
  { path: '/schedule', title: '排期管理', icon: 'Calendar' },
  { path: '/bugs', title: 'Bug 管理', icon: 'WarningFilled' },
  { path: '/reports', title: '统计报表', icon: 'DataAnalysis' },
  { path: '/admin/users', title: '人员管理', icon: 'User' }
]

// 是否管理员
const isAdmin = computed(() => userStore.userInfo?.isAdmin === 1)

// 实际渲染的菜单：管理员额外展示「日历管理」
const menus = computed(() =>
  isAdmin.value
    ? [...allMenus, { path: '/admin/calendar', title: '日历管理', icon: 'Calendar' }]
    : allMenus
)

// 当前激活的菜单项（跟随路由变化）
const activeMenu = computed(() => route.path)

// 顶部展示的用户名（未拉取到用户信息时显示占位）
const displayName = computed(() => userStore.userInfo?.realName || userStore.userInfo?.username || '用户')

// ===== 通知铃铛 =====
// 未读数（进入页面时拉取，之后 60s 轮询）
const noticeCount = ref(0)
// 通知弹层显隐
const noticeVisible = ref(false)
// 最近通知列表（弹层展示最近 10 条）
const notices = ref<NotificationItem[]>([])
// 列表加载状态
const noticeLoading = ref(false)
// 60s 未读数轮询定时器
let noticeTimer: ReturnType<typeof setInterval> | null = null

// 拉取未读数（静默失败，不阻塞页面）
const fetchUnreadCount = async () => {
  try {
    const res = await getUnreadCount()
    noticeCount.value = res.data.data.count
  } catch {
    // 失败已由拦截器统一提示（401 已跳登录），角标保持原值
  }
}

// 拉取最近通知
const fetchNotices = async () => {
  noticeLoading.value = true
  try {
    const res = await listNotifications({ page: 1, size: 10 })
    notices.value = res.data.data.records
  } catch {
    // 失败已由拦截器统一提示
  } finally {
    noticeLoading.value = false
  }
}

// 点击铃铛：刷新未读数并拉取最近通知
const openNotices = () => {
  fetchUnreadCount()
  fetchNotices()
}

// 全部标记已读
const handleReadAll = async () => {
  try {
    await readAllNotifications()
    ElMessage.success('已全部标记为已读')
    fetchUnreadCount()
    fetchNotices()
  } catch {
    // 失败已由拦截器统一提示
  }
}

// 点击通知：需求类跳转对应详情页
const handleNoticeClick = (n: NotificationItem) => {
  if (n.bizType === 'REQUIREMENT' && n.bizId) {
    noticeVisible.value = false
    router.push(`/requirements/${n.bizId}`)
  }
}

// 用户下拉菜单命令处理：退出登录
const handleCommand = (command: string) => {
  if (command === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}

// 挂载：拉取未读数并启动 60s 轮询
onMounted(() => {
  fetchUnreadCount()
  noticeTimer = setInterval(fetchUnreadCount, 60_000)
})

// 卸载：清理轮询定时器
onUnmounted(() => {
  if (noticeTimer) {
    clearInterval(noticeTimer)
    noticeTimer = null
  }
})
</script>

<template>
  <el-container class="layout-container">
    <!-- 左侧：侧边导航菜单 -->
    <el-aside width="220px" class="layout-aside">
      <el-menu
        class="layout-menu"
        :default-active="activeMenu"
        router
        background-color="#001529"
        text-color="#a6adb4"
        active-text-color="#ffffff"
      >
        <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
          <el-icon><component :is="menu.icon" /></el-icon>
          <span>{{ menu.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container class="layout-body">
      <!-- 顶部：系统名、全局搜索、通知铃铛、用户下拉菜单 -->
      <el-header class="layout-header">
        <div class="header-title">项目管理系统</div>
        <div class="header-actions">
          <!-- 全局搜索输入框（占位，接全局搜索接口后启用） -->
          <el-input
            class="global-search"
            placeholder="搜索需求 / Bug / 项目 / 人员"
            clearable
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <!-- 通知铃铛：点击弹出最近通知 -->
          <el-popover v-model:visible="noticeVisible" placement="bottom-end" :width="360" trigger="click">
            <template #reference>
              <el-badge
                :value="noticeCount"
                :max="99"
                :hidden="noticeCount === 0"
                class="notice-badge"
                @click="openNotices"
              >
                <el-icon :size="18"><Bell /></el-icon>
              </el-badge>
            </template>
            <!-- 通知列表：最近 10 条，未读加粗 + 圆点 -->
            <div v-loading="noticeLoading" class="notice-list">
              <template v-if="notices.length">
                <div
                  v-for="n in notices"
                  :key="n.id"
                  class="notice-item"
                  :class="{ unread: n.isRead === 0, clickable: n.bizType === 'REQUIREMENT' || n.bizType === 'BUG' }"
                  @click="handleNoticeClick(n)"
                >
                  <div class="notice-item-head">
                    <span v-if="n.isRead === 0" class="notice-dot"></span>
                    <span class="notice-title">{{ n.title }}</span>
                    <span class="notice-time">{{ n.createdAt }}</span>
                  </div>
                  <div class="notice-content">{{ n.content }}</div>
                </div>
              </template>
              <el-empty v-else-if="!noticeLoading" description="暂无通知" :image-size="60" />
            </div>
            <div class="notice-footer">
              <el-button link type="primary" size="small" @click="handleReadAll">全部已读</el-button>
            </div>
          </el-popover>
          <!-- 用户下拉菜单 -->
          <el-dropdown @command="handleCommand">
            <span class="user-dropdown">
              <el-icon><UserFilled /></el-icon>
              <span>{{ displayName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 主区：业务页面出口 -->
      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
/* 整体布局：铺满视口 */
.layout-container {
  height: 100vh;
}

/* 左侧深色侧边栏 */
.layout-aside {
  background-color: #001529;
}

/* 菜单去除右侧边框 */
.layout-menu {
  border-right: none;
}

/* 顶部栏：左右分布 */
.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
}

/* 系统名 */
.header-title {
  font-size: 16px;
  font-weight: 600;
}

/* 顶部右侧操作区 */
.header-actions {
  display: flex;
  align-items: center;
  gap: 24px;
}

/* 全局搜索框宽度 */
.global-search {
  width: 320px;
}

/* 通知铃铛：可点击 */
.notice-badge {
  cursor: pointer;
}

/* 通知列表：限制高度可滚动 */
.notice-list {
  max-height: 360px;
  overflow-y: auto;
}

/* 单条通知 */
.notice-item {
  padding: 8px 4px;
  border-bottom: 1px solid #f0f2f5;
}

.notice-item:last-child {
  border-bottom: none;
}

/* 可跳转的通知：手型 */
.notice-item.clickable {
  cursor: pointer;
}

.notice-item.clickable:hover {
  background: #f5f7fa;
}

/* 通知头部：圆点 + 标题 + 时间 */
.notice-item-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* 未读圆点 */
.notice-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #f56c6c;
}

/* 通知标题：未读加粗 */
.notice-title {
  flex: 1;
  overflow: hidden;
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.notice-item.unread .notice-title {
  font-weight: 600;
}

/* 通知时间 */
.notice-time {
  flex-shrink: 0;
  font-size: 12px;
  color: #909399;
}

/* 通知内容：最多两行 */
.notice-content {
  display: -webkit-box;
  margin-top: 4px;
  overflow: hidden;
  font-size: 12px;
  line-height: 18px;
  color: #909399;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

/* 弹层底部：全部已读 */
.notice-footer {
  padding-top: 8px;
  text-align: center;
  border-top: 1px solid #f0f2f5;
}

/* 用户下拉触发区 */
.user-dropdown {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  outline: none;
}

/* 主内容区：浅灰背景 */
.layout-main {
  background-color: #f5f7fa;
}
</style>
