<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../../services/api'
import { useSemesters } from '../../composables/useSemesters'
import type { Offering } from '../../types/api'
import { ended, label, meetsText } from '../../utils/format'
import SemesterSelect from '../../components/SemesterSelect.vue'
import ErrorNotice from '../../components/ErrorNotice.vue'
const route = useRoute()
const {
  semesters,
  choices,
  selectedId,
  current,
  loading: semesterLoading,
  error: semesterError,
  load: reloadSemesters,
} = useSemesters('all', () => {
  if (typeof route.query.semesterId === 'string') return route.query.semesterId
  return [...semesters.value].filter(ended).sort((a, b) => b.endDate.localeCompare(a.endDate))[0]
    ?.id
})
const items = ref<Offering[]>([]),
  error = ref<unknown>(null),
  loading = ref(false)
let generation = 0
async function load() {
  const token = ++generation
  items.value = []
  error.value = null
  if (!selectedId.value) {
    loading.value = false
    return
  }
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
      <p class="page-description">默认显示最近已结束学期的本人教学班，也可切换学期查看名单。</p>
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
  <p
    v-if="current"
    class="tiny muted"
  >
    {{ current.name }} · {{ current.startDate }} 至 {{ current.endDate }} ·
    {{ label(current.status) }}
  </p>
  <el-skeleton
    v-if="loading || semesterLoading"
    :rows="3"
    animated
  />
  <div
    v-if="!loading"
    class="class-grid"
  >
    <router-link
      v-for="o in items"
      :key="o.id"
      :to="{
        path: `/professor/offerings/${encodeURIComponent(o.id)}/roster`,
        query: { semesterId: selectedId },
      }"
      class="surface class-card"
      ><div class="course-code">{{ o.courseCode }} · {{ o.sectionCode }} 班</div>
      <h2>{{ o.courseName }}</h2>
      <p class="muted">{{ o.department }} · {{ o.enrolledCount }} 名学生</p>
      <p
        v-for="meeting in meetsText(o.meetings)"
        :key="meeting"
        class="tiny muted"
      >
        {{ meeting }}
      </p>
      <p
        v-if="o.cancelReason"
        class="tiny muted"
      >
        取消原因：{{ label(o.cancelReason) }}
      </p>
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
    v-if="!items.length && !loading && !semesterLoading && !error && !semesterError"
    :description="selectedId ? '本学期暂无本人授课教学班' : '暂无可查询的学期'"
  />
</template>
