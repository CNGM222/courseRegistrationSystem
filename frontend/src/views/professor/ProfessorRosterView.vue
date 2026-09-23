<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import { HttpError } from '../../services/http'
import type { Roster, GradeValue } from '../../types/api'
import { gradeValues } from '../../utils/format'
import { useDirtyGuard } from '../../composables/useDirtyGuard'
import ErrorNotice from '../../components/ErrorNotice.vue'
const route = useRoute(),
  id = String(route.params.offeringId)
const roster = ref<Roster | null>(null),
  edits = ref<Record<string, GradeValue | ''>>({}),
  loading = ref(false),
  busy = ref(false),
  blocked = ref(false),
  error = ref<unknown>(null)
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
  return Array.isArray(details)
    ? Object.fromEntries(
        details
          .filter((d) => d.enrollmentId)
          .map((d) => [d.enrollmentId, d.message || '此行校验失败']),
      )
    : {}
})
const dirty = computed(() => changes.value.length > 0),
  { confirmLeave } = useDirtyGuard(dirty)
let active = true
function apply(data: Roster) {
  roster.value = data
  edits.value = Object.fromEntries(data.students.map((s) => [s.enrollmentId, s.grade || '']))
}
async function load() {
  loading.value = true
  error.value = null
  try {
    const { data } = await api.professor.roster(id)
    if (active) {
      apply(data)
      blocked.value = false
    }
  } catch (e) {
    if (active) error.value = e
  } finally {
    if (active) loading.value = false
  }
}
async function refresh() {
  if (await confirmLeave()) await load()
}
async function save() {
  if (!roster.value?.canEditGrades || !changes.value.length || blocked.value) return
  try {
    await ElMessageBox.confirm(
      `本次将更新 ${changes.value.length} 名学生的成绩。未修改与未录入行保持不变。`,
      '保存成绩',
      { confirmButtonText: '确认保存', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  busy.value = true
  error.value = null
  try {
    apply((await api.professor.saveGrades(id, changes.value)).data)
    ElMessage.success('成绩已保存')
  } catch (e) {
    error.value = e
    if (e instanceof HttpError && [0, 409, 412].includes(e.status)) blocked.value = true
  } finally {
    busy.value = false
  }
}
onMounted(load)
onUnmounted(() => {
  active = false
  roster.value = null
  edits.value = {}
})
</script>
<template>
  <div class="page-heading">
    <div>
      <router-link
        to="/professor/classes"
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
        :disabled="busy"
        @click="refresh"
        >重新读取</el-button
      ><el-button
        v-if="roster?.canEditGrades"
        type="primary"
        :loading="busy"
        :disabled="!dirty || blocked || loading"
        @click="save"
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
      <el-table
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
              v-if="rowErrors[row.enrollmentId]"
              class="field-error"
            >
              {{ rowErrors[row.enrollmentId] }}
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
        ></el-table
      >
      <p class="tiny muted">
        合法成绩：A、B、C、D、F、I。空白表示未录入；已保存的成绩不能通过留空清除。
      </p>
    </div>
  </section>
</template>
