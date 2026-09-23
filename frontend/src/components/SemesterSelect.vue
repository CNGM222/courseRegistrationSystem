<script setup lang="ts">
import type { Semester } from '../types/api'
defineProps<{ modelValue: string; semesters: Semester[]; disabled?: boolean }>()
defineEmits<{ 'update:modelValue': [value: string] }>()
</script>
<template>
  <el-select
    :model-value="modelValue"
    :disabled="disabled || !semesters.length"
    aria-label="选择学期"
    placeholder="选择学期"
    style="width: min(100%, 260px)"
    @update:model-value="$emit('update:modelValue', $event)"
    ><el-option
      v-for="s in semesters"
      :key="s.id"
      :label="s.name"
      :value="s.id"
  /></el-select>
</template>
