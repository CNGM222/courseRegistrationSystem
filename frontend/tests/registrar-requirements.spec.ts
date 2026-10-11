import { test, expect } from '@playwright/test'
import { setup } from './fixture'

function closedResult() {
  return {
    runId: 'registrar-final-run',
    semesterId: '1',
    closedAt: new Date().toISOString(),
    cancelledOfferings: 1,
    leveledSchedules: 1,
    billingEvents: 2,
    pendingBillingEvents: 2,
    offerings: [
      {
        offeringId: '100',
        courseName: '软件工程',
        sectionCode: '01',
        status: 'CLOSED',
        enrolledCount: 3,
      },
      {
        offeringId: '101',
        courseName: '数据库原理',
        sectionCode: '01',
        status: 'CANCELLED',
        enrolledCount: 0,
        cancelReason: 'NO_PROFESSOR',
      },
    ],
  }
}

test('教务关闭结果优先于旧学期状态，最终名单区分主选和备选且不展示成绩', async ({ page }) => {
  const state = await setup(page, 'REGISTRAR')
  state.closeResult = closedResult()
  state.semesters[0].addDropEnd = new Date(Date.now() - 1000).toISOString()
  let rosterReads = 0
  await page.route('**/api/v1/registrar/semesters/1/offerings/100/roster', async (route) => {
    rosterReads++
    await route.fulfill({
      json: {
        data: {
          semesterId: '1',
          offering: { ...state.offerings[0], status: 'CLOSED' },
          students: [
            { enrollmentId: '901', studentId: '20', studentName: '主选学生', source: 'PRIMARY' },
            { enrollmentId: '902', studentId: '21', studentName: '补位学生', source: 'ALTERNATE' },
          ],
        },
      },
    })
  })
  await page.goto('/registrar/registration')
  await expect(page.locator('.stat-value').first()).toHaveText('已关闭')
  await expect(page.getByRole('button', { name: '关闭注册', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /^查看.*班最终名单$/ })).toHaveCount(1)
  await page.getByRole('button', { name: '查看软件工程01班最终名单', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '软件工程 01 班 · 最终名单' })
  await expect(dialog.getByText('主选学生', { exact: true })).toBeVisible()
  await expect(dialog.getByText('补位学生', { exact: true })).toBeVisible()
  await expect(dialog.getByText('主选', { exact: true })).toBeVisible()
  await expect(dialog.getByText('备选补位', { exact: true })).toBeVisible()
  await expect(dialog.getByText('成绩', { exact: true })).toHaveCount(0)
  expect(rosterReads).toBe(1)
  expect(state.closeCount).toBe(0)
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0])
})

test('最终名单读取失败显示原因并可重试，不伪装成空名单', async ({ page }) => {
  const state = await setup(page, 'REGISTRAR')
  state.closeResult = closedResult()
  let fail = true
  await page.route('**/api/v1/registrar/semesters/1/offerings/100/roster', async (route) => {
    await route.fulfill(
      fail
        ? {
            status: 503,
            json: { code: 'ROSTER_UNAVAILABLE', message: '最终名单暂不可用' },
          }
        : {
            json: {
              data: {
                semesterId: '1',
                offering: state.offerings[0],
                students: [
                  {
                    enrollmentId: '901',
                    studentId: '20',
                    studentName: '主选学生',
                    source: 'PRIMARY',
                  },
                ],
              },
            },
          },
    )
  })
  await page.goto('/registrar/registration')
  await page.getByRole('button', { name: '查看软件工程01班最终名单' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('最终名单暂不可用', { exact: true })).toBeVisible()
  await expect(dialog.getByText('该教学班没有最终注册学生')).toHaveCount(0)
  fail = false
  await dialog.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(dialog.getByText('主选学生', { exact: true })).toBeVisible()
})

test('关闭请求连接中断必须查询结果，查询成功不会重放关闭', async ({ page }) => {
  const state = await setup(page, 'REGISTRAR')
  state.semesters[0].addDropEnd = new Date(Date.now() - 1000).toISOString()
  let closeWrites = 0
  await page.route('**/api/v1/registrar/semesters/1/close', async (route) => {
    closeWrites++
    state.closeResult = closedResult()
    await route.abort('connectionreset')
  })
  await page.goto('/registrar/registration')
  await page.getByRole('button', { name: '关闭注册', exact: true }).click()
  await page.getByRole('button', { name: '确认关闭', exact: true }).click()
  await expect(page.getByText(/关闭请求的结果尚未确认/)).toBeVisible()
  await expect(page.getByRole('button', { name: '关闭注册', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '查询最新结果', exact: true }).click()
  await expect(page.getByText('教学班最终结果', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '关闭注册', exact: true })).toHaveCount(0)
  expect(closeWrites).toBe(1)
})

test('教务员可修改加退课截止时间并立即截止选课而不提前结算', async ({ page }) => {
  const state = await setup(page, 'REGISTRAR')
  let update: { body: { addDropEnd: string }; ifMatch: string | undefined } | null = null
  await page.route('**/api/v1/registrar/semesters/1/registration-deadline', async (route) => {
    update = {
      body: route.request().postDataJSON(),
      ifMatch: route.request().headers()['if-match'],
    }
    state.semesters[0].addDropEnd = update.body.addDropEnd
    state.semesters[0].version++
    await route.fulfill({ json: { data: state.semesters[0] } })
  })
  await page.goto('/registrar/registration')
  await page.getByRole('button', { name: '修改截止时间', exact: true }).click()
  const deadlineDialog = page.getByRole('dialog', { name: '修改加退课截止时间' })
  const localDeadline = new Date(Date.now() + 20 * 86400000 + 8 * 3600000)
    .toISOString()
    .slice(0, 16)
  await deadlineDialog.getByLabel('截止时间（北京时间）').fill(localDeadline)
  await deadlineDialog.getByRole('button', { name: '保存截止时间', exact: true }).click()
  await expect(deadlineDialog).toHaveCount(0)
  expect(update?.ifMatch).toBe('"0"')
  expect(Date.parse(update!.body.addDropEnd)).toBeGreaterThan(Date.now())
  expect(Date.parse(update!.body.addDropEnd)).toBeGreaterThan(Date.parse(state.semesters[0].registrationStart))

  await page.getByRole('button', { name: '立即截止选课', exact: true }).click()
  await page.getByRole('button', { name: '立即截止', exact: true }).click()
  await expect(page.locator('.stat-value').first()).toHaveText('已截止，待结算')
  await expect(page.getByRole('button', { name: '关闭注册', exact: true })).toBeEnabled()
  expect(state.cutoffCount).toBe(1)
  expect(state.closeCount).toBe(0)
})

test('教务员可直接重置学生密码，使用账号版本并要求确认两次输入', async ({ page }) => {
  const state = await setup(page, 'REGISTRAR')
  await page.goto('/registrar/students')
  await page.getByRole('button', { name: '重置密码', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '重置登录密码' })
  await dialog.getByLabel('新密码', { exact: true }).fill('new-student-login-password')
  await dialog.getByLabel('确认新密码', { exact: true }).fill('new-student-login-password')
  await dialog.getByRole('button', { name: '保存新密码', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  expect(state.passwordResets).toEqual([
    {
      kind: 'students',
      id: '200',
      password: 'new-student-login-password',
      accountVersion: '"0"',
    },
  ])
})

test('教务员可直接重置教师密码', async ({ page }) => {
  const state = await setup(page, 'REGISTRAR')
  await page.goto('/registrar/professors')
  await page.getByRole('button', { name: '重置密码', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '重置登录密码' })
  await dialog.getByLabel('新密码', { exact: true }).fill('new-professor-login-password')
  await dialog.getByLabel('确认新密码', { exact: true }).fill('new-professor-login-password')
  await dialog.getByRole('button', { name: '保存新密码', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  expect(state.passwordResets).toEqual([
    {
      kind: 'professors',
      id: '200',
      password: 'new-professor-login-password',
      accountVersion: '"0"',
    },
  ])
})

test('财务筛选和分页请求正确，恢复焦点即时刷新送达状态', async ({ page }) => {
  const state = await setup(page, 'REGISTRAR')
  state.closeResult = closedResult()
  const reads: { state: string | null; page: string | null }[] = []
  let delivered = false
  await page.route('**/api/v1/registrar/semesters/1/billing-events?**', async (route) => {
    const params = new URL(route.request().url()).searchParams
    reads.push({ state: params.get('state'), page: params.get('page') })
    await route.fulfill({
      json: {
        data: {
          items: [
            {
              eventId: `billing-${params.get('page')}`,
              studentId: '20',
              studentName: '财务测试学生',
              state: delivered ? 'DELIVERED' : 'RETRY',
              attempts: delivered ? 3 : 2,
              deliveredAt: delivered ? new Date().toISOString() : null,
              nextAttemptAt: delivered ? null : new Date().toISOString(),
              lastError: delivered ? null : '财务暂不可用',
            },
          ],
          page: Number(params.get('page')),
          pageSize: 20,
          total: 21,
        },
      },
    })
  })
  await page.goto('/registrar/registration')
  await expect(page.getByText('财务测试学生', { exact: true })).toBeVisible()
  await page
    .locator('.el-select')
    .filter({ has: page.getByRole('combobox', { name: '筛选通知状态' }) })
    .click()
  await page.getByRole('option', { name: '等待重试', exact: true }).click()
  await expect.poll(() => reads.at(-1)).toEqual({ state: 'RETRY', page: '1' })
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await expect.poll(() => reads.at(-1)).toEqual({ state: 'RETRY', page: '2' })
  await expect(page.getByText('billing-2', { exact: true })).toBeVisible()
  delivered = true
  const count = reads.length
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect.poll(() => reads.length).toBe(count + 1)
  await expect(page.locator('.el-table .el-tag').filter({ hasText: '已送达' })).toBeVisible()
  await expect(page.getByText('财务暂不可用', { exact: true })).toHaveCount(0)
})

test('后端师生字段错误定位到对应表单，修改字段后可再次保存', async ({ page }) => {
  const state = await setup(page, 'REGISTRAR')
  let saves = 0
  await page.route('**/api/v1/registrar/students/200', async (route) => {
    if (route.request().method() !== 'PUT') return route.fallback()
    saves++
    if (saves === 1)
      return route.fulfill({
        status: 422,
        json: {
          code: 'VALIDATION_ERROR',
          message: '请修正姓名',
          details: [{ field: 'name', message: '姓名不符合资料要求' }],
        },
      })
    return route.fallback()
  })
  await page.goto('/registrar/students')
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('姓名', { exact: true }).fill('待核对姓名')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(
    dialog.locator('.el-form-item').filter({ hasText: '姓名' }).locator('.el-form-item__error'),
  ).toHaveText('姓名不符合资料要求')
  await dialog.getByLabel('姓名', { exact: true }).fill('更正姓名')
  await expect(dialog.getByText('姓名不符合资料要求')).toHaveCount(0)
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect.poll(() => state.newPerson?.name).toBe('更正姓名')
  expect(saves).toBe(2)
})

test('师生删除连接中断后禁止重复删除，重新查询后恢复操作', async ({ page }) => {
  await setup(page, 'REGISTRAR')
  let deletes = 0
  await page.route('**/api/v1/registrar/students/200', async (route) => {
    if (route.request().method() !== 'DELETE') return route.fallback()
    deletes++
    return route.abort('connectionreset')
  })
  await page.goto('/registrar/students')
  await page.getByRole('button', { name: '删除', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '确认删除' })
  await confirmation.getByRole('button', { name: '删除', exact: true }).click()
  await expect(confirmation).toHaveCount(0)
  await expect(page.getByText(/删除结果或资料版本尚未确认/)).toBeVisible()
  const deleteAction = page.locator('.el-table').getByRole('button', { name: '删除', exact: true })
  await expect(deleteAction).toBeDisabled()
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await expect(deleteAction).toBeEnabled()
  expect(deletes).toBe(1)
})
