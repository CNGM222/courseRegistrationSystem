<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import { HttpError } from '../../services/http'
import { useSemesters } from '../../composables/useSemesters'
import { useDirtyGuard } from '../../composables/useDirtyGuard'
import { useAuthStore } from '../../stores/auth'
import type { TeachingPlan, Offering } from '../../types/api'
import { dateTime, label, meetsText, registrationOpen } from '../../utils/format'
import SemesterSelect from '../../components/SemesterSelect.vue'
import ErrorNotice from '../../components/ErrorNotice.vue'
import OfferingDetail from '../../components/OfferingDetail.vue'
const {
  choices,
  selectedId,
  current,
  open,
  loading: semesterLoading,
  error: semesterError,
  load: reloadSemesters,
} = useSemesters('current')
const auth = useAuthStore(),
  plan = ref<TeachingPlan | null>(null),
  selected = ref<string[]>([]),
  busy = ref(false),
  loading = ref(false),
  blocked = ref(false),
  error = ref<unknown>(null)
const detail = ref<Offering | null>(null),
  detailVisible = ref(false),
  lastSubmittedIds = ref<string[]>([])
function showOffering(offering: Offering) {
  detail.value = offering
  detailVisible.value = true
}
const dirty = computed(
  () =>
    !!plan.value &&
    [...selected.value].sort().join(',') !== [...plan.value.selectedOfferingIds].sort().join(','),
)
const { confirmLeave } = useDirtyGuard(dirty)
const all = computed(() => [
  ...new Map(
    [...(plan.value?.eligibleOfferings || []), ...(plan.value?.selectedOfferings || [])].map(
      (o) => [o.id, o],
    ),
  ).values(),
])
const disabled = computed(
  () =>
    !open.value ||
    loading.value ||
    semesterLoading.value ||
    busy.value ||
    blocked.value ||
    !plan.value,
)
const offeringErrors = computed(() => {
  const details = error.value instanceof HttpError ? error.value.details : null
  const messages: Record<string, string[]> = {}
  if (!Array.isArray(details)) return messages
  for (const item of details) {
    if (!item || typeof item !== 'object') continue
    const index = typeof item.field === 'string' ? item.field.match(/^offeringIds\[(\d+)\]/) : null
    const id = item.offeringId || (index ? lastSubmittedIds.value[Number(index[1])] : undefined)
    if (typeof id === 'string') (messages[id] ||= []).push(item.message || '此教学班校验失败')
  }
  return messages
})
let generation = 0
async function load() {
  const token = ++generation
  error.value = null
  if (!selectedId.value) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const { data } = await api.professor.teaching(selectedId.value)
    if (token === generation) {
      plan.value = data
      selected.value = [...data.selectedOfferingIds]
      blocked.value = false
    }
  } catch (e) {
    if (token === generation) error.value = e
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(selectedId, () => {
  plan.value = null
  selected.value = []
  void load()
})
async function selectSemester(id: string) {
  if (await confirmLeave()) selectedId.value = id
}
async function refresh() {
  if (busy.value || loading.value) return
  if (await confirmLeave()) await load()
}
async function save() {
  if (disabled.value || !plan.value || !dirty.value) return
  const token = generation
  const semesterId = selectedId.value
  busy.value = true
  try {
    try {
      await ElMessageBox.confirm(
        '提交后将统一更新本人的授课选择，已取消的教学班不再由您任教。',
        '确认授课方案',
        { confirmButtonText: '保存方案', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
    if (
      token !== generation ||
      !registrationOpen(current.value) ||
      loading.value ||
      blocked.value ||
      !plan.value
    )
      return
    lastSubmittedIds.value = [...selected.value]
    error.value = null
    try {
      const { data } = await api.professor.saveTeaching(
        semesterId,
        lastSubmittedIds.value,
        plan.value.version,
      )
      if (token !== generation) return
      plan.value = data
      selected.value = [...data.selectedOfferingIds]
      ElMessage.success('授课方案已保存')
    } catch (e) {
      if (token !== generation) return
      error.value = e
      if (e instanceof HttpError && [0, 409, 412, 428].includes(e.status)) blocked.value = true
    }
  } finally {
    busy.value = false
  }
}
onUnmounted(() => generation++)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1 class="page-title">授课选择</h1>
      <p class="page-description">选择有资格教授的教学班，统一提交完整授课方案。</p>
    </div>
    <SemesterSelect
      :model-value="selectedId"
      :semesters="choices"
      :disabled="busy"
      @update:model-value="selectSemester"
    />
  </div>
  <p
    v-if="current"
    class="tiny muted"
  >
    {{ current.name }} · {{ label(current.status) }} · 授课选择开放：{{
      dateTime(current.registrationStart)
    }}
    至 {{ dateTime(current.addDropEnd) }}
  </p>
  <ErrorNotice
    :error="semesterError"
    retry
    @retry="reloadSemesters"
  /><ErrorNotice :error="error" /><el-alert
    v-if="selectedId && !open"
    title="注册已关闭或不在开放时段，无法更改授课选择。"
    type="info"
    :closable="false"
  /><el-alert
    v-if="blocked"
    title="请重新读取最新授课方案并核对；系统不会自动重复提交。"
    type="warning"
    :closable="false"
  />
  <section
    v-if="plan"
    class="surface"
  >
    <div class="surface-header">
      <strong>已保存的授课 · {{ plan.selectedOfferings.length }} 个教学班</strong>
    </div>
    <div class="surface-body">
      <el-table
        :data="plan.selectedOfferings"
        empty-text="尚未保存任何授课教学班"
      >
        <el-table-column
          label="教学班"
          min-width="180"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="showOffering(row)"
              >{{ row.courseName }}</el-button
            >
            <div class="course-code">{{ row.courseCode }} · {{ row.sectionCode }} 班</div>
          </template>
        </el-table-column>
        <el-table-column
          label="上课时间"
          min-width="250"
        >
          <template #default="{ row }"
            ><p
              v-for="m in meetsText(row.meetings)"
              :key="m"
              class="tiny"
            >
              {{ m }}
            </p></template
          >
        </el-table-column>
        <el-table-column
          label="状态"
          min-width="120"
        >
          <template #default="{ row }"
            >{{ label(row.status) }}
            <p
              v-if="row.cancelReason"
              class="tiny muted"
            >
              {{ label(row.cancelReason) }}
            </p></template
          >
        </el-table-column>
        <el-table-column
          label="名单与成绩"
          min-width="125"
        >
          <template #default="{ row }">
            <router-link
              class="table-link"
              :to="{
                path: `/professor/offerings/${encodeURIComponent(row.id)}/roster`,
                query: { semesterId: selectedId },
              }"
              >查看名单</router-link
            >
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
  <section class="surface">
    <div class="surface-header">
      <strong>待提交授课方案</strong>
      <div class="action-row">
        <el-tag
          v-if="dirty"
          type="warning"
          >尚未保存</el-tag
        ><el-button
          :disabled="!selectedId || busy || loading"
          :loading="loading"
          @click="refresh"
          >重新读取</el-button
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="disabled || !dirty"
          @click="save"
          >保存授课方案</el-button
        >
      </div>
    </div>
    <div class="surface-body">
      <el-skeleton
        v-if="(loading || semesterLoading) && !plan"
        :rows="4"
        animated
      />
      <el-checkbox-group
        v-if="plan"
        v-model="selected"
        ><el-table
          v-loading="loading"
          :data="all"
          empty-text="本学期暂无可选择的授课教学班"
          ><el-table-column
            label="选择"
            width="65"
            ><template #default="{ row }"
              ><el-checkbox
                :label="row.id"
                :aria-label="`选择${row.courseName}${row.sectionCode}班`"
                :disabled="
                  disabled ||
                  row.status !== 'OPEN' ||
                  (!!row.professorId && row.professorId !== auth.user?.personId)
                "
                ><span /></el-checkbox></template></el-table-column
          ><el-table-column
            label="课程"
            min-width="200"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                @click="showOffering(row)"
                >{{ row.courseName }}</el-button
              >
              <div class="course-code">{{ row.courseCode }} · {{ row.sectionCode }} 班</div>
              <p
                v-for="message in offeringErrors[row.id]"
                :key="message"
                class="field-error"
              >
                {{ message }}
              </p></template
            ></el-table-column
          ><el-table-column
            label="上课时间"
            min-width="250"
            ><template #default="{ row }"
              ><p
                class="tiny"
                v-for="m in meetsText(row.meetings)"
                :key="m"
              >
                {{ m }}
              </p></template
            ></el-table-column
          ><el-table-column
            label="方案状态"
            min-width="130"
            ><template #default="{ row }">
              <el-tag
                v-if="selected.includes(row.id) && !plan.selectedOfferingIds.includes(row.id)"
                size="small"
                type="warning"
                >待新增</el-tag
              >
              <el-tag
                v-else-if="!selected.includes(row.id) && plan.selectedOfferingIds.includes(row.id)"
                size="small"
                type="warning"
                >待取消</el-tag
              >
              <span v-else>{{
                plan.selectedOfferingIds.includes(row.id) ? '已保存' : label(row.status)
              }}</span>
            </template></el-table-column
          ><el-table-column
            label="当前教师"
            min-width="110"
            ><template #default="{ row }">{{
              row.professorName || '尚未选择'
            }}</template></el-table-column
          ><el-table-column
            label="人数"
            width="90"
            ><template #default="{ row }"
              >{{ row.enrolledCount }} / {{ row.maxStudents }}</template
            ></el-table-column
          ></el-table
        ></el-checkbox-group
      >
      <el-empty
        v-if="!selectedId && !semesterLoading && !semesterError"
        description="暂无可选择授课的学期"
      />
      <p class="tiny muted">
        资格、教师占用和时间冲突均在服务端最终校验，任一失败时原授课安排保持不变。
      </p>
    </div>
  </section>
  <OfferingDetail
    v-model="detailVisible"
    :offering="detail"
  />
</template>
