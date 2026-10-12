<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import { HttpError } from '../../services/http'
import { useSemesters } from '../../composables/useSemesters'
import { dateTime, label } from '../../utils/format'
import type { CloseResult, BillingEvent, Invoice, RegistrarRoster } from '../../types/api'
import ErrorNotice from '../../components/ErrorNotice.vue'
import SemesterSelect from '../../components/SemesterSelect.vue'
import EmptyState from '../../components/EmptyState.vue'
import InvoiceDetail from '../../components/InvoiceDetail.vue'
const {
  choices,
  selectedId,
  current,
  error: semesterError,
  loading: semesterLoading,
  load: reloadSemesters,
} = useSemesters()
const result = ref<CloseResult | null>(null),
  events = ref<BillingEvent[]>([]),
  total = ref(0),
  page = ref(1),
  state = ref(''),
  busy = ref(false),
  loading = ref(false),
  billingLoading = ref(false)
const error = ref<unknown>(null),
  billingError = ref<unknown>(null),
  verified = ref(false),
  uncertain = ref(false),
  confirming = ref(false),
  deadlineDialog = ref(false),
  deadlineLocal = ref(''),
  deadlineError = ref(''),
  eventsUpdatedAt = ref<string | null>(null),
  now = ref(Date.now())
const registrationStatus = computed(() => {
  if (result.value) return 'CLOSED'
  if (current.value?.status === 'OPEN' && now.value >= Date.parse(current.value.addDropEnd))
    return 'REGISTRATION_ENDED'
  return current.value?.status
})
const rosterVisible = ref(false),
  roster = ref<RegistrarRoster | null>(null),
  rosterLoading = ref(false),
  rosterError = ref<unknown>(null),
  rosterOfferingId = ref(''),
  rosterTitle = ref('')
const invoiceVisible = ref(false),
  invoice = ref<Invoice | null>(null),
  invoiceStudentId = ref(''),
  invoiceLoading = ref(false),
  invoiceError = ref<unknown>(null)
let invoiceGeneration = 0
function clearInvoice() {
  invoiceGeneration++
  invoiceVisible.value = false
  invoice.value = null
  invoiceStudentId.value = ''
  invoiceLoading.value = false
  invoiceError.value = null
}
async function loadInvoice() {
  if (!active || !invoiceVisible.value || !selectedId.value || !invoiceStudentId.value) return
  const token = ++invoiceGeneration
  invoice.value = null
  invoiceError.value = null
  invoiceLoading.value = true
  try {
    const { data } = await api.registrar.invoice(selectedId.value, invoiceStudentId.value)
    if (token === invoiceGeneration) invoice.value = data
  } catch (e) {
    if (token === invoiceGeneration) invoiceError.value = e
  } finally {
    if (token === invoiceGeneration) invoiceLoading.value = false
  }
}
function openInvoice(event: BillingEvent) {
  clearInvoice()
  invoiceStudentId.value = event.studentId
  invoiceVisible.value = true
  void loadInvoice()
}
const canClose = computed(
  () =>
    verified.value &&
    !result.value &&
    !uncertain.value &&
    !semesterError.value &&
    !semesterLoading.value &&
    current.value?.status === 'OPEN' &&
    now.value >= Date.parse(current.value.addDropEnd) &&
    !busy.value &&
    !loading.value,
)
const canEditDeadline = computed(
  () =>
    verified.value &&
    !result.value &&
    !uncertain.value &&
    (current.value?.status === 'PREPARATION' || current.value?.status === 'OPEN') &&
    !busy.value &&
    !confirming.value &&
    !semesterLoading.value,
)
const canCutoff = computed(
  () =>
    canEditDeadline.value &&
    current.value?.status === 'OPEN' &&
    now.value < Date.parse(current.value.addDropEnd),
)
let generation = 0,
  billingGeneration = 0,
  rosterGeneration = 0,
  active = true,
  timer: ReturnType<typeof setInterval>,
  clock: ReturnType<typeof setInterval>
function shanghaiDateTimeInput(value: string) {
  if (!value || Number.isNaN(Date.parse(value))) return ''
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(new Date(value))
      .map(({ type, value }) => [type, value]),
  )
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`
}
function openDeadlineDialog() {
  if (!canEditDeadline.value || !current.value) return
  deadlineLocal.value = shanghaiDateTimeInput(current.value.addDropEnd)
  deadlineError.value = ''
  deadlineDialog.value = true
}
async function reconcileControl(semesterId: string) {
  await reloadSemesters()
  if (active && selectedId.value === semesterId && !semesterError.value) await loadResult()
}
async function saveDeadline() {
  if (!canEditDeadline.value || !current.value) return
  const semesterId = selectedId.value
  const version = current.value.version
  const timestamp = Date.parse(`${deadlineLocal.value}:00+08:00`)
  if (!Number.isFinite(timestamp)) {
    deadlineError.value = '请选择有效的截止时间'
    return
  }
  if (timestamp <= Date.parse(current.value.registrationStart)) {
    deadlineError.value = '截止时间必须晚于选课开始时间'
    return
  }
  const addDropEnd = new Date(timestamp).toISOString()
  busy.value = true
  error.value = null
  deadlineError.value = ''
  let failure: unknown = null
  try {
    await api.registrar.updateRegistrationDeadline(semesterId, addDropEnd, version)
    deadlineDialog.value = false
  } catch (e) {
    failure = e
  }
  await reconcileControl(semesterId)
  if (failure && active && selectedId.value === semesterId) error.value = failure
  else if (!failure && active && selectedId.value === semesterId)
    ElMessage.success('加退课截止时间已更新')
  busy.value = false
}
async function cutoffRegistration() {
  if (!canCutoff.value || !current.value || confirming.value) return
  const semesterId = selectedId.value
  const version = current.value.version
  const semesterName = current.value.name
  confirming.value = true
  try {
    await ElMessageBox.confirm(
      `立即截止“${semesterName}”的选课和授课调整？截止后学生不能再修改、提交或撤销课表，教师不能再调整授课。此操作不会执行最终名单处理或生成计费通知。`,
      '立即截止选课',
      { confirmButtonText: '立即截止', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  } finally {
    confirming.value = false
  }
  if (!active || selectedId.value !== semesterId || !canCutoff.value) return
  busy.value = true
  error.value = null
  let failure: unknown = null
  try {
    await api.registrar.cutoffRegistration(semesterId, version)
  } catch (e) {
    failure = e
  }
  await reconcileControl(semesterId)
  if (failure && active && selectedId.value === semesterId) error.value = failure
  else if (!failure && active && selectedId.value === semesterId) ElMessage.success('选课已截止')
  busy.value = false
}
async function loadEvents() {
  if (!active || !selectedId.value || !result.value) return
  const token = ++billingGeneration
  const semesterId = selectedId.value
  billingError.value = null
  billingLoading.value = true
  try {
    const { data } = await api.registrar.billingEvents(semesterId, page.value, 20, state.value)
    if (token === billingGeneration) {
      const lastPage = Math.max(1, Math.ceil(data.total / 20))
      if (page.value > lastPage) {
        page.value = lastPage
        // Start a fresh request for the valid page. The generation guard keeps
        // the stale response from replacing the corrected page.
        void loadEvents()
        return
      }
      events.value = data.items
      total.value = data.total
      eventsUpdatedAt.value = new Date().toISOString()
    }
  } catch (e) {
    if (token === billingGeneration) billingError.value = e
  } finally {
    if (token === billingGeneration) billingLoading.value = false
  }
}
async function loadRoster() {
  if (!active || !result.value || !rosterOfferingId.value || !rosterVisible.value) return
  const token = ++rosterGeneration
  const semesterId = selectedId.value
  const offeringId = rosterOfferingId.value
  rosterLoading.value = true
  rosterError.value = null
  roster.value = null
  try {
    const { data } = await api.registrar.finalRoster(semesterId, offeringId)
    if (token === rosterGeneration) roster.value = data
  } catch (e) {
    if (token === rosterGeneration) rosterError.value = e
  } finally {
    if (token === rosterGeneration) rosterLoading.value = false
  }
}
function showRoster(offering: CloseResult['offerings'][number]) {
  if (!result.value || offering.status !== 'CLOSED') return
  rosterOfferingId.value = offering.offeringId
  rosterTitle.value = `${offering.courseName} ${offering.sectionCode} 班 · 最终名单`
  rosterVisible.value = true
  void loadRoster()
}
function clearRoster() {
  rosterGeneration++
  rosterVisible.value = false
  roster.value = null
  rosterError.value = null
  rosterLoading.value = false
  rosterOfferingId.value = ''
}
async function loadResult() {
  if (!active || !selectedId.value) return
  const token = ++generation
  const semesterId = selectedId.value
  loading.value = true
  error.value = null
  verified.value = false
  try {
    let data: CloseResult | null
    try {
      data = (await api.registrar.closeResult(semesterId)).data
    } catch (e) {
      if (e instanceof HttpError && e.status === 404 && e.code === 'REGISTRATION_NOT_CLOSED')
        data = null
      else throw e
    }
    if (token !== generation) return
    result.value = data
    verified.value = true
    uncertain.value = false
    if (data) await loadEvents()
    else {
      billingGeneration++
      events.value = []
      total.value = 0
      eventsUpdatedAt.value = null
      billingError.value = null
      billingLoading.value = false
    }
  } catch (e) {
    if (token === generation) {
      error.value = e
      // A failed lookup leaves the previously displayed result unverified.
      // Do not allow a close operation until the result has been queried again.
      verified.value = false
      uncertain.value = true
    }
  } finally {
    if (token === generation) loading.value = false
  }
}
async function refresh() {
  if (busy.value || confirming.value || deadlineDialog.value) return
  await reloadSemesters()
  if (semesterError.value) return
  await loadResult()
}
async function close() {
  if (!canClose.value || confirming.value) return
  const semesterId = selectedId.value
  const semesterName = current.value?.name
  confirming.value = true
  try {
    await ElMessageBox.confirm(
      `确认关闭“${semesterName}”注册？系统将取消无教师教学班、按备选顺序补位，再取消不足三人的班并生成最终计费课表。关闭后不能继续选退课或更改授课。`,
      '关闭学期注册',
      { confirmButtonText: '确认关闭', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  } finally {
    confirming.value = false
  }
  now.value = Date.now()
  if (!active || semesterId !== selectedId.value || !canClose.value) return
  busy.value = true
  error.value = null
  try {
    const { data } = await api.registrar.close(semesterId)
    if (!active || selectedId.value !== semesterId) return
    result.value = data
    verified.value = true
    uncertain.value = false
    ElMessage.success('注册已关闭，财务通知由系统持续投递')
    await reloadSemesters()
    await loadEvents()
  } catch (e) {
    if (active && selectedId.value === semesterId) {
      error.value = e
      verified.value = false
      uncertain.value = e instanceof HttpError && [0, 502, 503, 504].includes(e.status)
    }
  } finally {
    busy.value = false
  }
}
watch(selectedId, () => {
  clearInvoice()
  clearRoster()
  result.value = null
  events.value = []
  total.value = 0
  eventsUpdatedAt.value = null
  billingError.value = null
  billingLoading.value = false
  uncertain.value = false
  billingGeneration++
  page.value = 1
  state.value = ''
  deadlineDialog.value = false
  deadlineError.value = ''
  void loadResult()
})
watch(state, () => {
  events.value = []
  total.value = 0
  eventsUpdatedAt.value = null
  page.value = 1
  void loadEvents()
})
function refreshVisibleEvents() {
  if (!document.hidden && result.value && !busy.value && !billingLoading.value) void loadEvents()
}
onMounted(() => {
  timer = setInterval(refreshVisibleEvents, 10000)
  document.addEventListener('visibilitychange', refreshVisibleEvents)
  window.addEventListener('focus', refreshVisibleEvents)
  clock = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})
onUnmounted(() => {
  active = false
  clearInvoice()
  generation++
  billingGeneration++
  clearInterval(timer)
  clearRoster()
  clearInterval(clock)
  document.removeEventListener('visibilitychange', refreshVisibleEvents)
  window.removeEventListener('focus', refreshVisibleEvents)
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1 class="page-title">注册管理</h1>
      <p class="page-description">确定学期最终课表，跟踪财务通知结果。</p>
    </div>
    <SemesterSelect
      v-model="selectedId"
      :semesters="choices"
      :disabled="busy || confirming || deadlineDialog"
    />
  </div>
  <ErrorNotice
    :error="semesterError"
    retry
    @retry="refresh"
  /><ErrorNotice
    :error="error"
    retry
    @retry="refresh"
  />
  <EmptyState
    v-if="!semesterLoading && !semesterError && !choices.length"
    title="暂无学期"
    description="学期数据建立后可查看注册和财务通知状态。"
  />
  <div
    v-if="current"
    class="stats-grid"
  >
    <div class="surface stat-card">
      <div class="stat-label">学期注册</div>
      <div class="stat-value">{{ label(registrationStatus) }}</div>
      <div class="stat-note">{{ current.name }}</div>
    </div>
    <div class="surface stat-card">
      <div class="stat-label">取消教学班</div>
      <div class="stat-value">{{ result?.cancelledOfferings ?? '—' }}</div>
      <div class="stat-note">无教师或最终不足三人</div>
    </div>
    <div class="surface stat-card">
      <div class="stat-label">补位学生</div>
      <div class="stat-value">{{ result?.leveledSchedules ?? '—' }}</div>
      <div class="stat-note">按照已提交备选进行处理</div>
    </div>
    <div class="surface stat-card">
      <div class="stat-label">生成计费通知</div>
      <div class="stat-value">{{ result?.billingEvents ?? '—' }}</div>
      <div class="stat-note">
        关闭时待发送：{{ result?.pendingBillingEvents ?? '—' }}；当前送达状态见下表
      </div>
    </div>
  </div>
  <section
    class="surface"
    v-if="current"
  >
    <div class="surface-header">
      <strong>注册控制</strong
      ><el-button
        :loading="loading"
        :disabled="busy || confirming || deadlineDialog"
        @click="refresh"
        >查询最新结果</el-button
      >
    </div>
    <div class="surface-body">
      <el-descriptions :column="1"
        ><el-descriptions-item label="选课开始">{{
          dateTime(current.registrationStart)
        }}</el-descriptions-item
        ><el-descriptions-item label="常规结束">{{
          dateTime(current.registrationEnd)
        }}</el-descriptions-item
        ><el-descriptions-item label="加退课截止">{{
          dateTime(current.addDropEnd)
        }}</el-descriptions-item
        ><el-descriptions-item
          v-if="result"
          label="关闭时间"
          >{{ dateTime(result.closedAt) }}</el-descriptions-item
        ></el-descriptions
      ><el-alert
        v-if="registrationStatus === 'REGISTRATION_ENDED'"
        title="选课已截止。可以关闭注册完成最终名额处理和计费，也可以修改截止时间重新开放。"
        type="warning"
        :closable="false"
        show-icon
      /><el-alert
        v-else-if="current.status === 'PREPARATION'"
        title="学期尚未开放；可在此调整加退课截止时间。"
        type="info"
        :closable="false"
        show-icon
      /><el-alert
        v-else-if="registrationStatus !== 'CLOSED'"
        title="到加退课截止后方可关闭。截止后可执行最终名额处理和计费。"
        type="warning"
        :closable="false"
        show-icon
      /><el-alert
        v-else
        title="注册已关闭，最终课表已确定。财务不可用时通知会保留并持续重试，不撤销关闭结果。"
        type="success"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="uncertain"
        title="关闭请求的结果尚未确认。请点击“查询最新结果”核对；查询完成前不能再次关闭。"
        type="warning"
        :closable="false"
        show-icon
        style="margin-top: 12px"
      />
      <div
        class="action-row"
        style="margin-top: 18px"
      >
        <el-button
          v-if="current.status !== 'CLOSED'"
          :disabled="!canEditDeadline || confirming"
          @click="openDeadlineDialog"
          >修改截止时间</el-button
        ><el-button
          v-if="current.status === 'OPEN' && now < Date.parse(current.addDropEnd)"
          type="warning"
          :disabled="!canCutoff || confirming"
          @click="cutoffRegistration"
          >立即截止选课</el-button
        >
        <el-button
          v-if="registrationStatus !== 'CLOSED'"
          type="danger"
          :loading="busy"
          :disabled="!canClose || confirming"
          @click="close"
          >关闭注册</el-button
        ><span
          v-if="busy"
          class="tiny muted"
          >正在处理，请勿重复提交；关闭计算可能需要较长时间。</span
        >
      </div>
    </div>
  </section>
  <el-dialog
    v-model="deadlineDialog"
    title="修改加退课截止时间"
    width="460px"
    :close-on-click-modal="false"
    :show-close="!busy"
  >
    <el-form @submit.prevent="saveDeadline">
      <el-form-item label="截止时间（北京时间）" :error="deadlineError">
        <el-input
          v-model="deadlineLocal"
          type="datetime-local"
          step="60"
          :min="shanghaiDateTimeInput(current?.registrationStart || '')"
          aria-label="截止时间（北京时间）"
          @input="deadlineError = ''"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="busy" @click="deadlineDialog = false">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="!deadlineLocal" @click="saveDeadline">
        保存截止时间
      </el-button>
    </template>
  </el-dialog>
  <section
    class="surface"
    v-if="result"
  >
    <div class="surface-header">
      <strong>教学班最终结果</strong><span class="tiny muted">包含取消原因和最终人数</span>
    </div>
    <div class="surface-body">
      <el-table
        :data="result.offerings"
        empty-text="无教学班结果"
        ><el-table-column
          prop="courseName"
          label="课程"
          min-width="180"
        /><el-table-column
          prop="sectionCode"
          label="教学班"
          width="100"
        /><el-table-column
          label="状态"
          width="100"
          ><template #default="{ row }"
            ><el-tag :type="row.status === 'CANCELLED' ? 'danger' : 'success'">{{
              label(row.status)
            }}</el-tag></template
          ></el-table-column
        ><el-table-column
          prop="enrolledCount"
          label="最终人数"
          width="100"
        /><el-table-column
          label="取消原因"
          min-width="145"
          ><template #default="{ row }">{{ label(row.cancelReason) }}</template></el-table-column
        ><el-table-column
          label="最终名单"
          width="115"
          fixed="right"
          ><template #default="{ row }"
            ><el-button
              v-if="row.status === 'CLOSED'"
              link
              type="primary"
              :aria-label="`查看${row.courseName}${row.sectionCode}班最终名单`"
              @click="showRoster(row)"
              >查看名单</el-button
            ><span
              v-else
              class="muted"
              >已取消</span
            ></template
          ></el-table-column
        ></el-table
      >
    </div>
  </section>
  <section
    class="surface"
    v-if="result"
  >
    <div class="surface-header">
      <div>
        <strong>账单与财务通知</strong>
        <p
          class="tiny muted"
          style="margin: 6px 0 0"
        >
          每 10 秒刷新；最近更新：{{ dateTime(eventsUpdatedAt) }}
        </p>
      </div>
      <div class="action-row">
        <el-select
          v-model="state"
          clearable
          placeholder="全部状态"
          aria-label="筛选通知状态"
          style="width: 145px"
          ><el-option
            v-for="s in ['PENDING', 'IN_FLIGHT', 'RETRY', 'DELIVERED']"
            :key="s"
            :label="label(s)"
            :value="s" /></el-select
        ><el-button
          :loading="billingLoading"
          @click="loadEvents"
          >刷新通知</el-button
        >
      </div>
    </div>
    <div class="surface-body">
      <ErrorNotice
        :error="billingError"
        retry
        @retry="loadEvents"
      /><el-table
        v-loading="billingLoading"
        :data="events"
        empty-text="暂无符合条件的财务通知"
        ><el-table-column
          prop="eventId"
          label="事件 ID"
          min-width="230"
        /><el-table-column
          prop="studentName"
          label="学生姓名"
          min-width="110"
          ><template #default="{ row }">{{ row.studentName || '—' }}</template></el-table-column
        ><el-table-column
          prop="studentId"
          label="学生 ID"
          min-width="110"
        /><el-table-column
          label="状态"
          width="110"
          ><template #default="{ row }"
            ><el-tag :type="row.state === 'DELIVERED' ? 'success' : 'warning'">{{
              label(row.state)
            }}</el-tag></template
          ></el-table-column
        ><el-table-column
          prop="attempts"
          label="尝试次数"
          width="90"
        /><el-table-column
          label="下一次 / 送达时间"
          min-width="190"
          ><template #default="{ row }">{{
            dateTime(row.deliveredAt || row.nextAttemptAt)
          }}</template></el-table-column
        ><el-table-column
          label="错误摘要"
          min-width="200"
          ><template #default="{ row }">{{ row.lastError || '—' }}</template></el-table-column
        ><el-table-column
          label="账单"
          width="110"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openInvoice(row)"
              >查看账单</el-button
            >
          </template>
        </el-table-column></el-table
      ><el-pagination
        v-model:current-page="page"
        :page-size="20"
        :total="total"
        layout="prev, pager, next, total"
        small
        class="pagination"
        @current-change="loadEvents"
      />
    </div>
  </section>
  <el-dialog
    v-model="invoiceVisible"
    title="学费账单"
    width="min(800px, 94vw)"
    destroy-on-close
    @close="clearInvoice"
  >
    <ErrorNotice
      :error="invoiceError"
      retry
      @retry="loadInvoice"
    />
    <el-skeleton
      v-if="invoiceLoading"
      :rows="5"
      animated
    />
    <InvoiceDetail
      v-else-if="invoice && !invoiceError"
      :invoice="invoice"
    />
    <template #footer>
      <el-button
        :loading="invoiceLoading"
        @click="loadInvoice"
        >刷新账单</el-button
      >
      <el-button @click="clearInvoice">关闭</el-button>
    </template>
  </el-dialog>
  <el-dialog
    v-model="rosterVisible"
    :title="rosterTitle"
    width="min(720px, 94vw)"
    destroy-on-close
    @close="clearRoster"
  >
    <ErrorNotice
      :error="rosterError"
      retry
      @retry="loadRoster"
    />
    <p class="tiny muted">名单为关闭注册后的最终有效注册，包含主选及成功补位的备选。</p>
    <el-table
      v-if="!rosterError"
      v-loading="rosterLoading"
      :data="roster?.students || []"
      empty-text="该教学班没有最终注册学生"
    >
      <el-table-column
        prop="studentId"
        label="学生 ID"
        min-width="130"
      />
      <el-table-column
        prop="studentName"
        label="姓名"
        min-width="140"
      />
      <el-table-column
        label="注册来源"
        min-width="120"
      >
        <template #default="{ row }">{{ label(row.source) }}</template>
      </el-table-column>
    </el-table>
    <template #footer><el-button @click="clearRoster">关闭</el-button></template>
  </el-dialog>
</template>
