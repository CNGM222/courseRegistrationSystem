<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import { HttpError } from '../../services/http'
import { useSemesters } from '../../composables/useSemesters'
import { useDirtyGuard } from '../../composables/useDirtyGuard'
import { useAuthStore } from '../../stores/auth'
import { useRecoveryStore } from '../../stores/recovery'
import { choiceIds, dateTime, registrationOpen } from '../../utils/format'
import type { Schedule, Offering, ValidationIssue } from '../../types/api'
import SemesterSelect from '../../components/SemesterSelect.vue'
import ErrorNotice from '../../components/ErrorNotice.vue'
import CatalogBrowser from '../../components/CatalogBrowser.vue'
import ScheduleDisplay from '../../components/ScheduleDisplay.vue'
import OfferingDetail from '../../components/OfferingDetail.vue'

const auth = useAuthStore(),
  recovery = useRecoveryStore(),
  owner = auth.user!.accountId
const {
  choices,
  selectedId,
  current,
  open,
  loading: semesterLoading,
  error: semesterError,
  load: reloadSemesters,
} = useSemesters('current', () =>
  recovery.draft?.owner === owner ? recovery.draft.semesterId : undefined,
)
const schedule = ref<Schedule | null>(null),
  primary = ref<string[]>([]),
  alternate = ref<string[]>([]),
  cache = ref<Record<string, Offering>>({})
const error = ref<unknown>(null),
  loading = ref(false),
  busy = ref(false),
  ready = ref(false),
  blocked = ref(false),
  saved = ref(''),
  notice = ref(''),
  validationPassed = ref(false),
  issues = ref<ValidationIssue[]>([])
const mobile = ref(window.innerWidth <= 760)
function resize() {
  mobile.value = window.innerWidth <= 760
}
const tab = ref('plan'),
  selectedDetail = ref<Offering | null>(null),
  detailVisible = ref(false)
watch(mobile, (isMobile) => {
  if (!isMobile && tab.value === 'catalog') tab.value = 'plan'
})
function showOffering(id: string) {
  selectedDetail.value = cache.value[id] || null
  detailVisible.value = true
}
function selectionStatus(id: string) {
  return schedule.value?.enrollments.some((e) => e.offeringId === id && e.state === 'ENROLLED')
    ? '已正式注册'
    : '已选择'
}
function issueText(issue: ValidationIssue) {
  const name = issue.offeringId && cache.value[issue.offeringId]?.courseName
  return name && !issue.message.includes(name) ? `${name}：${issue.message}` : issue.message
}
const fingerprint = computed(() => JSON.stringify([primary.value, alternate.value]))
const dirty = computed(() => ready.value && fingerprint.value !== saved.value)
const { confirmLeave } = useDirtyGuard(dirty)
const disabled = computed(
  () => !open.value || busy.value || loading.value || !ready.value || blocked.value,
)
let generation = 0,
  polling = false,
  timer: ReturnType<typeof setInterval>
function isCurrent(sid: string, token: number) {
  return token === generation && sid === selectedId.value && auth.user?.accountId === owner
}
function canContinue(sid: string, token: number) {
  if (!isCurrent(sid, token) || blocked.value || !ready.value || loading.value) return false
  if (!registrationOpen(current.value)) {
    ElMessage.warning('选课开放时段已结束，请读取最新课表核对状态')
    return false
  }
  return true
}
function remember(list: Offering[]) {
  for (const o of list) cache.value[o.id] = o
}
function syncDraft() {
  if (!ready.value) return
  if (dirty.value && auth.user)
    recovery.draft = {
      owner,
      semesterId: selectedId.value,
      primary: [...primary.value],
      alternate: [...alternate.value],
      offerings: Object.values(cache.value).filter((o) =>
        [...primary.value, ...alternate.value].includes(o.id),
      ),
    }
  else if (auth.user && recovery.draft?.semesterId === selectedId.value) recovery.clear()
}
watch([primary, alternate], syncDraft, { deep: true, flush: 'sync' })
function apply(s: Schedule | null) {
  schedule.value = s
  ready.value = false
  primary.value = choiceIds(s, 'PRIMARY')
  alternate.value = choiceIds(s, 'ALTERNATE')
  remember((s?.enrollments || []).flatMap((e) => (e.offering ? [e.offering] : [])))
  saved.value = fingerprint.value
  ready.value = true
}
async function loadSchedule(preserve = false) {
  const sid = selectedId.value,
    token = ++generation
  if (!sid) {
    loading.value = false
    return
  }
  const keepLocal = preserve && ready.value && (dirty.value || blocked.value)
  loading.value = true
  error.value = null
  blocked.value = true
  const local = { primary: [...primary.value], alternate: [...alternate.value] }
  const remembered = recovery.draft
  try {
    let next: Schedule | null
    try {
      next = (await api.student.schedule(sid)).data
    } catch (e) {
      if (e instanceof HttpError && e.status === 404 && e.code === 'SCHEDULE_NOT_FOUND') next = null
      else throw e
    }
    if (!isCurrent(sid, token)) return
    apply(next)
    if (keepLocal) {
      primary.value = local.primary
      alternate.value = local.alternate
      notice.value = '已读取服务器最新课表，保留了本地方案。请核对“有效课表”和本地修改后重新保存。'
    }
    validationPassed.value = false
    issues.value = []
    if (!keepLocal && remembered?.owner === owner && remembered.semesterId === sid && open.value) {
      const old = remembered
      try {
        await ElMessageBox.confirm(
          '发现此账号在当前标签页中的未提交选课方案，是否恢复？',
          '恢复工作方案',
          { confirmButtonText: '恢复方案', cancelButtonText: '使用服务器版本' },
        )
        if (!isCurrent(sid, token)) return
        remember(old.offerings)
        primary.value = [...old.primary]
        alternate.value = [...old.alternate]
      } catch {
        if (!isCurrent(sid, token)) return
        recovery.clear()
      }
    }
    const ids = [...new Set([...primary.value, ...alternate.value])].filter(
      (id) => !cache.value[id],
    )
    const details = await Promise.all(ids.map((id) => api.offering(id)))
    if (!isCurrent(sid, token)) return
    remember(details.map((d) => d.data))
    blocked.value = false
  } catch (e) {
    if (isCurrent(sid, token)) error.value = e
  } finally {
    if (token === generation) loading.value = false
  }
}
async function selectSemester(id: string) {
  if (busy.value || loading.value || !(await confirmLeave()) || !auth.user) return
  recovery.clear()
  selectedId.value = id
}
watch(selectedId, () => {
  ready.value = false
  schedule.value = null
  primary.value = []
  alternate.value = []
  cache.value = {}
  notice.value = ''
  issues.value = []
  validationPassed.value = false
  void loadSchedule()
})
function add(o: Offering, kind: 'PRIMARY' | 'ALTERNATE') {
  if (disabled.value) return
  const all = [...primary.value, ...alternate.value]
  if (all.includes(o.id) || all.some((id) => cache.value[id]?.courseCode === o.courseCode))
    return ElMessage.warning('同一方案不能重复选择同一课程或其不同教学班')
  const target = kind === 'PRIMARY' ? primary : alternate
  if (target.value.length >= (kind === 'PRIMARY' ? 4 : 2))
    return ElMessage.warning(kind === 'PRIMARY' ? '最多选择四门主选课程' : '最多选择两门备选课程')
  remember([o])
  target.value.push(o.id)
  issues.value = []
  validationPassed.value = false
}
function remove(kind: 'PRIMARY' | 'ALTERNATE', i: number) {
  if (disabled.value) return
  ;(kind === 'PRIMARY' ? primary : alternate).value.splice(i, 1)
  issues.value = []
  validationPassed.value = false
}
function reorderAlternates() {
  if (disabled.value) return
  alternate.value.reverse()
  issues.value = []
  validationPassed.value = false
}
async function save(sid: string, token: number) {
  const { data } = await api.student.saveDraft(
    sid,
    { primaryOfferingIds: primary.value, alternateOfferingIds: alternate.value },
    schedule.value?.version,
  )
  if (!isCurrent(sid, token)) return null
  apply(data)
  recovery.clear()
  issues.value = []
  validationPassed.value = false
  return data
}
async function handleFailure(e: unknown) {
  error.value = e
  if (e instanceof HttpError && [0, 409, 412].includes(e.status)) {
    blocked.value = true
    notice.value = '请点击“读取最新课表”核对服务器结果；本地方案会保留，不会自动重复提交。'
  }
  await poll()
}
async function saveDraft() {
  if (disabled.value) return
  const sid = selectedId.value,
    token = generation
  if (!canContinue(sid, token)) return
  busy.value = true
  error.value = null
  try {
    if (!(await save(sid, token))) return
    ElMessage.success('草稿已保存，名额未发生变化')
  } catch (e) {
    if (isCurrent(sid, token)) await handleFailure(e)
  } finally {
    busy.value = false
  }
}
async function precheck(andSubmit = false) {
  if (disabled.value) return
  const sid = selectedId.value,
    token = generation
  if (!canContinue(sid, token)) return
  if (
    !schedule.value?.firstSubmittedAt &&
    (primary.value.length !== 4 || alternate.value.length !== 2)
  )
    return ElMessage.warning('首次正式提交必须选择四门主选和两门备选；未完成的方案可保存草稿')
  busy.value = true
  error.value = null
  issues.value = []
  validationPassed.value = false
  try {
    const s =
      dirty.value || schedule.value?.draftRevision == null ? await save(sid, token) : schedule.value
    if (!s || !canContinue(sid, token)) return
    if (s?.draftRevision == null) throw new Error('服务器未返回草稿版本，请读取最新课表后重试')
    const revision = s.draftRevision,
      version = s.version,
      planFingerprint = fingerprint.value
    const validation = (await api.student.validate(sid, revision)).data
    if (!canContinue(sid, token)) return
    issues.value = validation.issues
    remember(validation.affectedOfferings || [])
    if (!validation.valid) {
      if (!validation.issues.length) error.value = new Error('方案未通过校验，请调整课程后重新预检')
      return
    }
    validationPassed.value = true
    if (!andSubmit) return
    const old = s.enrollments.filter((e) => e.state === 'ENROLLED').map((e) => e.offeringId)
    const names = (ids: string[]) =>
      ids.map((id) => cache.value[id]?.courseName || id).join('、') || '无'
    try {
      await ElMessageBox.confirm(
        `新增：${names(primary.value.filter((id) => !old.includes(id)))}；退课：${names(old.filter((id) => !primary.value.includes(id)))}；保留：${names(primary.value.filter((id) => old.includes(id)))}。备选 ${alternate.value.length} 门不提前占位，最终结果以提交校验为准。`,
        '确认提交选课方案',
        { confirmButtonText: '正式提交', cancelButtonText: '继续编辑', type: 'warning' },
      )
    } catch {
      return
    }
    if (!canContinue(sid, token)) return
    if (
      schedule.value?.draftRevision !== revision ||
      schedule.value?.version !== version ||
      fingerprint.value !== planFingerprint
    ) {
      ElMessage.warning('课表或工作方案已变化，请重新预检并确认提交')
      return
    }
    const submitted = (await api.student.submit(sid, revision, version)).data
    if (!isCurrent(sid, token)) return
    apply(submitted)
    recovery.clear()
    notice.value = ''
    validationPassed.value = false
    ElMessage.success('选课提交成功，有效课表已更新')
  } catch (e) {
    if (isCurrent(sid, token)) await handleFailure(e)
  } finally {
    busy.value = false
  }
}
async function deleteSchedule() {
  if (disabled.value || !schedule.value?.id) return
  const sid = selectedId.value,
    token = generation,
    version = schedule.value.version,
    scheduleId = schedule.value.id
  if (!canContinue(sid, token)) return
  busy.value = true
  error.value = null
  try {
    try {
      await ElMessageBox.confirm(
        '这将撤销所有有效注册、释放名额并删除当前主选和备选方案。',
        '删除整个课表？',
        { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
    if (!canContinue(sid, token)) return
    if (schedule.value?.id !== scheduleId || schedule.value?.version !== version) {
      ElMessage.warning('课表已变化，请读取最新课表后重新确认删除')
      return
    }
    await api.student.deleteSchedule(sid, version)
    if (!isCurrent(sid, token)) return
    apply(null)
    recovery.clear()
    await loadSchedule()
    ElMessage.success('课表已删除，原名额已释放')
  } catch (e) {
    if (isCurrent(sid, token)) await handleFailure(e)
  } finally {
    busy.value = false
  }
}
async function poll() {
  const ids = [...primary.value, ...alternate.value],
    sid = selectedId.value,
    token = generation
  if (document.hidden || polling || !auth.user || !sid || !ids.length) return
  polling = true
  try {
    const latest = (await api.semesters.availability(sid, ids)).data
    if (!isCurrent(sid, token)) return
    const full = latest.filter(
      (o) =>
        o.enrolledCount >= o.maxStudents && (cache.value[o.id]?.enrolledCount || 0) < o.maxStudents,
    )
    remember(latest)
    if (full.length) {
      validationPassed.value = false
      notice.value = `${full.map((o) => o.courseName).join('、')} 名额已满。请核对方案；最终提交会再次校验。`
    }
  } catch (e) {
    if (isCurrent(sid, token))
      notice.value = '暂时无法更新名额，显示值可能过期。提交前会进行服务端预检。'
  } finally {
    polling = false
  }
}
onMounted(() => {
  window.addEventListener('resize', resize)
  timer = setInterval(poll, 5000)
  document.addEventListener('visibilitychange', poll)
  window.addEventListener('focus', poll)
})
onUnmounted(() => {
  window.removeEventListener('resize', resize)
  generation++
  clearInterval(timer)
  document.removeEventListener('visibilitychange', poll)
  window.removeEventListener('focus', poll)
  if (auth.user) recovery.clear()
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1 class="page-title">选课中心</h1>
      <p class="page-description">
        首次提交需四门主选和两门备选；提交后的加退课可保留零至四门主选、零至两门备选。
      </p>
    </div>
    <SemesterSelect
      :model-value="selectedId"
      :semesters="choices"
      :disabled="busy || loading"
      @update:model-value="selectSemester"
    />
  </div>
  <ErrorNotice
    :error="semesterError"
    retry
    @retry="reloadSemesters"
  /><el-alert
    v-if="current && !open"
    title="当前不在允许修改课表的时段，或注册已关闭。"
    type="info"
    :closable="false"
    show-icon
  />
  <p
    v-if="current"
    class="tiny muted"
  >
    选课开始 {{ dateTime(current.registrationStart) }} · 常规结束
    {{ dateTime(current.registrationEnd) }} · 加退课截止 {{ dateTime(current.addDropEnd) }}
  </p>
  <ErrorNotice :error="error" /><el-alert
    v-if="notice"
    :title="notice"
    type="warning"
    :closable="false"
    show-icon
  />
  <div
    v-if="selectedId"
    class="action-row"
    style="margin: 14px 0"
  >
    <el-button
      :loading="loading"
      :disabled="busy"
      @click="loadSchedule(true)"
      >读取最新课表</el-button
    ><el-tag
      v-if="dirty"
      type="warning"
      >尚未保存</el-tag
    ><el-tag
      v-else-if="ready && !blocked && !loading"
      type="info"
      >工作方案已同步</el-tag
    >
  </div>
  <el-empty
    v-if="!semesterLoading && !semesterError && !choices.length"
    description="暂无可选课的学期"
  />
  <el-tabs
    v-if="selectedId"
    v-model="tab"
    ><el-tab-pane
      label="选课方案"
      name="plan"
      ><div
        v-loading="loading"
        class="surface"
      >
        <div class="surface-header">
          <strong>我的工作方案</strong
          ><span class="tiny muted">保存草稿不占名额；移除主选后须提交才退课</span>
        </div>
        <div class="surface-body">
          <h4>
            主选课程 <span class="muted">{{ primary.length }} / 4</span>
          </h4>
          <div class="slot-grid">
            <div
              v-for="i in 4"
              :key="i"
              class="choice-slot"
              :class="{ filled: primary[i - 1] }"
            >
              <div class="slot-label">主选 {{ i }}</div>
              <template v-if="primary[i - 1]"
                ><el-button
                  link
                  type="primary"
                  @click="showOffering(primary[i - 1])"
                  >{{ cache[primary[i - 1]]?.courseName || primary[i - 1] }}</el-button
                >
                <div class="slot-meta">
                  {{ cache[primary[i - 1]]?.enrolledCount }} /
                  {{ cache[primary[i - 1]]?.maxStudents }} 人
                </div>
                <el-tag
                  size="small"
                  :type="selectionStatus(primary[i - 1]) === '已正式注册' ? 'success' : 'info'"
                >
                  {{ selectionStatus(primary[i - 1]) }}
                </el-tag>
                <el-button
                  link
                  size="small"
                  :disabled="disabled"
                  @click="remove('PRIMARY', i - 1)"
                  >移除</el-button
                ></template
              ><span
                v-else
                class="muted tiny"
                >从下方目录加入主选</span
              >
            </div>
          </div>
          <h4>
            备选课程 <span class="muted">按以下顺序补位 · {{ alternate.length }} / 2</span>
          </h4>
          <div class="slot-grid alternate">
            <div
              v-for="i in 2"
              :key="i"
              class="choice-slot"
              :class="{ filled: alternate[i - 1] }"
            >
              <div class="slot-label">第 {{ i }} 备选</div>
              <template v-if="alternate[i - 1]"
                ><el-button
                  link
                  type="primary"
                  @click="showOffering(alternate[i - 1])"
                >
                  {{ cache[alternate[i - 1]]?.courseName || alternate[i - 1] }}
                </el-button>
                <div class="slot-meta">
                  {{ cache[alternate[i - 1]]?.enrolledCount }} /
                  {{ cache[alternate[i - 1]]?.maxStudents }} 人 · 不提前占位
                </div>
                <el-tag
                  size="small"
                  :type="selectionStatus(alternate[i - 1]) === '已正式注册' ? 'success' : 'info'"
                >
                  {{ selectionStatus(alternate[i - 1]) }}
                </el-tag>
                <el-button
                  link
                  size="small"
                  :disabled="disabled"
                  @click="remove('ALTERNATE', i - 1)"
                  >移除</el-button
                ><el-button
                  v-if="alternate.length === 2 && i === 2"
                  link
                  size="small"
                  :disabled="disabled"
                  @click="reorderAlternates"
                  >上移优先级</el-button
                ></template
              ><span
                v-else
                class="muted tiny"
                >备选不提前占位、不计费</span
              >
            </div>
          </div>
          <el-alert
            v-for="(issue, i) in issues"
            :key="i"
            :title="issueText(issue)"
            type="error"
            :closable="false"
            show-icon
          />
          <el-alert
            v-if="validationPassed"
            title="方案预检通过，尚未正式提交；预检不占名额，正式提交时将再次校验。"
            type="success"
            :closable="false"
            show-icon
          />
          <div
            class="action-row"
            style="margin-top: 22px"
          >
            <el-button
              :disabled="disabled"
              :loading="busy"
              @click="saveDraft"
              >保存草稿</el-button
            ><el-button
              :disabled="disabled"
              :loading="busy"
              @click="precheck()"
              >预检方案</el-button
            ><el-button
              type="primary"
              :disabled="disabled"
              :loading="busy"
              @click="precheck(true)"
              >预检并提交</el-button
            ><el-button
              type="danger"
              plain
              :disabled="disabled || !schedule?.id"
              @click="deleteSchedule"
              >删除整个课表</el-button
            >
          </div>
        </div>
      </div>
      <CatalogBrowser
        v-if="!mobile"
        :semester-id="selectedId"
        selectable
        :disabled="disabled"
        @select="add"
        @loaded="remember" /></el-tab-pane
    ><el-tab-pane
      v-if="mobile"
      label="课程目录"
      name="catalog"
      ><CatalogBrowser
        :semester-id="selectedId"
        selectable
        :disabled="disabled"
        @select="add"
        @loaded="remember" /></el-tab-pane
    ><el-tab-pane
      label="当前有效课表"
      name="effective"
      ><el-skeleton
        v-if="loading && !schedule"
        class="surface"
        :rows="5"
        animated />
      <ScheduleDisplay
        v-else-if="ready || schedule"
        :schedule="schedule" /></el-tab-pane></el-tabs
  ><OfferingDetail
    v-model="detailVisible"
    :offering="selectedDetail"
  />
</template>
