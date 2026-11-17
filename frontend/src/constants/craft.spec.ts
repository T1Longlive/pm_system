import { describe, expect, it } from 'vitest'
import { CRAFT_OPTIONS, craftLabel } from './craft'

describe('工种常量', () => {
  it('六个工种与后端 CraftConstants 手工对齐，PM 不在其中', () => {
    expect(CRAFT_OPTIONS.map((c) => c.value))
      .toEqual(['PLANNER', 'FE', 'BE', 'OPS', 'QA', 'UI'])
    expect(CRAFT_OPTIONS.some((c) => c.value === ('PM' as string))).toBe(false)
  })

  it('工种码转中文，未知码原样返回', () => {
    expect(craftLabel('FE')).toBe('前端开发')
    expect(craftLabel('QA')).toBe('测试')
    expect(craftLabel('XX')).toBe('XX')
  })
})
