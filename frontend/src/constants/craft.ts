// 项目内工种常量：与后端工种码一一对应（PM 为项目经理固定角色，不在下拉中分配）
export const CRAFT_OPTIONS = [
  { value: 'PLANNER', label: '策划' },
  { value: 'FE', label: '前端开发' },
  { value: 'BE', label: '后端开发' },
  { value: 'OPS', label: '运维' },
  { value: 'QA', label: '测试' },
  { value: 'UI', label: 'UI' }
] as const

// 工种码转中文名，未知码原样返回
export const craftLabel = (v: string) => CRAFT_OPTIONS.find(c => c.value === v)?.label || v
