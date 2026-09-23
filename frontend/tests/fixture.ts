// Isolated HTTP fixtures for automated tests only; never imported by the application.
import type { Page } from '@playwright/test'
const iso = (offset: number) => new Date(Date.now() + offset * 86400000).toISOString()
export const current = {
  id: '1',
  code: 'CURRENT',
  name: '当前测试学期',
  startDate: iso(-10).slice(0, 10),
  endDate: iso(100).slice(0, 10),
  registrationStart: iso(-15),
  registrationEnd: iso(5),
  addDropEnd: iso(10),
  status: 'OPEN',
  currency: 'CNY',
  version: 0,
}
export const previous = {
  ...current,
  id: '2',
  code: 'PREVIOUS',
  name: '上个测试学期',
  startDate: iso(-200).slice(0, 10),
  endDate: iso(-30).slice(0, 10),
  registrationStart: iso(-210),
  addDropEnd: iso(-190),
  status: 'CLOSED',
}
export const offerings = Array.from({ length: 8 }, (_, i) => ({
  id: String(100 + i),
  semesterId: '1',
  courseId: String(300 + i),
  courseCode: `CS${i + 1}`,
  courseName: [
    '软件工程',
    '数据库原理',
    '计算机网络',
    '操作系统',
    '人工智能',
    '编译原理',
    '算法设计',
    '计算机图形学',
  ][i],
  department: '计算机学院',
  credits: 3,
  sectionCode: '01',
  professorId: '30',
  professorName: '测试教师',
  prerequisites: [],
  meetings: [
    { weekNo: 1, weekday: (i % 7) + 1, startPeriod: 1, endPeriodExclusive: 3, room: '教学楼101' },
  ],
  enrolledCount: 3,
  maxStudents: 10,
  minStudents: 3,
  status: 'OPEN',
  tuition: '600.00',
  currency: 'CNY',
  version: 0,
}))
export function initialSchedule() {
  return {
    id: '50',
    semesterId: '1',
    status: 'DRAFT',
    version: 0,
    draftRevision: 1,
    submittedRevision: null as number | null,
    firstSubmittedAt: null as string | null,
    draftChoices: [] as any[],
    submittedChoices: [] as any[],
    enrollments: [] as any[],
  }
}
export async function setup(page: Page, role = 'STUDENT') {
  const state = {
    user: {
      accountId: '10',
      personId: role === 'STUDENT' ? '20' : '30',
      role,
      displayName: '测试用户',
      username: 'tester',
    } as any,
    schedule: initialSchedule(),
    offerings: structuredClone(offerings),
    semesters: [structuredClone(current), structuredClone(previous)],
    savedDraft: null as any,
    submissions: 0,
    deletes: 0,
    headers: [] as any[],
    loginError: false,
    availability401: false,
    catalogError: false,
    staleSubmit: false,
    validationError: false,
    grades: null as any,
    newPerson: null as any,
    personnelConflict: false,
    closeResult: null as any,
    closeBusy: false,
    closeCount: 0,
    csrfCalls: 0,
    teaching: {
      version: 0,
      eligibleOfferings: structuredClone(offerings),
      selectedOfferings: [structuredClone(offerings[0])],
      selectedOfferingIds: ['100'],
    },
  }
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname.replace('/api/v1', ''),
      method = request.method()
    const body = request.postDataJSON()
    const ok = (data: unknown, status = 200) =>
      route.fulfill({
        status,
        json: { data, requestId: 'test-request' },
        headers: { ETag: '"0"', 'Cache-Control': 'no-store' },
      })
    const fail = (status: number, code: string, message: string) =>
      route.fulfill({ status, json: { code, message, requestId: 'test-request' } })
    if (method !== 'GET') state.headers.push({ path, headers: request.headers(), body })
    if (path === '/auth/csrf') {
      state.csrfCalls++
      return ok({ token: `csrf-${state.csrfCalls}`, headerName: 'X-CSRF-TOKEN' })
    }
    if (path === '/auth/me')
      return state.user ? ok(state.user) : fail(401, 'UNAUTHENTICATED', '请登录')
    if (path === '/auth/login') {
      if (state.loginError) return fail(401, 'INVALID_CREDENTIALS', '账号或密码错误')
      state.user = {
        accountId: '10',
        personId: role === 'STUDENT' ? '20' : '30',
        role,
        displayName: '测试用户',
        username: 'tester',
      }
      return ok(state.user)
    }
    if (path === '/auth/logout') {
      state.user = null
      return route.fulfill({ status: 204 })
    }
    if (!state.user) return fail(401, 'UNAUTHENTICATED', '请登录')
    if (path === '/semesters') return ok(state.semesters)
    if (path.includes('/offering-availability')) {
      if (state.availability401) {
        state.availability401 = false
        state.user = null
        return fail(401, 'UNAUTHENTICATED', '会话已过期')
      }
      return ok(state.offerings.filter((o) => url.searchParams.getAll('ids').includes(o.id)))
    }
    if (/^\/semesters\/\d+\/offerings$/.test(path)) {
      if (state.catalogError) return fail(503, 'CATALOG_UNAVAILABLE', '目录暂不可用，请稍后重试')
      const filtered = state.offerings.filter(
        (o) =>
          !url.searchParams.get('keyword') ||
          o.courseName.includes(url.searchParams.get('keyword')!),
      )
      return ok({
        items: filtered,
        page: 1,
        pageSize: 10,
        total: filtered.length,
        snapshotVerifiedAt: iso(0),
        departments: ['计算机学院'],
      })
    }
    if (/^\/offerings\/\d+$/.test(path))
      return ok(state.offerings.find((o) => o.id === path.split('/').at(-1)))
    if (path.startsWith('/students/me/schedules/')) {
      if (path.endsWith('/draft')) {
        state.savedDraft = body
        state.schedule.version++
        state.schedule.draftRevision++
        state.schedule.draftChoices = [
          ...body.primaryOfferingIds.map((id: string, i: number) => ({
            offeringId: id,
            kind: 'PRIMARY',
            priority: i + 1,
          })),
          ...body.alternateOfferingIds.map((id: string, i: number) => ({
            offeringId: id,
            kind: 'ALTERNATE',
            priority: i + 1,
          })),
        ]
        return ok(state.schedule)
      }
      if (path.endsWith('/validate'))
        return ok({
          valid: !state.validationError,
          issues: state.validationError
            ? [
                {
                  code: 'PREREQUISITE_UNMET',
                  message: '数据库原理：先修课未通过',
                  offeringId: '101',
                },
              ]
            : [],
          affectedOfferings: state.offerings,
        })
      if (path.endsWith('/submit')) {
        state.submissions++
        if (state.staleSubmit) return fail(412, 'PRECONDITION_FAILED', '课表版本已变化')
        state.schedule.submittedChoices = structuredClone(state.schedule.draftChoices)
        state.schedule.submittedRevision = state.schedule.draftRevision
        state.schedule.status = 'REGISTERED'
        state.schedule.firstSubmittedAt = iso(0)
        state.schedule.version++
        state.schedule.enrollments = state.schedule.draftChoices
          .filter((c) => c.kind === 'PRIMARY')
          .map((c, i) => ({
            id: String(900 + i),
            offeringId: c.offeringId,
            state: 'ENROLLED',
            source: 'PRIMARY',
            offering: state.offerings.find((o) => o.id === c.offeringId),
          }))
        return ok(state.schedule)
      }
      if (method === 'DELETE') {
        state.deletes++
        state.schedule = { ...initialSchedule(), status: 'WITHDRAWN', version: 8 }
        return route.fulfill({ status: 204 })
      }
      return ok(state.schedule)
    }
    if (path.startsWith('/students/me/report-cards/'))
      return ok({
        semesterId: '2',
        semesterName: previous.name,
        items: [null, 'I', 'A'].map((g, i) => ({
          enrollmentId: String(800 + i),
          offeringId: String(100 + i),
          courseCode: offerings[i].courseCode,
          courseName: offerings[i].courseName,
          sectionCode: '01',
          professorName: '测试教师',
          grade: g,
          version: g === null ? null : 0,
        })),
      })
    if (path.startsWith('/professors/me/teaching/')) {
      if (method === 'PUT') {
        state.teaching.selectedOfferingIds = body.offeringIds
        state.teaching.selectedOfferings = state.offerings.filter((o) =>
          body.offeringIds.includes(o.id),
        )
        state.teaching.version++
      }
      return ok(state.teaching)
    }
    const roster = {
      offering: state.offerings[0],
      canEditGrades: true,
      students: [
        { enrollmentId: '900', studentId: '20', studentName: '张同学', grade: null, version: null },
        { enrollmentId: '901', studentId: '21', studentName: '李同学', grade: 'A', version: 0 },
        { enrollmentId: '902', studentId: '22', studentName: '王同学', grade: null, version: null },
      ],
    }
    if (path.endsWith('/grades')) {
      state.grades = body
      return ok(roster)
    }
    if (path.endsWith('/roster')) return ok(roster)
    const person = {
      id: '200',
      username: 'student200',
      name: '测试学生',
      birthDate: '2004-01-01',
      status: 'ACTIVE',
      graduationDate: null,
      department: '计算机学院',
      ssnMasked: '***-**-0001',
      version: 0,
    }
    if (/^\/registrar\/(students|professors)/.test(path)) {
      if (method === 'DELETE')
        return state.personnelConflict
          ? fail(409, 'ACTIVE_ASSOCIATION', '存在当前注册关联，无法删除')
          : route.fulfill({ status: 204 })
      if (method === 'POST' || method === 'PUT') {
        state.newPerson = body
        return ok({ ...person, ...body, version: 1 })
      }
      if (/\/\d+$/.test(path)) return ok(person)
      return ok({ items: [person], page: 1, pageSize: 20, total: 1 })
    }
    if (path.endsWith('/close-result'))
      return state.closeResult
        ? ok(state.closeResult)
        : fail(404, 'REGISTRATION_NOT_CLOSED', '注册尚未关闭')
    if (path.endsWith('/close')) {
      state.closeCount++
      if (state.closeBusy) return fail(409, 'REGISTRATION_BUSY', '有注册变更正在处理，请稍后重试')
      state.semesters[0].status = 'CLOSED'
      state.closeResult = {
        runId: 'run-1',
        semesterId: '1',
        closedAt: iso(0),
        cancelledOfferings: 1,
        leveledSchedules: 2,
        billingEvents: 1,
        pendingBillingEvents: 1,
        offerings: [
          {
            offeringId: '100',
            courseName: '软件工程',
            sectionCode: '01',
            status: 'CANCELLED',
            enrolledCount: 0,
            cancelReason: 'BELOW_MINIMUM',
          },
        ],
      }
      return ok(state.closeResult)
    }
    if (path.endsWith('/billing-events'))
      return ok({
        items: [
          {
            eventId: 'event-1',
            studentId: '20',
            state: 'RETRY',
            attempts: 2,
            nextAttemptAt: iso(0),
            lastError: '财务服务暂不可达',
          },
        ],
        total: 1,
        page: 1,
        pageSize: 20,
      })
    return fail(404, 'NOT_FOUND', `测试中未声明接口：${path}`)
  })
  return state
}
