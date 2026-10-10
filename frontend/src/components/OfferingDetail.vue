<script setup lang="ts">
import { meetsText, label } from '../utils/format'
import type { Offering } from '../types/api'
import ErrorNotice from './ErrorNotice.vue'
defineProps<{ offering: Offering | null; modelValue: boolean; loading?: boolean; error?: unknown }>()
defineEmits(['update:modelValue', 'retry'])
</script>
<template>
  <el-drawer
    :model-value="modelValue"
    title="教学班详情"
    size="min(520px, 94vw)"
    @update:model-value="$emit('update:modelValue', $event)"
    ><ErrorNotice :error="error" retry @retry="$emit('retry')" />
    <p v-if="loading" role="status" class="muted">正在读取教学班详情…</p>
    <template v-if="offering && !error && !loading"
      ><h2 class="course-title">{{ offering.courseName }}</h2>
      <p class="muted">{{ offering.courseCode }} · {{ offering.sectionCode }} 班</p>
      <el-descriptions
        :column="1"
        border
        ><el-descriptions-item label="院系">{{ offering.department }}</el-descriptions-item
        ><el-descriptions-item label="教师">{{
          offering.professorName || '尚未安排'
        }}</el-descriptions-item
        ><el-descriptions-item label="学分">{{ offering.credits }}</el-descriptions-item
        ><el-descriptions-item label="人数"
          >{{ offering.enrolledCount }} / {{ offering.maxStudents }}（最低
          {{ offering.minStudents }} 人）</el-descriptions-item
        ><el-descriptions-item label="状态">{{ label(offering.status) }}</el-descriptions-item
        ><el-descriptions-item v-if="offering.cancelReason" label="取消原因">{{ label(offering.cancelReason) }}</el-descriptions-item
        ><el-descriptions-item label="先修课程">{{
          offering.prerequisites.join('、') || '无'
        }}</el-descriptions-item
        ><el-descriptions-item label="课程费用"
          >{{ offering.currency }} {{ offering.tuition }}</el-descriptions-item
        ><el-descriptions-item label="上课安排"
          ><p
            v-for="m in meetsText(offering.meetings)"
            :key="m"
          >
            {{ m }}
          </p></el-descriptions-item
        ></el-descriptions
      ></template
    ></el-drawer
  >
</template>
