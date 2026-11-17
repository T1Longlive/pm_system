/**
 * Bug 逐格编辑的哨兵语义（与后端 BugUpdateDTO 约定一致）：
 *   文本：'' = 清空
 *   数值/ID：0 = 清空
 *   状态下拉：'' = 清空
 * 抽离为纯函数，便于 Vitest 覆盖，避免组件里散落魔法值。
 */

export interface TextCellUpdate {
  /** 本地展示值：空串归一化为 null */
  local: string | null
  /** PUT 提交值：空串原样提交，触发后端清空 */
  payload: string
}

export interface NumCellUpdate {
  local: number | null
  /** PUT 提交值：清空时为 0 */
  payload: number
}

export interface StatusCellUpdate {
  local: string | null
  /** PUT 提交值：清空时为 '' */
  payload: string
}

export function textCellUpdate(value: string): TextCellUpdate {
  const trimmed = value.trim()
  return { local: trimmed === '' ? null : trimmed, payload: trimmed }
}

export function numCellUpdate(value: number | '' | null | undefined): NumCellUpdate {
  const local = value == null || value === '' ? null : value
  return { local, payload: local === null ? 0 : local }
}

export function statusCellUpdate(value: string | '' | null | undefined): StatusCellUpdate {
  const local = value ? value : null
  return { local, payload: local ?? '' }
}

/** 外链视频显示名兜底：取 URL 尾段、解码、截断 200 字符 */
export function videoNameFromUrl(url: string): string {
  const tail = url.split('/').pop() || url
  let name = tail
  try {
    name = decodeURIComponent(tail)
  } catch {
    // 非法编码保持原样
  }
  return name.slice(0, 200)
}

/**
 * 视频保存载荷：URL 为空表示清除（同时清空名称）；
 * 名称优先取本次上传的原始文件名，否则取 URL 尾段。
 */
export function videoCellUpdate(url: string, uploadedName?: string | null): {
  videoUrl: string
  videoName: string
} {
  const trimmed = url.trim()
  if (!trimmed) return { videoUrl: '', videoName: '' }
  return {
    videoUrl: trimmed,
    videoName: uploadedName?.trim() || videoNameFromUrl(trimmed)
  }
}
