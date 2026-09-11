import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
// 主题覆盖：primary 浅蓝改深蓝（须在 element css 之后引入）
import './styles/theme.css'

import App from './App.vue'
import router from './router'

// 创建 Vue 应用实例
const app = createApp(App)

// 全局注册 Element Plus 全部图标组件（模板中可直接按名称使用）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 注册 Pinia 状态管理
app.use(createPinia())
// 注册路由
app.use(router)
// 全量注册 Element Plus，并使用中文语言包
app.use(ElementPlus, { locale: zhCn })

// 挂载应用
app.mount('#app')
