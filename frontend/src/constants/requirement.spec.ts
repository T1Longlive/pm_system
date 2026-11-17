import { describe, expect, it } from 'vitest'
import {
  STATUS_OPTIONS,
  priorityLabel,
  priorityTagType,
  statusLabel,
  statusTagType
} from './requirement'

describe('需求状态常量', () => {
  it('状态集合与后端派生态一致，且 READY 不再是可选项', () => {
    expect(STATUS_OPTIONS.map((s) => s.value)).toEqual([
      'PENDING', 'DEV', 'TEST', 'ARCHIVING', 'ARCHIVED', 'CANCELLED'
    ])
    expect(STATUS_OPTIONS.some((s) => s.value === ('READY' as string))).toBe(false)
  })

  it('终态只有已归档与已取消', () => {
    expect(STATUS_OPTIONS.filter((s) => s.terminal).map((s) => s.value))
      .toEqual(['ARCHIVED', 'CANCELLED'])
  })

  it('状态码转中文，未知码原样返回', () => {
    expect(statusLabel('PENDING')).toBe('待排期')
    expect(statusLabel('UNKNOWN')).toBe('UNKNOWN')
  })

  it('历史 READY 保留颜色兜底但不在选项里', () => {
    expect(statusTagType('READY')).toBe('primary')
    expect(statusTagType('UNKNOWN')).toBe('info')
  })

  it('优先级标签与颜色映射', () => {
    expect(priorityLabel(1)).toBe('高')
    expect(priorityLabel(3)).toBe('低')
    expect(priorityLabel(9)).toBe('9')
    expect(priorityTagType(1)).toBe('danger')
    expect(priorityTagType(2)).toBe('warning')
    expect(priorityTagType(3)).toBe('info')
  })
})
