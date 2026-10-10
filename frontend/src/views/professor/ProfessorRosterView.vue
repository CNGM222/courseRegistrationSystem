<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { onBeforeRouteUpdate, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import { HttpError } from '../../services/http'
import type { Roster, GradeValue } from '../../types/api'
import { gradeValues, label, meetsText } from '../../utils/format'
import { useDirtyGuard } from '../../composables/useDirtyGuard'
import ErrorNotice from '../../components/ErrorNotice.vue'
const route = useRoute(),
  id = computed(() => String(route.params.offeringId))
const roster = ref<Roster | null>(null),
  edits = ref<Record<string, GradeValue | ''>>({}),
  loading = ref(false),
  busy = ref(false),
  blocked = ref(false),
  error = ref<unknown>(null),
  lastSubmittedIds = ref<string[]>([])
const changes = computed(() =>
  (roster.value?.students || [])
    .filter((s) => edits.value[s.enrollmentId] && edits.value[s.enrollmentId] !== s.grade)
    .map((s) => ({
      enrollmentId: s.enrollmentId,
      grade: edits.value[s.enrollmentId] as GradeValue,
      expectedVersion: s.version,
    })),
)
const rowErrors = computed(() => {
  const details = error.value instanceof HttpError ? error.value.details : null
  const messages: Record<string, string[]> = {}
  if (!Array.isArray(details)) return messages
  for (const item of details) {
    if (!item || typeof item !== 'object') continue
    const index =
      typeof item.field === 'string' ? item.field.match(/^entries(?:\[(\d+)\]|\.(\d+))/) : null
    const enrollmentId =
      item.enrollmentId ||
      (index ? lastSubmittedIds.value[Number(index[1] || index[2])] : undefined)
    if (typeof enrollmentId === 'string')
      (messages[enrollmentId] ||= []).push(item.message || '此行校验失败')
  }
  return messages
})
const dirty = computed(() => changes.value.length > 0),
  { confirmLeave } = useDirtyGuard(dirty)
const canSave = computed(
  () => !!roster.value?.canEditGrades && !loading.value && !busy.value && !blocked.value,
)
let generation = 0
function apply(data: Roster, pending: Record<string, GradeValue> = {}) {
  roster.value = data
  edits.value = Object.fromEntries(
    data.students.map((s) => [s.enrollmentId, pending[s.enrollmentId] || s.grade || '']),
  )
}
async function load() {
  const token = ++generation
  const offeringId = id.value
  loading.value = true
  error.value = null
  try {
    const { data } = await api.professor.roster(offeringId)
    if (token === generation) {
      apply(data)
      blocked.value = false
    }
  } catch (e) {
    if (token === generation) error.value = e
  } finally {
    if (token === generation) loading.value = false
  }
}
async function refresh() {
  if (loading.value || busy.value) return
  if (await confirmLeave()) await load()
}
async function save(enrollmentId?: string) {
  if (!canSave.value || !changes.value.length) return
  const entries = changes.value.filter(
    (entry) => !enrollmentId || entry.enrollmentId === enrollmentId,
  )
  if (!entries.length) return
  const token = generation
  const offeringId = id.value
  busy.value = true
  try {
    try {
      await ElMessageBox.confirm(
        `本次将更新 ${entries.length} 名学生的成绩。未提交与未录入行保持不变。`,
        '保存成绩',
        { confirmButtonText: '确认保存', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
    if (token !== generation || !roster.value?.canEditGrades || blocked.value || loading.value)
      return
    const pending = Object.fromEntries(
      changes.value
        .filter((entry) => !entries.some((saved) => saved.enrollmentId === entry.enrollmentId))
        .map((entry) => [entry.enrollmentId, entry.grade]),
    )
    lastSubmittedIds.value = entries.map((entry) => entry.enrollmentId)
    error.value = null
    try {
      const { data } = await api.professor.saveGrades(offeringId, entries)
      if (token !== generation) return
      apply(data, pending)
      ElMessage.success('成绩已保存')
    } catch (e) {
      if (token !== generation) return
      error.value = e
      if (e instanceof HttpError && [0, 409, 412, 428].includes(e.status)) blocked.value = true
    }
  } finally {
    busy.value = false
  }
}
onBeforeRouteUpdate(async (to, from) => {
  if (to.params.offeringId === from.params.offeringId) return true
  if (busy.value) return false
  return confirmLeave()
})
watch(
  id,
  () => {
    roster.value = null
    edits.value = {}
    blocked.value = false
    void load()
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
  roster.value = null
  edits.value = {}
})
</script>
<template>
  <div class="page-heading">
    <div>
      <router-link
        :to="{
          path: '/professor/classes',
          query: roster
            ? { semesterId: roster.offering.semesterId }
            : route.query.semesterId
              ? { semesterId: route.query.semesterId }
              : {},
        }"
        class="table-link tiny"
        >← 返回教学班</router-link
      >
      <h1
        class="page-title"
        style="margin-top: 10px"
      >
        {{ roster?.offering.courseName || '学生名单与成绩' }}
      </h1>
      <p class="page-description">
        {{ roster?.offering.sectionCode }} 班 · 仅正式注册学生进入本名单
      </p>
    </div>
    <div class="action-row">
      <el-button
        :loading="loading"
        :disabled="busy || loading"
        @click="refresh"
        >重新读取</el-button
      ><el-button
        v-if="roster?.canEditGrades"
        type="primary"
        :loading="busy"
        :disabled="!dirty || !canSave"
        @click="save()"
        >保存 {{ changes.length }} 项修改</el-button
      >
    </div>
  </div>
  <ErrorNotice :error="error" /><el-alert
    v-if="blocked"
    title="服务器数据可能已变化，请重新读取并核对成绩后再保存。"
    type="warning"
    :closable="false"
  /><el-alert
    v-if="roster && !roster.canEditGrades"
    title="本教学班当前只允许查看名单，成绩尚不可编辑。"
    type="info"
    :closable="false"
  />
  <section class="surface">
    <div class="surface-body">
      <el-skeleton
        v-if="loading && !roster"
        :rows="4"
        animated
      />
      <div
        v-if="roster"
        class="roster-summary tiny muted"
      >
        <p>
          {{ roster.offering.courseCode }} · {{ roster.offering.sectionCode }} 班 ·
          {{ roster.offering.department }} · {{ label(roster.offering.status) }} ·
          {{ roster.students.length }} 名正式注册学生
        </p>
        <p
          v-for="meeting in meetsText(roster.offering.meetings)"
          :key="meeting"
        >
          {{ meeting }}
        </p>
        <p v-if="roster.offering.cancelReason">
          取消原因：{{ label(roster.offering.cancelReason) }}
        </p>
      </div>
      <el-table
        v-if="roster"
        v-loading="loading"
        :data="roster?.students || []"
        empty-text="暂无正式注册学生"
        ><el-table-column
          prop="studentId"
          label="学生 ID"
          min-width="130"
        /><el-table-column
          prop="studentName"
          label="姓名"
          min-width="130"
        /><el-table-column
          label="当前成绩"
          min-width="110"
          ><template #default="{ row }">{{ row.grade ?? '未录入' }}</template></el-table-column
        ><el-table-column
          v-if="roster?.canEditGrades"
          label="录入 / 修订"
          min-width="185"
          ><template #default="{ row }"
            ><el-select
              v-model="edits[row.enrollmentId]"
              :aria-label="`${row.studentName}的成绩`"
              :disabled="busy || blocked || loading"
              placeholder="留空暂不录入"
              style="width: 160px"
              ><el-option
                v-if="row.grade === null"
                label="未录入（跳过）"
                value="" /><el-option
                v-for="g in gradeValues"
                :key="g"
                :label="g === 'I' ? 'I · 不完整' : g"
                :value="g"
            /></el-select>
            <p
              v-for="message in rowErrors[row.enrollmentId]"
              :key="message"
              class="field-error"
            >
              {{ message }}
            </p></template
          ></el-table-column
        ><el-table-column
          label="修改状态"
          width="110"
          ><template #default="{ row }"
            ><el-tag
              v-if="edits[row.enrollmentId] && edits[row.enrollmentId] !== row.grade"
              type="warning"
              size="small"
              >未保存</el-tag
            ><span
              v-else
              class="muted"
              >—</span
            ></template
          ></el-table-column
        ><el-table-column
          v-if="roster?.canEditGrades"
          label="逐人保存"
          min-width="110"
        >
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              :disabled="
                !canSave || !changes.some((entry) => entry.enrollmentId === row.enrollmentId)
              "
              :aria-label="`保存${row.studentName}的成绩`"
              @click="save(row.enrollmentId)"
              >保存此行</el-button
            >
          </template>
        </el-table-column></el-table
      >
      <p class="tiny muted">
        合法成绩：A、B、C、D、F、I。空白表示未录入；已保存的成绩不能通过留空清除。
      </p>
    </div>
  </section>
</template>
