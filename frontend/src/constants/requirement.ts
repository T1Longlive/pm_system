// 需求状态常量：与后端状态机一一对应（terminal 标记终态，终态不可再流转）
export const STATUS_OPTIONS = [
  { value: 'PENDING', label: '待排期', terminal: false },
  { value: 'READY', label: '已排期', terminal: false },
  { value: 'DEV', label: '开发中', terminal: false },
  { value: 'TEST', label: '测试中', terminal: false },
  { value: 'ARCHIVING', label: '待归档', terminal: false },
  { value: 'ARCHIVED', label: '已归档', terminal: true },
  { value: 'CANCELLED', label: '已取消', terminal: true }
] as const

// 优先级选项：1高 2中 3低（默认中）
export const PRIORITY_OPTIONS = [
  { value: 1, label: '高' },
  { value: 2, label: '中' },
  { value: 3, label: '低' }
] as const

// 状态码转中文名，未知码原样返回
export const statusLabel = (v: string) => STATUS_OPTIONS.find((s) => s.value === v)?.label || v

// 优先级值转中文名，未知值原样返回
export const priorityLabel = (v: number) => PRIORITY_OPTIONS.find((p) => p.value === v)?.label || String(v)

// 状态对应的 el-tag 颜色：未排期灰、进行中蓝/橙、已归档绿、已取消红
const STATUS_TAG: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
  PENDING: 'info',
  READY: 'primary',
  DEV: 'warning',
  TEST: 'primary',
  ARCHIVING: 'warning',
  ARCHIVED: 'success',
  CANCELLED: 'danger'
}

// 状态码转 el-tag 颜色
export const statusTagType = (v: string) => STATUS_TAG[v] || 'info'

// 优先级转 el-tag 颜色：高红 中橙 低灰
export const priorityTagType = (v: number): 'danger' | 'warning' | 'info' =>
  v === 1 ? 'danger' : v === 2 ? 'warning' : 'info'
