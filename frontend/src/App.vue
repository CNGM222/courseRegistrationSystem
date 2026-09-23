<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from './stores/auth'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
const router = useRouter(),
  auth = useAuthStore()
function expired() {
  if (!auth.user) return
  auth.setUser(null)
  ElMessage.warning('登录已过期，请重新登录。未提交选课方案暂存于当前标签页。')
  void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
}
onMounted(() => window.addEventListener('crs:unauthorized', expired))
onUnmounted(() => window.removeEventListener('crs:unauthorized', expired))
</script>
<template>
  <el-config-provider :locale="zhCn"><router-view /></el-config-provider>
</template>
