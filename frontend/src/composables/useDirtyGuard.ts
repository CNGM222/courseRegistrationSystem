import { onMounted, onUnmounted, type Ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
export function useDirtyGuard(dirty: Ref<boolean>) {
  const auth = useAuthStore()
  async function confirmLeave() {
    if (!dirty.value || !auth.user) return true
    try {
      await ElMessageBox.confirm('存在尚未保存的修改，离开后这些修改将丢失。', '离开此页面？', {
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑',
        type: 'warning',
      })
      return true
    } catch {
      return false
    }
  }
  const beforeUnload = (e: BeforeUnloadEvent) => {
    if (dirty.value) {
      e.preventDefault()
      e.returnValue = ''
    }
  }
  onBeforeRouteLeave(confirmLeave)
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
  return { confirmLeave }
}
