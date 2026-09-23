<script setup lang="ts">
import { useSemesters } from '../composables/useSemesters'
import SemesterSelect from '../components/SemesterSelect.vue'
import CatalogBrowser from '../components/CatalogBrowser.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
const { choices, selectedId, error, load } = useSemesters()
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
    />
  </div>
  <ErrorNotice
    :error="error"
    retry
    @retry="load"
  /><CatalogBrowser :semester-id="selectedId" />
</template>
