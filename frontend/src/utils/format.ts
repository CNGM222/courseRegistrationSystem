import type { Meeting, Semester, Schedule } from '../types/api'
export const gradeValues = ['A', 'B', 'C', 'D', 'F', 'I'] as const
export const weekdays = ['一', '二', '三', '四', '五', '六', '日']
export const labels: Record<string, string> = {
  STUDENT: '学生',
  PROFESSOR: '教师',
  REGISTRAR: '教务员',
  OPEN: '开放中',
  CLOSED: '已关闭',
  PREPARATION: '准备中',
  CANCELLED: '已取消',
  DRAFT: '草稿',
  REGISTERED: '已注册',
  WITHDRAWN: '已撤销',
  FINALIZED: '已确定',
  EXPIRED: '已失效',
  ENROLLED: '已注册',
  DROPPED: '已退课',
  PENDING: '待发送',
  IN_FLIGHT: '发送中',
  RETRY: '等待重试',
  DELIVERED: '已送达',
  ACTIVE: '正常',
  INACTIVE: '停用',
  GRADUATED: '已毕业',
  PRIMARY: '主选',
  ALTERNATE: '备选补位',
  NO_PROFESSOR: '无授课教师',
  BELOW_MINIMUM: '不足三人',
  USER_DROP: '主动退课',
  SCHEDULE_DELETE: '课表已删除',
}
export const label = (v?: string | null) => (v ? labels[v] || v : '—')
export function dateTime(value?: string | null) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime())
    ? value
    : d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}
export function today() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}
export function registrationOpen(s?: Semester, now = Date.now()) {
  return (
    !!s &&
    s.status === 'OPEN' &&
    now >= Date.parse(s.registrationStart) &&
    now < Date.parse(s.addDropEnd)
  )
}
export function ended(s?: Semester) {
  return !!s && s.endDate < today()
}
export function meetsText(meetings: Meeting[]) {
  const groups = new Map<string, number[]>()
  for (const m of meetings) {
    const k = `周${weekdays[m.weekday - 1]} ${m.startPeriod}–${m.endPeriodExclusive - 1}节${m.room ? ' · ' + m.room : ''}`
    groups.set(k, [...(groups.get(k) || []), m.weekNo])
  }
  return [...groups].map(
    ([k, weeks]) => `${k}（第${[...new Set(weeks)].sort((a, b) => a - b).join('、')}周）`,
  )
}
export function choiceIds(s: Schedule | null, kind: 'PRIMARY' | 'ALTERNATE') {
  return [...(s?.draftChoices || s?.submittedChoices || [])]
    .filter((c) => c.kind === kind)
    .sort((a, b) => a.priority - b.priority)
    .map((c) => c.offeringId)
}
export function errorMessage(e: unknown) {
  return e instanceof Error ? e.message : '操作失败，请重试'
}
