import type { ReqAttachment, ReqParticipantInput } from '@/api/requirement'

/**
 * 需求表单的纯逻辑：从 RequirementFormDialog 抽离，便于 Vitest 覆盖。
 * 只做数据整形，不依赖 Vue 响应式与接口。
 */

export interface ParticipantRow {
  craft: string
  userIds: number[]
}

/** 项目是否可被当前用户创建需求：PM 或策划角色 */
export function isCreatableProject(project: { myRole: string[] }): boolean {
  return project.myRole.includes('PM') || project.myRole.includes('PLANNER')
}

/**
 * 参与人行 → 提交列表：
 * 剔除未选工种或未选成员的空行，按「工种 + 用户」去重（同一人可兼多工种），保持提交顺序。
 */
export function collectParticipants(rows: ParticipantRow[]): ReqParticipantInput[] {
  const seen = new Set<string>()
  const result: ReqParticipantInput[] = []
  for (const row of rows) {
    if (!row.craft || row.userIds.length === 0) continue
    for (const userId of row.userIds) {
      const key = `${row.craft}-${userId}`
      if (seen.has(key)) continue
      seen.add(key)
      result.push({ userId, craft: row.craft })
    }
  }
  return result
}

/** 附件提交：仅保留契约字段（不带本地回显用的 id） */
export function collectAttachments(attachments: ReqAttachment[]) {
  return attachments.map(({ url, fileName, fileType, fileSize }) => ({
    url,
    fileName,
    fileType,
    fileSize
  }))
}
