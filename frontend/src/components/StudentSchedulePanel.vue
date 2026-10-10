<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { api } from '../services/api'
import { HttpError } from '../services/http'
import type { Schedule } from '../types/api'
import { useSemesters } from '../composables/useSemesters'
import SemesterSelect from './SemesterSelect.vue'
import ErrorNotice from './ErrorNotice.vue'
import ScheduleDisplay from './ScheduleDisplay.vue'
const props = defineProps<{ history?: boolean }>()
const {
  choices,
  selectedId,
  loading: semesterLoading,
  error: semesterError,
  load: reloadSemesters,
} = useSemesters(props.history ? 'ended' : 'current')
const schedule = ref<Schedule | null>(null),
  error = ref<unknown>(null),
  loading = ref(false)
let generation = 0
async function load() {
  const token = ++generation
  schedule.value = null
  error.value = null
  if (!selectedId.value) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const res = await api.student.schedule(selectedId.value)
    if (token === generation) schedule.value = res.data
  } catch (e) {
    if (
      token === generation &&
      !(e instanceof HttpError && e.status === 404 && e.code === 'SCHEDULE_NOT_FOUND')
    )
      error.value = e
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
      <h1 class="page-title">{{ history ? '选课历史' : '当前课表' }}</h1>
      <p class="page-description">
        {{
          history
            ? '按已结束学期查看最终课程、退课与取消记录。'
            : '这里展示实际获得名额的课程，不包含未提交草稿和待补位备选。'
        }}
      </p>
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
  <el-skeleton
    v-if="loading || semesterLoading"
    class="surface"
    :rows="5"
    animated
  />
  <div v-else>
    <ScheduleDisplay
      v-if="selectedId && !error && !loading && !semesterLoading"
      :schedule="schedule"
      :history="history"
    /><el-empty
      v-else-if="!semesterLoading && !semesterError && !choices.length"
      description="暂无可查看的学期"
    />
  </div>
</template>
