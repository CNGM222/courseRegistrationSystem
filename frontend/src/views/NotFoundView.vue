<script setup lang="ts">
defineProps<{ forbidden?: boolean }>()
</script>
<template>
  <el-result
    :icon="forbidden ? 'warning' : 'info'"
    :title="forbidden ? '当前账号无权访问此页面' : '页面不存在'"
    sub-title="请通过工作台导航访问可用服务。"
    ><template #extra
      ><router-link to="/home"
        ><el-button type="primary">返回工作台</el-button></router-link
      ></template
    ></el-result
  >
</template>
