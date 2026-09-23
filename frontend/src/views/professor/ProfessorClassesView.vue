<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { api } from '../../services/api'
import { useSemesters } from '../../composables/useSemesters'
import type { Offering } from '../../types/api'
import { label } from '../../utils/format'
import SemesterSelect from '../../components/SemesterSelect.vue'
import ErrorNotice from '../../components/ErrorNotice.vue'
const { choices, selectedId, error: semesterError, load: reloadSemesters } = useSemesters()
const items = ref<Offering[]>([]),
  error = ref<unknown>(null),
  loading = ref(false)
let generation = 0
async function load() {
  if (!selectedId.value) return
  const token = ++generation
  items.value = []
  error.value = null
  loading.value = true
  try {
    const res = await api.professor.teaching(selectedId.value)
    if (token === generation) items.value = res.data.selectedOfferings
  } catch (e) {
    if (token === generation) error.value = e
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(selectedId, load)
onUnmounted(() => generation++)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1 class="page-title">班级与成绩</h1>
      <p class="page-description">查看本人教学班名单；学期结束后可录入和修订成绩。</p>
    </div>
    <SemesterSelect
      v-model="selectedId"
      :semesters="choices"
    />
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
  <div
    v-loading="loading"
    class="class-grid"
  >
    <router-link
      v-for="o in items"
      :key="o.id"
      :to="`/professor/offerings/${o.id}/roster`"
      class="surface class-card"
      ><div class="course-code">{{ o.courseCode }} · {{ o.sectionCode }} 班</div>
      <h2>{{ o.courseName }}</h2>
      <p class="muted">{{ o.department }} · {{ o.enrolledCount }} 名学生</p>
      <div class="action-row">
        <el-tag
          size="small"
          type="info"
          >{{ label(o.status) }}</el-tag
        ><span class="table-link">查看名单与成绩 →</span>
      </div></router-link
    >
  </div>
  <el-empty
    v-if="!items.length && !loading && !error"
    description="本学期暂无本人授课教学班"
  />
</template>
