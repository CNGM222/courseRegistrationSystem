<script setup lang="ts">
import { useSemesters } from '../composables/useSemesters'
import SemesterSelect from '../components/SemesterSelect.vue'
import CatalogBrowser from '../components/CatalogBrowser.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import EmptyState from '../components/EmptyState.vue'
const { choices, selectedId, error, loading, load } = useSemesters()
</script>
<template>
  <div class="page-heading">
    <div>
      <h1 class="page-title">课程目录</h1>
      <p class="page-description">查看课程、先修要求与教学安排。</p>
    </div>
    <SemesterSelect
      v-model="selectedId"
      :semesters="choices"
      :disabled="loading"
    />
  </div>
  <ErrorNotice
    :error="error"
    retry
    @retry="load"
  />
  <p v-if="loading" role="status" class="muted">正在读取学期…</p>
  <EmptyState v-else-if="!error && !choices.length" title="暂无可查询学期" />
  <CatalogBrowser v-else-if="!error" :semester-id="selectedId" />
</template>
