import { ref, computed, onMounted, onUnmounted } from 'vue'
import { api } from '../services/api'
import { ended, registrationOpen } from '../utils/format'
import type { Semester } from '../types/api'
export function useSemesters(
  mode: 'all' | 'ended' | 'current' = 'all',
  preferredId?: () => string | undefined,
) {
  const semesters = ref<Semester[]>([]),
    selectedId = ref(''),
    error = ref<unknown>(null),
    loading = ref(false),
    now = ref(Date.now())
  const choices = computed(() =>
    mode === 'ended'
      ? semesters.value.filter(ended)
      : mode === 'current'
        ? semesters.value.filter((s) => !ended(s))
        : semesters.value,
  )
  const current = computed(() => semesters.value.find((s) => s.id === selectedId.value))
  const open = computed(() => registrationOpen(current.value, now.value))
  let timer: ReturnType<typeof setInterval>
  async function load() {
    loading.value = true
    error.value = null
    try {
      semesters.value = (await api.semesters.list()).data.sort((a, b) =>
        b.startDate.localeCompare(a.startDate),
      )
      if (!choices.value.some((s) => s.id === selectedId.value))
        selectedId.value =
          (
            choices.value.find((s) => s.id === preferredId?.()) ||
            choices.value.find((s) => s.status === 'OPEN') ||
            choices.value[0]
          )?.id || ''
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
    }
  }
  onMounted(() => {
    void load()
    timer = setInterval(() => {
      now.value = Date.now()
    }, 1000)
  })
  onUnmounted(() => clearInterval(timer))
  return { semesters, choices, selectedId, current, open, error, loading, load }
}
