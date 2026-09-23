<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import { HttpError } from '../../services/http'
import { useSemesters } from '../../composables/useSemesters'
import { useDirtyGuard } from '../../composables/useDirtyGuard'
import { useAuthStore } from '../../stores/auth'
import { useRecoveryStore } from '../../stores/recovery'
import { choiceIds, dateTime } from '../../utils/format'
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
const fingerprint = computed(() => JSON.stringify([primary.value, alternate.value]))
const dirty = computed(() => ready.value && fingerprint.value !== saved.value)
const { confirmLeave } = useDirtyGuard(dirty)
const disabled = computed(
  () => !open.value || busy.value || loading.value || !ready.value || blocked.value,
)
let generation = 0,
  polling = false,
  timer: ReturnType<typeof setInterval>
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
  if (!sid) return
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
    if (token !== generation) return
    apply(next)
    const ids = [...new Set([...primary.value, ...alternate.value])].filter(
      (id) => !cache.value[id],
    )
    const details = await Promise.all(ids.map((id) => api.offering(id)))
    if (token !== generation) return
    remember(details.map((d) => d.data))
    blocked.value = false
    if (keepLocal) {
      primary.value = local.primary
      alternate.value = local.alternate
      notice.value = '已读取服务器最新课表，保留了本地方案。请核对“有效课表”和本地修改后重新保存。'
    } else if (remembered?.owner === owner && remembered.semesterId === sid && open.value) {
      const old = remembered
      try {
        await ElMessageBox.confirm(
          '发现此账号在当前标签页中的未提交选课方案，是否恢复？',
          '恢复工作方案',
          { confirmButtonText: '恢复方案', cancelButtonText: '使用服务器版本' },
        )
        if (token === generation) {
          remember(old.offerings)
          primary.value = [...old.primary]
          alternate.value = [...old.alternate]
        }
      } catch {
        recovery.clear()
      }
    }
  } catch (e) {
    if (token === generation) error.value = e
  } finally {
    if (token === generation) loading.value = false
  }
}
async function selectSemester(id: string) {
  if (busy.value || !(await confirmLeave())) return
  recovery.clear()
  selectedId.value = id
}
watch(selectedId, () => {
  ready.value = false
  primary.value = []
  alternate.value = []
  cache.value = {}
  notice.value = ''
  issues.value = []
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
}
function remove(kind: 'PRIMARY' | 'ALTERNATE', i: number) {
  ;(kind === 'PRIMARY' ? primary : alternate).value.splice(i, 1)
  issues.value = []
}
async function save() {
  const { data } = await api.student.saveDraft(
    selectedId.value,
    { primaryOfferingIds: primary.value, alternateOfferingIds: alternate.value },
    schedule.value?.version,
  )
  apply(data)
  recovery.clear()
  issues.value = []
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
  busy.value = true
  error.value = null
  try {
    await save()
    ElMessage.success('草稿已保存，名额未发生变化')
  } catch (e) {
    await handleFailure(e)
  } finally {
    busy.value = false
  }
}
async function submit() {
  if (disabled.value) return
  if (
    !schedule.value?.firstSubmittedAt &&
    (primary.value.length !== 4 || alternate.value.length !== 2)
  )
    return ElMessage.warning('首次正式提交必须选择四门主选和两门备选；未完成的方案可保存草稿')
  busy.value = true
  error.value = null
  issues.value = []
  try {
    const s = dirty.value || !schedule.value?.draftRevision ? await save() : schedule.value
    const validation = (await api.student.validate(selectedId.value, s!.draftRevision!)).data
    issues.value = validation.issues
    remember(validation.affectedOfferings || [])
    if (!validation.valid) {
      if (!validation.issues.length) error.value = new Error('方案未通过校验，请调整课程后重新预检')
      return
    }
    const old = s!.enrollments.filter((e) => e.state === 'ENROLLED').map((e) => e.offeringId)
    const names = (ids: string[]) =>
      ids.map((id) => cache.value[id]?.courseName || id).join('、') || '无'
    try {
      await ElMessageBox.confirm(
        `新增：${names(primary.value.filter((id) => !old.includes(id)))}；退课：${names(old.filter((id) => !primary.value.includes(id)))}；保留：${names(primary.value.filter((id) => old.includes(id)))}。两门备选不提前占位，最终结果以提交校验为准。`,
        '确认提交选课方案',
        { confirmButtonText: '正式提交', cancelButtonText: '继续编辑', type: 'warning' },
      )
    } catch {
      return
    }
    apply((await api.student.submit(selectedId.value, s!.draftRevision!, s!.version)).data)
    recovery.clear()
    notice.value = ''
    ElMessage.success('选课提交成功，有效课表已更新')
  } catch (e) {
    await handleFailure(e)
  } finally {
    busy.value = false
  }
}
async function deleteSchedule() {
  if (disabled.value || !schedule.value?.id) return
  try {
    await ElMessageBox.confirm(
      '这将撤销所有有效注册、释放名额并删除当前主选和备选方案。',
      '删除整个课表？',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  busy.value = true
  error.value = null
  try {
    await api.student.deleteSchedule(selectedId.value, schedule.value.version)
    apply(null)
    recovery.clear()
    await loadSchedule()
    ElMessage.success('课表已删除，原名额已释放')
  } catch (e) {
    await handleFailure(e)
  } finally {
    busy.value = false
  }
}
async function poll() {
  const ids = [...primary.value, ...alternate.value],
    sid = selectedId.value
  if (document.hidden || polling || !auth.user || !sid || !ids.length) return
  polling = true
  try {
    const latest = (await api.semesters.availability(sid, ids)).data
    if (sid !== selectedId.value || !auth.user) return
    const full = latest.filter(
      (o) =>
        o.enrolledCount >= o.maxStudents && (cache.value[o.id]?.enrolledCount || 0) < o.maxStudents,
    )
    remember(latest)
    if (full.length)
      notice.value = `${full.map((o) => o.courseName).join('、')} 名额已满。请核对方案；最终提交会再次校验。`
  } catch (e) {
    if (sid === selectedId.value && auth.user)
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
      <p class="page-description">安排下一段学习旅程。主选四门，备选两门。</p>
    </div>
    <SemesterSelect
      :model-value="selectedId"
      :semesters="choices"
      :disabled="busy"
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
      v-else
      type="info"
      >工作方案已同步</el-tag
    >
  </div>
  <el-tabs v-model="tab"
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
                ><div class="slot-course">
                  {{ cache[alternate[i - 1]]?.courseName || alternate[i - 1] }}
                </div>
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
                  @click="alternate.reverse()"
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
            :title="issue.message"
            type="error"
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
              type="primary"
              :disabled="disabled"
              :loading="busy"
              @click="submit"
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
      ><ScheduleDisplay :schedule="schedule" /></el-tab-pane></el-tabs
  ><OfferingDetail
    v-model="detailVisible"
    :offering="selectedDetail"
  />
</template>
