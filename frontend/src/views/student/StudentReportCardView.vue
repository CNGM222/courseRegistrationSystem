<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { api } from '../../services/api'
import { useSemesters } from '../../composables/useSemesters'
import type { ReportCard } from '../../types/api'
import ErrorNotice from '../../components/ErrorNotice.vue'
import SemesterSelect from '../../components/SemesterSelect.vue'
const { choices, selectedId, error: semesterError, load: reloadSemesters } = useSemesters('ended')
const card = ref<ReportCard | null>(null),
  loading = ref(false),
  error = ref<unknown>(null)
let generation = 0
async function load() {
  if (!selectedId.value) return
  const token = ++generation
  card.value = null
  loading.value = true
  error.value = null
  try {
    const { data } = await api.student.reportCard(selectedId.value)
    if (token === generation) card.value = data
  } catch (e) {
    if (token === generation) error.value = e
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(selectedId, load)
onUnmounted(() => {
  generation++
  card.value = null
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1 class="page-title">我的成绩单</h1>
      <p class="page-description">查看本人已结束学期的课程成绩。</p>
    </div>
    <div class="action-row">
      <SemesterSelect
        v-model="selectedId"
        :semesters="choices"
      /><el-button
        :disabled="!selectedId"
        :loading="loading"
        @click="load"
        >刷新</el-button
      >
    </div>
  </div>
  <ErrorNotice
    :error="semesterError"
    retry
    @retry="reloadSemesters"
  /><ErrorNotice
    :error="error"
    retry
    @retry="load"
  />
  <section class="surface">
    <div class="surface-header">
      <strong>{{ card?.semesterName || '学期成绩' }}</strong
      ><span class="tiny muted">未录入与 I（不完整）是不同状态</span>
    </div>
    <div class="surface-body">
      <el-table
        v-loading="loading"
        :data="card?.items || []"
        empty-text="暂无成绩信息"
        ><el-table-column
          prop="courseCode"
          label="课程代码"
          min-width="120"
        /><el-table-column
          prop="courseName"
          label="课程名称"
          min-width="180"
        /><el-table-column
          prop="sectionCode"
          label="教学班"
          min-width="90"
        /><el-table-column
          prop="professorName"
          label="教师"
          min-width="100"
        /><el-table-column
          label="成绩"
          width="120"
          ><template #default="{ row }"
            ><el-tag
              :type="
                row.grade === null
                  ? 'info'
                  : row.grade === 'F'
                    ? 'danger'
                    : row.grade === 'I'
                      ? 'warning'
                      : 'success'
              "
              >{{ row.grade ?? '未录入' }}</el-tag
            ></template
          ></el-table-column
        ></el-table
      >
    </div>
  </section>
</template>
