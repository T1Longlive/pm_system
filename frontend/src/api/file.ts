import request, { type ApiResponse } from './request'

// 文件上传结果
export interface UploadResult {
  // 文件访问路径，如 /upload/2026/09/uuid.png
  url: string
  // 原始文件名
  fileName: string
  // 文件类型：IMAGE 图片 / DOC 文档 / VIDEO 视频
  fileType: 'IMAGE' | 'DOC' | 'VIDEO'
  // 文件大小（字节）
  fileSize: number
}

// 上传文件（axios 传 FormData 时自动设置 multipart/form-data 边界，无需手动指定 Content-Type）
export function uploadFile(file: File) {
  const data = new FormData()
  data.append('file', file)
  return request.post<ApiResponse<UploadResult>>('/files/upload', data)
}
