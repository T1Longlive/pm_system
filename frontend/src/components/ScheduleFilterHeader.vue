<script setup lang="ts">
import { CaretBottom } from '@element-plus/icons-vue'

// 筛选项：value 为归一化值（空值统一 ''），label 展示名，count 为该值行数
export interface ColFilterOption {
  value: string
  label: string
  count: number
}

defineProps<{
  title: string
  active: boolean
}>()

const emit = defineEmits<{
  // 携带原生事件：父组件用触发按钮位置定位单例筛选面板
  (e: 'toggle', ev: MouseEvent): void
}>()
</script>

<template>
  <div class="th-filter-wrap">
    <span class="th-title">{{ title }}</span>
    <span
      class="filter-trigger"
      :class="{ active }"
      title="筛选"
      @click.stop="emit('toggle', $event)"
    >
      <el-icon><CaretBottom /></el-icon>
    </span>
  </div>
</template>

<style scoped>
/* 布局容器：占满单元格内容区并居中标题；
   注意三角按钮的 absolute 锚点是父页设置的 .vxe-cell（position:relative），
   使按钮贴到表头单元格右下角而非标题文字右下角 */
.th-filter-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 24px;
}

.th-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.filter-trigger {
  position: absolute;
  right: 2px;
  bottom: 2px;
  width: 14px;
  height: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  line-height: 1;
  color: #fff;
  background: #2e7d32;
  border-radius: 2px;
  cursor: pointer;
}

.filter-trigger:hover {
  background: #256b29;
  color: #fff;
}

.filter-trigger.active {
  background: #1b5e20;
  color: #fff;
}
</style>
