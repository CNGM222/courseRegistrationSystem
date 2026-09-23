import { request, withIfMatch } from './http'
import type {
  BillingEvent,
  CloseResult,
  CurrentUser,
  GradeValue,
  Offering,
  PageResult,
  PersonRecord,
  ReportCard,
  Roster,
  Schedule,
  ScheduleValidation,
  Semester,
  TeachingPlan,
} from '../types/api'

export const api = {
  auth: {
    login: (username: string, password: string) =>
      request<CurrentUser>('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      }),
    logout: () => request<void>('/auth/logout', { method: 'POST' }),
    me: () => request<CurrentUser>('/auth/me'),
  },
  semesters: {
    list: () => request<Semester[]>('/semesters'),
    offerings: (
      semesterId: string,
      params: { page?: number; pageSize?: number; keyword?: string; department?: string } = {},
    ) => {
      const query = new URLSearchParams()
      Object.entries(params).forEach(
        ([key, value]) => value !== undefined && query.set(key, String(value)),
      )
      return request<PageResult<Offering>>(
        `/semesters/${semesterId}/offerings?${query.toString()}`,
        {},
        9500,
      )
    },
    availability: (semesterId: string, ids: string[]) =>
      request<Offering[]>(
        `/semesters/${semesterId}/offering-availability?${ids.map((id) => `ids=${encodeURIComponent(id)}`).join('&')}`,
      ),
  },
  student: {
    schedule: (semesterId: string) => request<Schedule>(`/students/me/schedules/${semesterId}`),
    saveDraft: (
      semesterId: string,
      body: { primaryOfferingIds: string[]; alternateOfferingIds: string[] },
      version?: number | null,
    ) =>
      request<Schedule>(`/students/me/schedules/${semesterId}/draft`, {
        method: 'PUT',
        headers: withIfMatch(undefined, version),
        body: JSON.stringify(body),
      }),
    validate: (semesterId: string, draftRevision: number) =>
      request<ScheduleValidation>(`/students/me/schedules/${semesterId}/validate`, {
        method: 'POST',
        body: JSON.stringify({ draftRevision }),
      }),
    submit: (semesterId: string, draftRevision: number, version?: number | null) =>
      request<Schedule>(`/students/me/schedules/${semesterId}/submit`, {
        method: 'POST',
        headers: withIfMatch(undefined, version),
        body: JSON.stringify({ draftRevision }),
      }),
    deleteSchedule: (semesterId: string, version?: number | null) =>
      request<void>(`/students/me/schedules/${semesterId}`, {
        method: 'DELETE',
        headers: withIfMatch(undefined, version),
      }),
    reportCard: (semesterId: string) =>
      request<ReportCard>(`/students/me/report-cards/${semesterId}`),
  },
  offering: (id: string) => request<Offering>(`/offerings/${encodeURIComponent(id)}`, {}, 9500),
  professor: {
    teaching: (semesterId: string) =>
      request<TeachingPlan>(`/professors/me/teaching/${semesterId}`),
    saveTeaching: (semesterId: string, offeringIds: string[], version?: number | null) =>
      request<TeachingPlan>(`/professors/me/teaching/${semesterId}`, {
        method: 'PUT',
        headers: withIfMatch(undefined, version),
        body: JSON.stringify({ offeringIds }),
      }),
    roster: (offeringId: string) =>
      request<Roster>(`/professors/me/offerings/${offeringId}/roster`),
    saveGrades: (
      offeringId: string,
      entries: { enrollmentId: string; grade: GradeValue; expectedVersion: number | null }[],
    ) =>
      request<Roster>(`/professors/me/offerings/${offeringId}/grades`, {
        method: 'PUT',
        body: JSON.stringify({ entries }),
      }),
  },
  registrar: {
    person: (kind: 'students' | 'professors', id: string) =>
      request<PersonRecord>(`/registrar/${kind}/${encodeURIComponent(id)}`),
    students: (page = 1, pageSize = 20, keyword = '') =>
      request<PageResult<PersonRecord>>(
        `/registrar/students?page=${page}&pageSize=${pageSize}&keyword=${encodeURIComponent(keyword)}`,
      ),
    createStudent: (body: Record<string, unknown>) =>
      request<PersonRecord>('/registrar/students', { method: 'POST', body: JSON.stringify(body) }),
    updateStudent: (id: string, body: Record<string, unknown>, version: number) =>
      request<PersonRecord>(`/registrar/students/${id}`, {
        method: 'PUT',
        headers: withIfMatch(undefined, version),
        body: JSON.stringify(body),
      }),
    deleteStudent: (id: string, version: number) =>
      request<void>(`/registrar/students/${id}`, {
        method: 'DELETE',
        headers: withIfMatch(undefined, version),
      }),
    professors: (page = 1, pageSize = 20, keyword = '') =>
      request<PageResult<PersonRecord>>(
        `/registrar/professors?page=${page}&pageSize=${pageSize}&keyword=${encodeURIComponent(keyword)}`,
      ),
    createProfessor: (body: Record<string, unknown>) =>
      request<PersonRecord>('/registrar/professors', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    updateProfessor: (id: string, body: Record<string, unknown>, version: number) =>
      request<PersonRecord>(`/registrar/professors/${id}`, {
        method: 'PUT',
        headers: withIfMatch(undefined, version),
        body: JSON.stringify(body),
      }),
    deleteProfessor: (id: string, version: number) =>
      request<void>(`/registrar/professors/${id}`, {
        method: 'DELETE',
        headers: withIfMatch(undefined, version),
      }),
    close: (semesterId: string) =>
      request<CloseResult>(`/registrar/semesters/${semesterId}/close`, { method: 'POST' }, 120000),
    closeResult: (semesterId: string) =>
      request<CloseResult>(`/registrar/semesters/${semesterId}/close-result`),
    billingEvents: (semesterId: string, page = 1, pageSize = 20, state = '') =>
      request<PageResult<BillingEvent>>(
        `/registrar/semesters/${semesterId}/billing-events?page=${page}&pageSize=${pageSize}&state=${encodeURIComponent(state)}`,
      ),
  },
}
