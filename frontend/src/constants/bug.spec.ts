import { describe, expect, it } from 'vitest'
import {
  ACCEPT_STATUS_OPTIONS,
  HANDLE_STATUS_OPTIONS,
  acceptStatusLabel,
  acceptStatusTagType,
  handleStatusLabel,
  handleStatusTagType
} from './bug'

describe('Bug 进度常量', () => {
  it('处理进度选项与后端值域一致', () => {
    expect(HANDLE_STATUS_OPTIONS.map((o) => o.value)).toEqual(['DONE', 'HOLD', 'SKIP'])
    expect(ACCEPT_STATUS_OPTIONS.map((o) => o.value)).toEqual(['PASSED', 'REJECTED'])
  })

  it('空值归到默认文案', () => {
    expect(handleStatusLabel(null)).toBe('未处理')
    expect(handleStatusLabel('')).toBe('未处理')
    expect(acceptStatusLabel(undefined)).toBe('未验收')
  })

  it('已知码返回中文标签', () => {
    expect(handleStatusLabel('DONE')).toBe('已处理')
    expect(handleStatusLabel('HOLD')).toBe('暂不处理')
    expect(acceptStatusLabel('REJECTED')).toBe('验收不通过')
  })

  it('标签颜色映射，空值与未知码为 info', () => {
    expect(handleStatusTagType('DONE')).toBe('success')
    expect(handleStatusTagType('HOLD')).toBe('warning')
    expect(handleStatusTagType('SKIP')).toBe('danger')
    expect(handleStatusTagType(null)).toBe('info')
    expect(acceptStatusTagType('PASSED')).toBe('success')
    expect(acceptStatusTagType('REJECTED')).toBe('danger')
    expect(acceptStatusTagType('X')).toBe('info')
  })
})
