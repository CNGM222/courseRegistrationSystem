import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api } from '../services/api'
import { clearCsrf, HttpError } from '../services/http'
import { useRecoveryStore } from './recovery'
import type { CurrentUser } from '../types/api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const loading = ref(false)
  const initialized = ref(false)
  const restoreError = ref<unknown>(null)
  let restorePromise: Promise<void> | null = null
  const isAuthenticated = computed(() => Boolean(user.value))

  async function restore(force = false) {
    if (restorePromise) return restorePromise
    if (initialized.value && !force) return
    restoreError.value = null
    restorePromise = (async () => {
      try {
        user.value = (await api.auth.me()).data
      } catch (e) {
        user.value = null
        if (!(e instanceof HttpError && e.status === 401)) restoreError.value = e
      } finally {
        initialized.value = true
        restorePromise = null
      }
    })()
    return restorePromise
  }

  async function login(username: string, password: string) {
    loading.value = true
    restoreError.value = null
    try {
      clearCsrf()
      const next = (await api.auth.login(username, password)).data
      if (useRecoveryStore().draft?.owner !== next.accountId) useRecoveryStore().clear()
      clearCsrf()
      user.value = next
      initialized.value = true
    } finally {
      loading.value = false
    }
  }

  async function logout() {
    if (user.value) await api.auth.logout()
    user.value = null
    clearCsrf()
    useRecoveryStore().clear()
  }

  function setUser(value: CurrentUser | null) {
    user.value = value
    if (!value) clearCsrf()
  }
  return { user, loading, initialized, restoreError, isAuthenticated, restore, login, logout, setUser }
})
