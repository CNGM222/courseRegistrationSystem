<script setup lang="ts">
import { computed } from 'vue'
import { HttpError } from '../services/http'
import { errorMessage } from '../utils/format'
const props = defineProps<{ error: unknown; retry?: boolean }>()
defineEmits(['retry'])
const detail = computed(() => (props.error instanceof HttpError ? props.error : null))
</script>
<template>
  <el-alert
    v-if="error"
    :title="errorMessage(error)"
    type="error"
    show-icon
    :closable="false"
    class="error-notice"
    ><p v-if="detail?.status === 412">数据已被其他操作更新，请重新读取后核对修改。</p>
    <p
      v-if="detail?.requestId"
      class="muted"
    >
      请求编号：{{ detail.requestId }}
    </p>
    <ul v-if="Array.isArray(detail?.details)">
      <li
        v-for="(d, i) in detail?.details as any[]"
        :key="i"
      >
        {{ d.message || d.field || d.offeringId || d.code }}
      </li>
    </ul>
    <el-button
      v-if="retry"
      size="small"
      @click="$emit('retry')"
      >重新加载</el-button
    ></el-alert
  >
</template>
