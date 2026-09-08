// Bug 处理进度常量：空=未处理（下拉 clearable，空值由 clear 触发，不设空选项）
export const HANDLE_STATUS_OPTIONS = [
  { value: 'DONE', label: '已处理' },
  { value: 'HOLD', label: '暂不处理' },
  { value: 'SKIP', label: '不处理' }
] as const

// Bug 验收进度常量：空=未验收（下拉 clearable，不设空选项）
export const ACCEPT_STATUS_OPTIONS = [
  { value: 'PASSED', label: '验收通过' },
  { value: 'REJECTED', label: '验收不通过' }
] as const

// 处理进度码转中文名，空值/未知码归到默认文案
export const handleStatusLabel = (v: string | null | undefined) =>
  (v && HANDLE_STATUS_OPTIONS.find((s) => s.value === v)?.label) || '未处理'

// 验收进度码转中文名，空值/未知码归到默认文案
export const acceptStatusLabel = (v: string | null | undefined) =>
  (v && ACCEPT_STATUS_OPTIONS.find((s) => s.value === v)?.label) || '未验收'

// 处理进度对应的 el-tag 颜色：未处理灰、已处理绿、暂不处理橙、不处理红
const HANDLE_STATUS_TAG: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
  DONE: 'success',
  HOLD: 'warning',
  SKIP: 'danger'
}

// 验收进度对应的 el-tag 颜色：未验收灰、通过绿、不通过红
const ACCEPT_STATUS_TAG: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
  PASSED: 'success',
  REJECTED: 'danger'
}

// 处理进度码转 el-tag 颜色
export const handleStatusTagType = (v: string | null | undefined) =>
  (v && HANDLE_STATUS_TAG[v]) || 'info'

// 验收进度码转 el-tag 颜色
export const acceptStatusTagType = (v: string | null | undefined) =>
  (v && ACCEPT_STATUS_TAG[v]) || 'info'
