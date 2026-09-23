import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Offering } from '../types/api'

export const useRecoveryStore = defineStore('recovery', () => {
  const draft = ref<{
    owner: string
    semesterId: string
    primary: string[]
    alternate: string[]
    offerings: Offering[]
  } | null>(null)
  function clear() {
    draft.value = null
  }
  return { draft, clear }
})
