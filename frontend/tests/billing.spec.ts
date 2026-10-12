import { test, expect, type Page } from '@playwright/test'
import { setup } from './fixture'

function invoice(semesterId = '2') {
  return {
    id: 'invoice-50',
    semesterId,
    semesterName: '已关闭测试学期',
    studentId: '20',
    studentName: '测试学生',
    totalAmount: '1234.56',
    currency: 'CNY',
    createdAt: '2026-09-01T08:00:00Z',
    deliveryState: 'RETRY',
    deliveredAt: null,
    items: [
      {
        offeringId: '101',
        courseCode: 'CS2',
        courseName: '关闭时的数据库原理',
        sectionCode: '02',
        professorName: '快照教师',
        amount: '1234.56',
      },
    ],
  }
}

async function setupStudent(page: Page) {
  const state = await setup(page)
  await page.route('**/api/v1/students/me/invoices/*', (route) => {
    const semesterId = new URL(route.request().url()).pathname.split('/').at(-1)
    return semesterId === '2'
      ? route.fulfill({ json: { data: invoice() } })
      : route.fulfill({
          status: 409,
          json: { code: 'INVOICE_NOT_READY', message: '注册尚未最终关闭，账单尚未生成' },
        })
  })
  return state
}

test('学生导航提供账单入口，关闭后尚未结束的学期也能查看账单明细', async ({ page }) => {
  const state = await setupStudent(page)
  state.semesters[1].endDate = state.semesters[0].endDate
  await page.goto('/student/schedule')
  await page.getByRole('link', { name: '我的账单', exact: true }).click()
  await expect(page).toHaveURL(/student\/billing/)
  await expect(page.getByText('账单已生成', { exact: true })).toBeVisible()
  await expect(page.locator('.invoice-total')).toHaveText('1234.56 CNY')
  await expect(page.getByText('关闭时的数据库原理', { exact: true })).toBeVisible()
  await expect(page.getByText('快照教师', { exact: true })).toBeVisible()
  await expect(page.getByText('等待重试', { exact: true })).toBeVisible()
  expect(state.headers).toHaveLength(0)
})

test('零金额账单保留账单信息，与没有账单区别展示', async ({ page }) => {
  await setupStudent(page)
  await page.route('**/api/v1/students/me/invoices/2', (route) =>
    route.fulfill({ json: { data: { ...invoice(), totalAmount: '0.00', items: [] } } }),
  )
  await page.goto('/student/billing')
  await expect(page.locator('.invoice-total')).toHaveText('0.00 CNY')
  await expect(
    page.getByText('本学期最终有效课程为零，无应缴学费，已生成零金额账单。'),
  ).toBeVisible()
  await expect(page.getByText('账单已生成', { exact: true })).toBeVisible()
})

test('未最终关闭和未提交选课分别说明尚未出账与无账单', async ({ page }) => {
  await setupStudent(page)
  await page.route('**/api/v1/students/me/invoices/2', (route) =>
    route.fulfill({
      status: 404,
      json: {
        code: 'INVOICE_NOT_FOUND',
        message: '该学期没有账单，仅有草稿或从未提交选课不会生成账单',
      },
    }),
  )
  await page.goto('/student/billing')
  await expect(page.getByText('该学期没有账单，仅有草稿或从未提交选课不会生成账单')).toBeVisible()
  await page
    .locator('.el-select')
    .filter({ has: page.getByRole('combobox', { name: '选择学期' }) })
    .click()
  await page.getByRole('option', { name: '当前测试学期', exact: true }).click()
  await expect(page.getByText('注册尚未最终关闭，账单尚未生成')).toBeVisible()
  await expect(page.locator('.invoice-total')).toHaveCount(0)
})

test('普通接口错误保持真实错误，重试后可读取已生成账单', async ({ page }) => {
  await setupStudent(page)
  let failed = true
  await page.route('**/api/v1/students/me/invoices/2', (route) =>
    failed
      ? route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: '账单接口不可用' } })
      : route.fulfill({ json: { data: invoice() } }),
  )
  await page.goto('/student/billing')
  await expect(page.getByText('账单接口不可用')).toBeVisible()
  await expect(page.locator('.el-empty')).toHaveCount(0)
  failed = false
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(page.locator('.invoice-total')).toHaveText('1234.56 CNY')
})

test('切换学期后旧账单响应不会覆盖当前学期的未出账提示', async ({ page }) => {
  await setupStudent(page)
  let release!: () => void
  let started = false
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/students/me/invoices/2', async (route) => {
    started = true
    await gate
    await route.fulfill({ json: { data: invoice() } })
  })
  await page.goto('/student/billing')
  await expect.poll(() => started).toBe(true)
  await expect(page.locator('.el-empty')).toHaveCount(0)
  await page
    .locator('.el-select')
    .filter({ has: page.getByRole('combobox', { name: '选择学期' }) })
    .click()
  await page.getByRole('option', { name: '当前测试学期', exact: true }).click()
  await expect(page.getByText('注册尚未最终关闭，账单尚未生成')).toBeVisible()
  const response = page.waitForResponse('**/api/v1/students/me/invoices/2')
  release()
  await response
  await expect(page.locator('.invoice-total')).toHaveCount(0)
  await expect(page.getByText('注册尚未最终关闭，账单尚未生成')).toBeVisible()
})

test('没有学期时显示空状态并且不请求账单', async ({ page }) => {
  const state = await setupStudent(page)
  state.semesters.splice(0)
  const requests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/invoices/')) requests.push(request.url())
  })
  await page.goto('/student/billing')
  await expect(page.getByText('暂无学期，暂时无法查询账单')).toBeVisible()
  expect(requests).toHaveLength(0)
})

test('教务能从财务通知查看账单，读取失败后可重试', async ({ page }) => {
  const state = await setup(page, 'REGISTRAR')
  state.closeResult = {
    runId: 'run-1',
    semesterId: '1',
    closedAt: new Date().toISOString(),
    cancelledOfferings: 0,
    leveledSchedules: 0,
    billingEvents: 1,
    pendingBillingEvents: 1,
    offerings: [],
  }
  let failed = true
  await page.route('**/api/v1/registrar/semesters/1/students/20/invoice', (route) =>
    failed
      ? route.fulfill({ status: 503, json: { code: 'UNAVAILABLE', message: '账单读取暂时失败' } })
      : route.fulfill({ json: { data: invoice('1') } }),
  )
  await page.goto('/registrar/registration')
  await page.getByRole('button', { name: '查看账单', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '学费账单', exact: true })
  await expect(dialog.getByText('账单读取暂时失败')).toBeVisible()
  failed = false
  await dialog.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(dialog.locator('.invoice-total')).toHaveText('1234.56 CNY')
  await expect(dialog.getByText('关闭时的数据库原理', { exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).toHaveCount(0)
})

test('教师没有学生账单入口，直接访问受角色路由保护', async ({ page }) => {
  await setup(page, 'PROFESSOR')
  await page.goto('/student/billing')
  await expect(page).toHaveURL(/forbidden/)
  await expect(page.getByRole('link', { name: '我的账单', exact: true })).toHaveCount(0)
})
