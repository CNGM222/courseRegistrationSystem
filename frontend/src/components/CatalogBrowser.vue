<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { api } from '../services/api'
import { dateTime, meetsText } from '../utils/format'
import type { Offering } from '../types/api'
import ErrorNotice from './ErrorNotice.vue'
import OfferingDetail from './OfferingDetail.vue'
const props = defineProps<{ semesterId: string; selectable?: boolean; disabled?: boolean }>()
const emit = defineEmits<{
  select: [offering: Offering, kind: 'PRIMARY' | 'ALTERNATE']
  loaded: [offerings: Offering[]]
}>()
const items = ref<Offering[]>([]),
  loading = ref(false),
  error = ref<unknown>(null)
const keyword = ref(''),
  department = ref(''),
  departments = ref<string[]>([]),
  page = ref(1),
  total = ref(0),
  snapshot = ref('')
const detail = ref<Offering | null>(null),
  showDetail = ref(false)
function showOffering(offering: Offering) {
  detail.value = offering
  showDetail.value = true
}
let generation = 0
async function load() {
  if (!props.semesterId) return
  const token = ++generation
  loading.value = true
  error.value = null
  try {
    const { data } = await api.semesters.offerings(props.semesterId, {
      page: page.value,
      pageSize: 10,
      keyword: keyword.value.trim(),
      department: department.value,
    })
    if (token !== generation) return
    items.value = data.items
    total.value = data.total
    snapshot.value = data.snapshotVerifiedAt || ''
    departments.value = data.departments || []
    emit('loaded', data.items)
  } catch (e) {
    if (token === generation) {
      error.value = e
      items.value = []
    }
  } finally {
    if (token === generation) loading.value = false
  }
}
function search() {
  page.value = 1
  void load()
}
watch(
  () => props.semesterId,
  () => {
    items.value = []
    department.value = ''
    page.value = 1
    void load()
  },
  { immediate: true },
)
onUnmounted(() => {
  generation++
})
</script>
<template>
  <section class="surface">
    <div class="surface-header">
      <div>
        <strong>课程目录</strong>
        <p class="tiny muted">目录校验时间：{{ dateTime(snapshot) }}</p>
      </div>
      <el-button
        :loading="loading"
        :disabled="!semesterId"
        @click="load"
        >刷新</el-button
      >
    </div>
    <div class="surface-body">
      <form
        class="filter-row"
        @submit.prevent="search"
      >
        <el-input
          v-model="keyword"
          placeholder="课程名称 / 课程代码"
          aria-label="搜索课程"
          clearable
          style="width: 240px"
        /><el-select
          v-model="department"
          clearable
          filterable
          placeholder="全部院系"
          aria-label="筛选院系"
          style="width: 170px"
          @change="search"
          ><el-option
            v-for="d in departments"
            :key="d"
            :label="d"
            :value="d" /></el-select
        ><el-button
          native-type="submit"
          type="primary"
          plain
          :disabled="!semesterId"
          >查询</el-button
        >
      </form>
      <ErrorNotice
        :error="error"
        retry
        @retry="load"
      /><el-table
        v-loading="loading"
        :data="items"
        empty-text="暂无符合条件的课程"
        style="margin-top: 16px"
        ><el-table-column
          label="课程"
          min-width="200"
          ><template #default="{ row }"
            ><el-button
              link
              type="primary"
              class="course-title"
              @click="showOffering(row)"
              >{{ row.courseName }}</el-button
            >
            <div class="course-code">
              {{ row.courseCode }} · {{ row.sectionCode }} 班 · {{ row.credits }} 学分
            </div>
            <div class="tiny muted">先修：{{ row.prerequisites.join('、') || '无' }}</div></template
          ></el-table-column
        ><el-table-column
          prop="department"
          label="院系"
          min-width="140"
        /><el-table-column
          label="教师"
          min-width="105"
          ><template #default="{ row }">{{
            row.professorName || '待安排'
          }}</template></el-table-column
        ><el-table-column
          label="上课时间"
          min-width="220"
          ><template #default="{ row }"
            ><div
              v-for="m in meetsText(row.meetings)"
              :key="m"
              class="tiny"
            >
              {{ m }}
            </div></template
          ></el-table-column
        ><el-table-column
          label="名额"
          width="94"
          ><template #default="{ row }"
            ><el-tag
              :type="row.enrolledCount >= row.maxStudents ? 'danger' : 'success'"
              size="small"
              >{{ row.enrolledCount }} / {{ row.maxStudents }}</el-tag
            ></template
          ></el-table-column
        ><el-table-column
          v-if="selectable"
          label="加入方案"
          width="155"
          fixed="right"
          ><template #default="{ row }"
            ><el-button
              link
              type="primary"
              :disabled="disabled || row.status !== 'OPEN'"
              @click="emit('select', row, 'PRIMARY')"
              >主选</el-button
            ><el-button
              link
              :disabled="disabled || row.status !== 'OPEN'"
              @click="emit('select', row, 'ALTERNATE')"
              >备选</el-button
            ></template
          ></el-table-column
        ><el-table-column
          v-else
          label="详情"
          width="75"
          ><template #default="{ row }"
            ><el-button
              link
              type="primary"
              @click="showOffering(row)"
              >查看</el-button
            ></template
          ></el-table-column
        ></el-table
      ><el-pagination
        v-model:current-page="page"
        :page-size="10"
        :total="total"
        layout="prev, pager, next, total"
        small
        class="pagination"
        @current-change="load"
      />
    </div>
    <OfferingDetail
      v-model="showDetail"
      :offering="detail"
    />
  </section>
</template>
