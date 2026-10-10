import { test, expect, type Page } from '@playwright/test'
import { setup } from './fixture'

async function chooseSix(page: Page) {
  for (let i = 0; i < 4; i++)
    await page.getByRole('button', { name: '主选', exact: true }).nth(i).click()
  for (let i = 4; i < 6; i++)
    await page.getByRole('button', { name: '备选', exact: true }).nth(i).click()
}

function sixChoices() {
  return Array.from({ length: 6 }, (_, i) => ({
    offeringId: String(100 + i),
    kind: i < 4 ? 'PRIMARY' : 'ALTERNATE',
    priority: i < 4 ? i + 1 : i - 3,
  }))
}

test('学生可单独预检方案，查看结果后继续编辑且不注册占位', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/student/registration')
  await chooseSix(page)
  await page.getByRole('button', { name: '预检方案', exact: true }).click()
  await expect(
    page.getByText('方案预检通过，尚未正式提交；预检不占名额，正式提交时将再次校验。'),
  ).toBeVisible()
  expect(state.submissions).toBe(0)
  expect(state.schedule.enrollments).toEqual([])
  expect(state.savedDraft.primaryOfferingIds).toHaveLength(4)
  await page.getByRole('button', { name: '移除', exact: true }).first().click()
  await expect(
    page.getByText('方案预检通过，尚未正式提交；预检不占名额，正式提交时将再次校验。'),
  ).toHaveCount(0)
})

test('已有提交但没有草稿指针时，编辑器读取提交方案而非空方案', async ({ page }) => {
  const state = await setup(page)
  Object.assign(state.schedule, {
    draftRevision: null,
    submittedRevision: 7,
    firstSubmittedAt: new Date().toISOString(),
    status: 'REGISTERED',
    submittedChoices: sixChoices(),
  })
  await page.goto('/student/registration')
  await expect(page.locator('.choice-slot.filled')).toHaveCount(6)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect
    .poll(() => state.savedDraft?.primaryOfferingIds)
    .toEqual(['100', '101', '102', '103'])
  expect(state.savedDraft.alternateOfferingIds).toEqual(['104', '105'])
})

test('存在空草稿时保留明确清空的工作方案，不回填以前提交的课程', async ({ page }) => {
  const state = await setup(page)
  Object.assign(state.schedule, {
    submittedRevision: 5,
    firstSubmittedAt: new Date().toISOString(),
    submittedChoices: sixChoices(),
  })
  await page.goto('/student/registration')
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled()
  await expect(page.locator('.choice-slot.filled')).toHaveCount(0)
})

test('草稿版本零是有效指针，独立预检不额外保存草稿', async ({ page }) => {
  const state = await setup(page)
  state.schedule.draftRevision = 0
  state.schedule.draftChoices = sixChoices()
  await page.goto('/student/registration')
  await expect(page.locator('.choice-slot.filled')).toHaveCount(6)
  await page.getByRole('button', { name: '预检方案', exact: true }).click()
  await expect(
    page.getByText('方案预检通过，尚未正式提交；预检不占名额，正式提交时将再次校验。'),
  ).toBeVisible()
  expect(state.savedDraft).toBeNull()
  expect(state.headers.find((entry) => entry.path.endsWith('/validate'))?.body).toEqual({
    draftRevision: 0,
  })
})

test('正式提交确认期间跨过加退课截止时，不再发起注册写请求', async ({ page }) => {
  const testTime = Date.now()
  await page.clock.install({ time: new Date(testTime) })
  const state = await setup(page)
  state.semesters[0].addDropEnd = new Date(testTime + 60_000).toISOString()
  await page.goto('/student/registration')
  await chooseSix(page)
  await page.getByRole('button', { name: '预检并提交', exact: true }).click()
  await expect(page.getByText('确认提交选课方案')).toBeVisible()
  await page.clock.fastForward(61_000)
  await page.getByRole('button', { name: '正式提交', exact: true }).click()
  await expect(page.getByText('选课开放时段已结束，请读取最新课表核对状态')).toBeVisible()
  expect(state.submissions).toBe(0)
})

test('删除确认期间跨过加退课截止时，不再发起撤销课表请求', async ({ page }) => {
  const testTime = Date.now()
  await page.clock.install({ time: new Date(testTime) })
  const state = await setup(page)
  state.semesters[0].addDropEnd = new Date(testTime + 60_000).toISOString()
  await page.goto('/student/registration')
  await page.getByRole('button', { name: '删除整个课表', exact: true }).click()
  await expect(page.getByText('删除整个课表？', { exact: true })).toBeVisible()
  await page.clock.fastForward(61_000)
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.getByText('选课开放时段已结束，请读取最新课表核对状态')).toBeVisible()
  expect(state.deletes).toBe(0)
})

test('离开选课页面后到达的预检响应不会打开提交确认或发送注册请求', async ({ page }) => {
  const state = await setup(page)
  let release!: () => void
  let started = false
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/students/me/schedules/1/validate', async (route) => {
    started = true
    await gate
    await route.fallback()
  })
  await page.goto('/student/registration')
  await chooseSix(page)
  await page.getByRole('button', { name: '预检并提交', exact: true }).click()
  await expect.poll(() => started).toBe(true)
  await page.getByRole('link', { name: '当前课表', exact: true }).click()
  await expect(page).toHaveURL(/student\/schedule/)
  release()
  await expect(page.getByText('有效课表', { exact: true })).toBeVisible()
  await expect(page.getByText('确认提交选课方案')).toHaveCount(0)
  expect(state.submissions).toBe(0)
})

test('读取最新结果保留本地工作方案，目录详情故障不覆盖选课修改', async ({ page }) => {
  const state = await setup(page)
  await page.route('**/api/v1/offerings/108', (route) =>
    route.fulfill({
      status: 503,
      json: { code: 'CATALOG_UNAVAILABLE', message: '目录暂不可用，请稍后重试' },
    }),
  )
  await page.goto('/student/registration')
  await chooseSix(page)
  state.schedule.draftChoices = [{ offeringId: '108', kind: 'PRIMARY', priority: 1 }]
  state.schedule.enrollments = [
    {
      id: '997',
      offeringId: '107',
      state: 'ENROLLED',
      source: 'PRIMARY',
      offering: state.offerings[7],
    },
  ]
  await page.getByRole('button', { name: '读取最新课表', exact: true }).click()
  await expect(page.getByText(/已读取服务器最新课表，保留了本地方案/)).toBeVisible()
  await expect(page.locator('.choice-slot.filled')).toHaveCount(6)
  await page.getByRole('tab', { name: '当前有效课表', exact: true }).click()
  await expect(
    page
      .locator('.el-tab-pane')
      .filter({ hasText: '有效课表' })
      .getByText('计算机图形学', { exact: true }),
  ).toBeVisible()
  expect(state.submissions).toBe(0)
})

test('备选也显示最新容量并可查看完整教学班详情', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/student/registration')
  await chooseSix(page)
  state.offerings[4].enrolledCount = 9
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  const alternate = page.locator('.slot-grid.alternate .choice-slot').first()
  await expect(alternate.getByText('9 / 10 人 · 不提前占位')).toBeVisible()
  await alternate.getByRole('button', { name: '人工智能', exact: true }).click()
  await expect(page.getByRole('heading', { name: '教学班详情' })).toBeVisible()
  await expect(page.getByText('9 / 10（最低 3 人）')).toBeVisible()
})

test('周课表按真实教学周支持第三十六周，并说明没有课程安排的周', async ({ page }) => {
  const state = await setup(page)
  state.offerings[0].meetings[0].weekNo = 36
  state.schedule.status = 'REGISTERED'
  state.schedule.enrollments = [
    {
      id: '900',
      offeringId: '100',
      state: 'ENROLLED',
      source: 'PRIMARY',
      offering: state.offerings[0],
    },
  ]
  await page.goto('/student/schedule')
  await page.getByText('周课表', { exact: true }).click()
  await expect(page.getByText('第 1 周没有已注册课程的上课安排')).toBeVisible()
  await page.getByRole('spinbutton', { name: '教学周' }).fill('36')
  await page.getByRole('spinbutton', { name: '教学周' }).press('Enter')
  await expect(page.locator('.timeline-item').getByText('软件工程', { exact: true })).toBeVisible()
})

test('当前课表初次读取期间不提前显示不存在的课表', async ({ page }) => {
  await setup(page)
  let release!: () => void
  let started = false
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/students/me/schedules/1', async (route) => {
    started = true
    await gate
    await route.fallback()
  })
  await page.goto('/student/schedule')
  await expect.poll(() => started).toBe(true)
  await expect(page.getByText('本学期还没有课表', { exact: true })).toHaveCount(0)
  release()
  await expect(page.getByText('有效课表', { exact: true })).toBeVisible()
})

test('普通接口 404 显示真实错误，只有约定的课表不存在才展示历史空状态', async ({ page }) => {
  await setup(page)
  await page.route('**/api/v1/students/me/schedules/2', (route) =>
    route.fulfill({
      status: 404,
      json: { code: 'NOT_FOUND', message: '历史课表接口未实现' },
    }),
  )
  await page.goto('/student/history')
  await expect(page.getByText('历史课表接口未实现')).toBeVisible()
  await expect(page.getByText('该学期没有选课记录', { exact: true })).toHaveCount(0)
  await page.route('**/api/v1/students/me/schedules/2', (route) =>
    route.fulfill({
      status: 404,
      json: { code: 'SCHEDULE_NOT_FOUND', message: '本人课表不存在' },
    }),
  )
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.getByText('该学期没有选课记录', { exact: true })).toBeVisible()
})

test('没有已结束学期时，成绩单提供准确空状态', async ({ page }) => {
  const state = await setup(page)
  state.semesters.splice(1)
  await page.goto('/student/report-card')
  await expect(page.getByText('暂无已结束学期，学期结束后可查看成绩单')).toBeVisible()
  expect(state.headers.filter((entry) => entry.path.includes('report-cards'))).toHaveLength(0)
})

test('全部成绩未录入时提供学期提示，并保持未录入语义', async ({ page }) => {
  await setup(page)
  await page.route('**/api/v1/students/me/report-cards/2', (route) =>
    route.fulfill({
      json: {
        data: {
          semesterId: '2',
          semesterName: '历史学期',
          items: [
            {
              enrollmentId: '900',
              offeringId: '100',
              courseCode: 'CS1',
              courseName: '软件工程',
              sectionCode: '01',
              professorName: '测试教师',
              grade: null,
              version: null,
            },
          ],
        },
      },
      headers: { 'Cache-Control': 'no-store' },
    }),
  )
  await page.goto('/student/report-card')
  await expect(page.getByText('该学期所有课程成绩尚未录入，请稍后刷新查询。')).toBeVisible()
  await expect(page.getByText('未录入', { exact: true })).toBeVisible()
  await expect(page.getByText('F', { exact: true })).toHaveCount(0)
})

test('关闭后的学生课表只展示最终注册并记录主选取消与备选补位', async ({ page }) => {
  const state = await setup(page)
  state.semesters[0].status = 'CLOSED'
  state.semesters[0].addDropEnd = new Date(Date.now() - 1000).toISOString()
  Object.assign(state.schedule, {
    status: 'FINALIZED',
    draftRevision: null,
    submittedRevision: 6,
    firstSubmittedAt: new Date().toISOString(),
    submittedChoices: sixChoices(),
    enrollments: [
      {
        id: '900',
        offeringId: '100',
        state: 'CANCELLED',
        source: 'PRIMARY',
        reason: 'NO_PROFESSOR',
        offering: state.offerings[0],
      },
      {
        id: '901',
        offeringId: '101',
        state: 'ENROLLED',
        source: 'PRIMARY',
        offering: state.offerings[1],
      },
      {
        id: '904',
        offeringId: '104',
        state: 'ENROLLED',
        source: 'ALTERNATE',
        offering: state.offerings[4],
      },
    ],
  })
  await page.goto('/student/schedule')
  const enrolled = page.locator('.el-table__body-wrapper tbody')
  await expect(enrolled.getByText('数据库原理', { exact: true })).toBeVisible()
  await expect(enrolled.getByText('人工智能', { exact: true })).toBeVisible()
  await expect(enrolled.getByText('备选补位', { exact: true })).toBeVisible()
  await expect(enrolled.getByText('软件工程', { exact: true })).toHaveCount(0)
  await expect(enrolled.getByText('编译原理', { exact: true })).toHaveCount(0)
  await expect(page.locator('.history-row').filter({ hasText: '软件工程' })).toBeVisible()
  await expect(page.locator('.history-row').getByText('已取消 · 无授课教师')).toBeVisible()
  await page.getByRole('link', { name: '选课中心', exact: true }).click()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '预检并提交', exact: true })).toBeDisabled()
  expect(state.submissions).toBe(0)
})

test('最终零门课表仍保留关闭状态和取消记录，不显示为从未创建课表', async ({ page }) => {
  const state = await setup(page)
  state.semesters[0].status = 'CLOSED'
  Object.assign(state.schedule, {
    status: 'FINALIZED',
    enrollments: [
      {
        id: '900',
        offeringId: '100',
        state: 'CANCELLED',
        source: 'PRIMARY',
        reason: 'BELOW_MINIMUM',
        offering: state.offerings[0],
      },
    ],
  })
  await page.goto('/student/schedule')
  await expect(page.getByText('已确定', { exact: true })).toBeVisible()
  await expect(page.getByText('尚无有效注册课程，草稿与备选不在此处占位')).toBeVisible()
  await expect(page.locator('.history-row').getByText('已取消 · 不足三人')).toBeVisible()
  await expect(page.getByText('本学期还没有课表', { exact: true })).toHaveCount(0)
})
