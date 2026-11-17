import { describe, expect, it } from 'vitest'
import {
  numCellUpdate,
  statusCellUpdate,
  textCellUpdate,
  videoCellUpdate,
  videoNameFromUrl
} from './bug'

describe('文本格哨兵', () => {
  it('普通文本 trim 后原样提交', () => {
    expect(textCellUpdate('  登录页  ')).toEqual({ local: '登录页', payload: '登录页' })
  })

  it('空串与纯空白都表示清空', () => {
    expect(textCellUpdate('')).toEqual({ local: null, payload: '' })
    expect(textCellUpdate('   ')).toEqual({ local: null, payload: '' })
  })
})

describe('数值/ID 哨兵', () => {
  it('有值：本地与提交值一致', () => {
    expect(numCellUpdate(3)).toEqual({ local: 3, payload: 3 })
  })

  it('空值/空串归一化为 null，提交 0', () => {
    expect(numCellUpdate(undefined)).toEqual({ local: null, payload: 0 })
    expect(numCellUpdate(null)).toEqual({ local: null, payload: 0 })
    expect(numCellUpdate('')).toEqual({ local: null, payload: 0 })
  })

  it('数字 0 按原值处理（后端 0 亦表示清空）', () => {
    expect(numCellUpdate(0)).toEqual({ local: 0, payload: 0 })
  })
})

describe('状态下拉哨兵', () => {
  it('有值原样提交', () => {
    expect(statusCellUpdate('DONE')).toEqual({ local: 'DONE', payload: 'DONE' })
  })

  it('空值/空串归一化为 null，提交空串', () => {
    expect(statusCellUpdate('')).toEqual({ local: null, payload: '' })
    expect(statusCellUpdate(undefined)).toEqual({ local: null, payload: '' })
  })
})

describe('视频载荷', () => {
  it('空 URL 双清', () => {
    expect(videoCellUpdate('   ', 'name')).toEqual({ videoUrl: '', videoName: '' })
  })

  it('优先使用上传文件名', () => {
    expect(videoCellUpdate(' https://cdn/a.mp4 ', ' 录制.mp4 '))
      .toEqual({ videoUrl: 'https://cdn/a.mp4', videoName: '录制.mp4' })
  })

  it('没有上传名时取 URL 尾段', () => {
    expect(videoCellUpdate('https://cdn/path/demo.mp4'))
      .toEqual({ videoUrl: 'https://cdn/path/demo.mp4', videoName: 'demo.mp4' })
  })

  it('URL 尾段解码并截断 200 字符', () => {
    expect(videoNameFromUrl('https://cdn/%E6%BC%94%E7%A4%BA.mp4')).toBe('演示.mp4')
    expect(videoNameFromUrl('https://cdn/' + 'a'.repeat(300))).toHaveLength(200)
  })
})
