<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../services/api'
import { HttpError } from '../../services/http'
import { useSemesters } from '../../composables/useSemesters'
import { useDirtyGuard } from '../../composables/useDirtyGuard'
import { useAuthStore } from '../../stores/auth'
import type { TeachingPlan, Offering } from '../../types/api'
import { meetsText } from '../../utils/format'
import SemesterSelect from '../../components/SemesterSelect.vue'
import ErrorNotice from '../../components/ErrorNotice.vue'
import OfferingDetail from '../../components/OfferingDetail.vue'
const {
  choices,
  selectedId,
  open,
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
  detailVisible = ref(false)
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
  () => !open.value || loading.value || busy.value || blocked.value || !plan.value,
)
let generation = 0
async function load() {
  if (!selectedId.value) return
  const token = ++generation
  error.value = null
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
  if (await confirmLeave()) await load()
}
async function save() {
  if (disabled.value || !plan.value) return
  try {
    await ElMessageBox.confirm(
      '提交后将统一更新本人的授课选择，已取消的教学班不再由您任教。',
      '确认授课方案',
      { confirmButtonText: '保存方案', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  busy.value = true
  error.value = null
  try {
    plan.value = (
      await api.professor.saveTeaching(selectedId.value, selected.value, plan.value.version)
    ).data
    selected.value = [...plan.value.selectedOfferingIds]
    ElMessage.success('授课方案已保存')
  } catch (e) {
    error.value = e
    if (e instanceof HttpError && [0, 409, 412].includes(e.status)) blocked.value = true
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
  <section class="surface">
    <div class="surface-header">
      <strong>可授课教学班</strong>
      <div class="action-row">
        <el-tag
          v-if="dirty"
          type="warning"
          >尚未保存</el-tag
        ><el-button
          :disabled="!selectedId || busy"
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
      <el-checkbox-group v-model="selected"
        ><el-table
          v-loading="loading"
          :data="all"
          empty-text="本学期暂无可选择的授课教学班"
          ><el-table-column
            label="选择"
            width="65"
            ><template #default="{ row }"
              ><el-checkbox
                :value="row.id"
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
              <div class="course-code">
                {{ row.courseCode }} · {{ row.sectionCode }} 班
              </div></template
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
