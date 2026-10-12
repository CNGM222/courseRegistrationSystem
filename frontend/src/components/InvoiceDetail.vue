<script setup lang="ts">
import type { Invoice } from '../types/api'
import { dateTime, label } from '../utils/format'
defineProps<{ invoice: Invoice }>()
</script>

<template>
  <section class="surface">
    <div class="surface-header">
      <strong>{{ invoice.semesterName }}</strong>
      <el-tag type="success">账单已生成</el-tag>
    </div>
    <div class="surface-body">
      <el-descriptions
        :column="1"
        border
      >
        <el-descriptions-item label="账单编号">{{ invoice.id }}</el-descriptions-item>
        <el-descriptions-item label="学生"
          >{{ invoice.studentName }}（{{ invoice.studentId }}）</el-descriptions-item
        >
        <el-descriptions-item label="生成时间">{{
          dateTime(invoice.createdAt)
        }}</el-descriptions-item>
        <el-descriptions-item label="学费合计">
          <strong class="invoice-total">{{ invoice.totalAmount }} {{ invoice.currency }}</strong>
        </el-descriptions-item>
        <el-descriptions-item label="财务通知">
          <el-tag :type="invoice.deliveryState === 'DELIVERED' ? 'success' : 'warning'">
            {{ invoice.deliveryState ? label(invoice.deliveryState) : '状态暂不可用' }}
          </el-tag>
          <span
            v-if="invoice.deliveredAt"
            class="tiny muted"
          >
            · {{ dateTime(invoice.deliveredAt) }}</span
          >
        </el-descriptions-item>
      </el-descriptions>
      <p class="tiny muted">
        费用按注册最终关闭时的有效课程确定；财务通知状态表示账单信息的发送进度。
      </p>
      <el-alert
        v-if="!invoice.items.length"
        title="本学期最终有效课程为零，无应缴学费，已生成零金额账单。"
        type="info"
        :closable="false"
        show-icon
      />
      <el-table
        v-else
        :data="invoice.items"
        row-key="offeringId"
      >
        <el-table-column
          prop="courseCode"
          label="课程代码"
          min-width="110"
        />
        <el-table-column
          prop="courseName"
          label="课程名称"
          min-width="170"
        />
        <el-table-column
          prop="sectionCode"
          label="教学班"
          min-width="90"
        />
        <el-table-column
          prop="professorName"
          label="教师"
          min-width="100"
        />
        <el-table-column
          label="学费"
          min-width="140"
          align="right"
        >
          <template #default="{ row }">{{ row.amount }} {{ invoice.currency }}</template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>
