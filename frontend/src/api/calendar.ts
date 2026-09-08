import request, { type ApiResponse } from './request'

// 日历日类型：工作日 / 周末 / 节假日 / 调休上班（周末调整为工作日）
export type CalendarDayType = 'WORKDAY' | 'WEEKEND' | 'HOLIDAY' | 'ADJUSTED'

// 单个日历日
export interface CalendarDayData {
  // 日期，格式 yyyy-MM-dd
  day: string
  type: CalendarDayType
  // 节假日名称（仅节假日时有值）
  name: string | null
}

// 月历数据
export interface CalendarMonthData {
  year: number
  month: number
  days: CalendarDayData[]
}

// 查询指定年月的日历
export function getCalendar(year: number, month: number) {
  return request.get<ApiResponse<CalendarMonthData>>('/calendar', { params: { year, month } })
}

// 更新某日的日历类型（仅管理员）
export function updateCalendarDay(day: string, data: { type: CalendarDayType; name?: string }) {
  return request.put<ApiResponse<void>>(`/calendar/${day}`, data)
}
