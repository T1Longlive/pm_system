import { describe, expect, it } from 'vitest'
import {
  collectAttachments,
  collectParticipants,
  isCreatableProject
} from './requirementForm'

describe('requirementForm 需求表单纯逻辑', () => {
  it('可创建项目：PM 或策划可见，普通工种不可见', () => {
    expect(isCreatableProject({ myRole: ['PM'] })).toBe(true)
    expect(isCreatableProject({ myRole: ['PLANNER'] })).toBe(true)
    expect(isCreatableProject({ myRole: ['PM', 'FE'] })).toBe(true)
    expect(isCreatableProject({ myRole: ['FE', 'QA'] })).toBe(false)
    expect(isCreatableProject({ myRole: [] })).toBe(false)
  })

  it('参与人整形：剔除空行，同人同工种去重，同人不同工种保留', () => {
    const rows = [
      { craft: 'FE', userIds: [1, 2] },
      { craft: '', userIds: [3] },
      { craft: 'QA', userIds: [] },
      { craft: 'FE', userIds: [2, 3] },
      { craft: 'QA', userIds: [2] }
    ]

    expect(collectParticipants(rows)).toEqual([
      { userId: 1, craft: 'FE' },
      { userId: 2, craft: 'FE' },
      { userId: 3, craft: 'FE' },
      { userId: 2, craft: 'QA' }
    ])
  })

  it('参与人整形：空数组返回空列表', () => {
    expect(collectParticipants([])).toEqual([])
  })

  it('附件整形：提交时剔除本地回显 id，只保留契约字段', () => {
    const attachments = [{
      id: 99,
      url: '/upload/a.png',
      fileName: 'a.png',
      fileType: 'IMAGE' as const,
      fileSize: 10
    }]

    expect(collectAttachments(attachments)).toEqual([{
      url: '/upload/a.png',
      fileName: 'a.png',
      fileType: 'IMAGE',
      fileSize: 10
    }])
  })
})
