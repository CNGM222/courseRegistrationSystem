<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { api } from '../../services/api'
import { HttpError } from '../../services/http'
import { useSemesters } from '../../composables/useSemesters'
import type { Invoice } from '../../types/api'
import ErrorNotice from '../../components/ErrorNotice.vue'
import SemesterSelect from '../../components/SemesterSelect.vue'
import InvoiceDetail from '../../components/InvoiceDetail.vue'
const {
  choices,
  selectedId,
  loading: semesterLoading,
  error: semesterError,
  load: reloadSemesters,
} = useSemesters('all', () => choices.value.find((s) => s.status === 'CLOSED')?.id)
const invoice = ref<Invoice | null>(null),
  loading = ref(false),
  error = ref<unknown>(null),
  emptyMessage = ref('')
let generation = 0
async function load() {
  const token = ++generation
  invoice.value = null
  error.value = null
  emptyMessage.value = ''
  if (!selectedId.value) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const { data } = await api.student.invoice(selectedId.value)
    if (token === generation) invoice.value = data
  } catch (e) {
    if (token !== generation) return
    if (
      e instanceof HttpError &&
      ((e.status === 409 && e.code === 'INVOICE_NOT_READY') ||
        (e.status === 404 && e.code === 'INVOICE_NOT_FOUND'))
    )
      emptyMessage.value = e.message
    else error.value = e
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(selectedId, load)
onUnmounted(() => {
  generation++
  invoice.value = null
})
</script>

<template>
  <div class="page-heading">
    <div>
      <h1 class="page-title">我的账单</h1>
      <p class="page-description">
        查看本人各学期的学费账单和课程费用明细。注册最终关闭后生成账单。
      </p>
    </div>
    <div class="action-row">
      <SemesterSelect
        v-model="selectedId"
        :semesters="choices"
      />
      <el-button
        :disabled="!selectedId || semesterLoading"
        :loading="loading"
        @click="load"
        >刷新账单</el-button
      >
    </div>
  </div>
  <ErrorNotice
    :error="semesterError"
    retry
    @retry="reloadSemesters"
  />
  <ErrorNotice
    :error="error"
    retry
    @retry="load"
  />
  <el-skeleton
    v-if="semesterLoading || loading"
    class="surface"
    :rows="5"
    animated
  />
  <template v-else-if="!semesterError && !error">
    <el-empty
      v-if="!choices.length"
      description="暂无学期，暂时无法查询账单"
    />
    <el-empty
      v-else-if="emptyMessage"
      :description="emptyMessage"
    />
    <InvoiceDetail
      v-else-if="invoice"
      :invoice="invoice"
    />
  </template>
</template>
