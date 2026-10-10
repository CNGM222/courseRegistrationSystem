import { expect, test, type Page } from '@playwright/test'
import { setup, offerings, previous } from './fixture'

async function setGrade(page: Page, studentName: string, grade: string) {
  await page.getByRole('combobox', { name: `${studentName}的成绩` }).press('Enter')
  await page
    .getByRole('listbox', { name: `${studentName}的成绩` })
    .getByRole('option', { name: grade, exact: true })
    .click()
}

function selectRoot(page: Page, name: string) {
  return page
    .getByRole('combobox', { name })
    .locator(
      'xpath=ancestor::div[contains(concat(" ", normalize-space(@class), " "), " el-select ")]',
    )
}

function selectedText(page: Page, name: string) {
  return selectRoot(page, name).locator('.el-select__selected-item:not(.is-hidden)')
}

function visibleTeachingCheckbox(page: Page, courseName: string) {
  return page.locator('.el-table__body tr').filter({ hasText: courseName }).locator('.el-checkbox')
}

test('教师默认查询最近已结束学期，当前班级名单只读且返回时保留学期', async ({ page }) => {
  await setup(page, 'PROFESSOR')
  const queried: string[] = []
  await page.route('**/api/v1/professors/me/teaching/*', async (route) => {
    const semesterId = new URL(route.request().url()).pathname.split('/').at(-1)!
    queried.push(semesterId)
    const offering = { ...offerings[0], semesterId }
    await route.fulfill({
      json: {
        data: {
          version: 0,
          eligibleOfferings: [],
          selectedOfferingIds: [offering.id],
          selectedOfferings: [offering],
        },
      },
    })
  })
  await page.route('**/api/v1/professors/me/offerings/100/roster', (route) =>
    route.fulfill({
      json: {
        data: {
          offering: offerings[0],
          canEditGrades: false,
          students: [
            {
              enrollmentId: '900',
              studentId: '20',
              studentName: '当前学生',
              grade: null,
              version: null,
            },
          ],
        },
      },
    }),
  )
  await page.goto('/professor/classes')
  await expect.poll(() => queried).toEqual(['2'])
  await expect(selectedText(page, '选择学期')).toHaveText(previous.name)
  await selectRoot(page, '选择学期').click()
  await page.getByRole('option', { name: '当前测试学期', exact: true }).click()
  await expect.poll(() => queried).toEqual(['2', '1'])
  await page.getByRole('link', { name: /查看名单与成绩/ }).click()
  await expect(page.getByText('当前学生', { exact: true })).toBeVisible()
  await expect(page.getByText('本教学班当前只允许查看名单，成绩尚不可编辑。')).toBeVisible()
  await expect(page.getByRole('combobox', { name: /的成绩/ })).toHaveCount(0)
  await page.getByRole('link', { name: '← 返回教学班' }).click()
  await expect.poll(() => queried).toEqual(['2', '1', '1'])
  await expect(page).toHaveURL(/classes\?semesterId=1/)
})

test('逐人保存只提交该行，保留其他待保存成绩与版本存在性', async ({ page }) => {
  await setup(page, 'PROFESSOR')
  const roster = {
    offering: { ...offerings[0], semesterId: '2', status: 'CLOSED' },
    canEditGrades: true,
    students: [
      {
        enrollmentId: '900',
        studentId: '20',
        studentName: '张同学',
        grade: null as string | null,
        version: null as number | null,
      },
      { enrollmentId: '901', studentId: '21', studentName: '李同学', grade: 'A', version: 0 },
    ],
  }
  const bodies: any[] = []
  await page.route('**/api/v1/professors/me/offerings/100/*', async (route) => {
    if (route.request().method() === 'PUT') {
      const body = route.request().postDataJSON()
      bodies.push(body)
      for (const entry of body.entries) {
        const student = roster.students.find((item) => item.enrollmentId === entry.enrollmentId)!
        student.grade = entry.grade
        student.version = student.version === null ? 0 : student.version + 1
      }
    }
    await route.fulfill({ json: { data: roster } })
  })
  await page.goto('/professor/offerings/100/roster')
  await setGrade(page, '张同学', 'B')
  await setGrade(page, '李同学', 'C')
  await page.getByRole('button', { name: '保存张同学的成绩', exact: true }).click()
  await page.getByRole('button', { name: '确认保存', exact: true }).click()
  await expect.poll(() => bodies.length).toBe(1)
  expect(bodies[0].entries).toEqual([{ enrollmentId: '900', grade: 'B', expectedVersion: null }])
  await expect(selectedText(page, '李同学的成绩')).toHaveText('C')
  await expect(page.getByRole('button', { name: '保存 1 项修改', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '保存 1 项修改', exact: true }).click()
  await page.getByRole('button', { name: '确认保存', exact: true }).click()
  await expect.poll(() => bodies.length).toBe(2)
  expect(bodies[1].entries).toEqual([{ enrollmentId: '901', grade: 'C', expectedVersion: 0 }])
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0])
})

test('成绩字段错误按实际请求行定位，不按整个名单的索引定位', async ({ page }) => {
  await setup(page, 'PROFESSOR')
  await page.route('**/api/v1/professors/me/offerings/100/grades', (route) =>
    route.fulfill({
      status: 422,
      json: {
        code: 'VALIDATION_ERROR',
        message: '请修正成绩',
        details: [{ field: 'entries[0].grade', message: '此成绩值无效' }],
      },
    }),
  )
  await page.goto('/professor/offerings/100/roster')
  await setGrade(page, '李同学', 'C')
  await page.getByRole('button', { name: '保存 1 项修改', exact: true }).click()
  await page.getByRole('button', { name: '确认保存', exact: true }).click()
  const secondRow = page.locator('.el-table__body tbody tr').filter({ hasText: '李同学' })
  await expect(secondRow.locator('.field-error')).toHaveText('此成绩值无效')
  await expect(
    page.locator('.el-table__body tbody tr').filter({ hasText: '张同学' }).locator('.field-error'),
  ).toHaveCount(0)
  await expect(selectedText(page, '李同学的成绩')).toHaveText('C')
})

test('授课方案失败保持已保存安排，显示具体教学班错误并停止重复提交', async ({ page }) => {
  const state = await setup(page, 'PROFESSOR')
  let requestBody: any = null
  await page.route('**/api/v1/professors/me/teaching/1', async (route) => {
    if (route.request().method() === 'GET') return route.fallback()
    requestBody = route.request().postDataJSON()
    await route.fulfill({
      status: 409,
      json: {
        code: 'TEACHING_CONFLICT',
        message: '授课方案存在冲突',
        details: [{ offeringId: '101', message: '数据库原理：第 1 周周二 1–2 节已被其他教师选择' }],
      },
    })
  })
  await page.goto('/professor/teaching')
  await visibleTeachingCheckbox(page, '软件工程').click()
  await visibleTeachingCheckbox(page, '数据库原理').click()
  await expect(page.getByText('待取消', { exact: true })).toBeVisible()
  await expect(page.getByText('待新增', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '保存授课方案', exact: true }).click()
  await page.getByRole('button', { name: '保存方案', exact: true }).click()
  await expect.poll(() => requestBody).toEqual({ offeringIds: ['101'] })
  await expect(page.locator('.field-error')).toHaveText(
    '数据库原理：第 1 周周二 1–2 节已被其他教师选择',
  )
  await expect(page.getByRole('button', { name: '保存授课方案', exact: true })).toBeDisabled()
  await expect(
    page.locator('.surface').first().getByRole('button', { name: '软件工程', exact: true }),
  ).toBeVisible()
  await expect(
    page.locator('.surface').first().getByRole('button', { name: '数据库原理', exact: true }),
  ).toHaveCount(0)
  expect(state.teaching.selectedOfferingIds).toEqual(['100'])
})

for (const [path, endpoint, emptyText] of [
  ['/professor/teaching', '**/api/v1/professors/me/teaching/*', '本学期暂无可选择的授课教学班'],
  ['/professor/classes', '**/api/v1/professors/me/teaching/*', '本学期暂无本人授课教学班'],
  [
    '/professor/offerings/100/roster',
    '**/api/v1/professors/me/offerings/100/roster',
    '暂无正式注册学生',
  ],
])
  test(`${path} 请求失败不展示业务空状态`, async ({ page }) => {
    await setup(page, 'PROFESSOR')
    await page.route(endpoint, (route) =>
      route.fulfill({
        status: 503,
        json: { code: 'DEPENDENCY_UNAVAILABLE', message: '教学数据暂不可用，请稍后重试' },
      }),
    )
    await page.goto(path)
    await expect(page.getByText('教学数据暂不可用，请稍后重试')).toBeVisible()
    await expect(page.getByText(emptyText, { exact: true })).toHaveCount(0)
  })

test('切换同一路由的教学班时可取消丢弃修改，确认后读取目标名单', async ({ page }) => {
  await setup(page, 'PROFESSOR')
  const queried: string[] = []
  await page.route('**/api/v1/professors/me/offerings/*/roster', async (route) => {
    const id = new URL(route.request().url()).pathname.split('/').at(-2)!
    queried.push(id)
    await route.fulfill({
      json: {
        data: {
          offering: offerings.find((offering) => offering.id === id),
          canEditGrades: true,
          students: [
            {
              enrollmentId: `enrollment-${id}`,
              studentId: id,
              studentName: `班级${id}学生`,
              grade: null,
              version: null,
            },
          ],
        },
      },
    })
  })
  await page.goto('/professor/offerings/100/roster')
  await setGrade(page, '班级100学生', 'B')
  const navigate = () =>
    page.evaluate(() => {
      history.pushState({}, '', '/professor/offerings/101/roster')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
  await navigate()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page).toHaveURL(/offerings\/100\/roster/)
  await expect(selectedText(page, '班级100学生的成绩')).toHaveText('B')
  expect(queried).toEqual(['100'])
  await navigate()
  await page.getByRole('button', { name: '放弃修改', exact: true }).last().click()
  await expect(page.getByText('班级101学生', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '数据库原理', exact: true })).toBeVisible()
  await expect(page.getByRole('combobox', { name: '班级100学生的成绩' })).toHaveCount(0)
  expect(queried).toEqual(['100', '101'])
})
