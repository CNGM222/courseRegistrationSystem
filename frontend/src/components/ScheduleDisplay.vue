<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Schedule } from '../types/api'
import { label, meetsText, weekdays } from '../utils/format'
const props = defineProps<{ schedule: Schedule | null; history?: boolean }>()
const mode = ref('list'),
  week = ref(1)
const effectiveEnrollments = computed(
  () => props.schedule?.enrollments.filter((e) => e.state === 'ENROLLED') || [],
)
const lastWeek = computed(() =>
  Math.max(
    1,
    ...effectiveEnrollments.value.flatMap((e) => (e.offering?.meetings || []).map((m) => m.weekNo)),
  ),
)
const hasWeekMeetings = computed(() =>
  effectiveEnrollments.value.some((e) => e.offering?.meetings.some((m) => m.weekNo === week.value)),
)
watch(
  () => props.schedule?.semesterId,
  () => {
    week.value = 1
  },
)
watch(lastWeek, (value) => {
  week.value = Math.min(week.value, value)
})
</script>
<template>
  <div
    v-if="schedule"
    class="surface"
  >
    <div class="surface-header">
      <div>
        <strong>有效课表</strong
        ><el-tag
          size="small"
          style="margin-left: 12px"
          >{{ label(schedule.status) }}</el-tag
        >
      </div>
      <el-radio-group
        v-model="mode"
        size="small"
        ><el-radio-button value="list">列表</el-radio-button
        ><el-radio-button value="week">周课表</el-radio-button></el-radio-group
      >
    </div>
    <div class="surface-body">
      <template v-if="mode === 'week'"
        ><div
          class="filter-row"
          style="margin-bottom: 16px"
        >
          <span>教学周</span
          ><el-input-number
            v-model="week"
            :min="1"
            :max="lastWeek"
            size="small"
            aria-label="教学周"
          />
        </div>
        <el-empty
          v-if="!hasWeekMeetings"
          :description="`第 ${week} 周没有已注册课程的上课安排`"
        />
        <div class="timeline">
          <div
            v-for="(d, index) in weekdays"
            :key="d"
            class="timeline-day"
          >
            <h4>星期{{ d }}</h4>
            <template
              v-for="e in effectiveEnrollments"
              :key="e.id"
              ><div
                v-for="(m, j) in (e.offering?.meetings || []).filter(
                  (m) => m.weekday === index + 1 && m.weekNo === week,
                )"
                :key="j"
                class="timeline-item"
              >
                <strong>{{ e.offering?.courseName }}</strong>
                <p>{{ m.startPeriod }}–{{ m.endPeriodExclusive - 1 }} 节</p>
                <span
                  >{{ m.room || '地点待定' }} · {{ e.offering?.professorName || '教师待定' }}</span
                >
              </div></template
            >
          </div>
        </div></template
      ><el-table
        v-else
        :data="effectiveEnrollments"
        empty-text="尚无有效注册课程，草稿与备选不在此处占位"
        ><el-table-column
          label="课程"
          min-width="180"
          ><template #default="{ row }"
            ><div class="course-title">{{ row.offering?.courseName || row.offeringId }}</div>
            <div class="course-code">
              {{ row.offering?.courseCode }} · {{ row.offering?.sectionCode }} 班
            </div></template
          ></el-table-column
        ><el-table-column
          label="教师"
          min-width="100"
          ><template #default="{ row }">{{
            row.offering?.professorName || '待安排'
          }}</template></el-table-column
        ><el-table-column
          label="时间"
          min-width="230"
          ><template #default="{ row }"
            ><div
              v-for="m in meetsText(row.offering?.meetings || [])"
              :key="m"
              class="tiny"
            >
              {{ m }}
            </div></template
          ></el-table-column
        ><el-table-column
          label="注册来源"
          width="95"
          ><template #default="{ row }">{{ label(row.source) }}</template></el-table-column
        ></el-table
      ><template v-if="schedule.enrollments.some((e) => e.state !== 'ENROLLED')"
        ><h4>退课与取消记录</h4>
        <div
          v-for="e in schedule.enrollments.filter((e) => e.state !== 'ENROLLED')"
          :key="e.id"
          class="history-row"
        >
          <span
            >{{ e.offering?.courseName || e.offeringId
            }}<span
              v-if="e.offering"
              class="tiny muted"
            >
              · {{ e.offering.courseCode }} · {{ e.offering.sectionCode }} 班</span
            ></span
          ><el-tag
            type="info"
            size="small"
            >{{ label(e.state)
            }}<template v-if="e.reason"> · {{ label(e.reason) }}</template></el-tag
          >
        </div></template
      >
    </div>
  </div>
  <div
    v-else
    class="surface"
  >
    <div class="empty-state">
      <strong>{{ history ? '该学期没有选课记录' : '本学期还没有课表' }}</strong>
      {{ history ? '可切换其他已结束学期查看。' : '完成选课提交后，可在此查看有效课程。' }}
    </div>
  </div>
</template>
