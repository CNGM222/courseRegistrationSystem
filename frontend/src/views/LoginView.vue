<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { homeFor } from '../router'
import ErrorNotice from '../components/ErrorNotice.vue'
const auth = useAuthStore(),
  router = useRouter(),
  route = useRoute()
const form = reactive({ username: '', password: '' }),
  error = ref<unknown>(null)
async function submit() {
  if (!form.username.trim() || !form.password) {
    error.value = new Error('请输入账号和密码')
    return
  }
  error.value = null
  try {
    await auth.login(form.username.trim(), form.password)
    const redirect =
      typeof route.query.redirect === 'string' &&
      route.query.redirect.startsWith('/') &&
      !route.query.redirect.startsWith('//') &&
      !route.query.redirect.startsWith('/login')
        ? route.query.redirect
        : homeFor(auth.user!.role)
    await router.replace(redirect)
  } catch (e) {
    error.value = e
  } finally {
    form.password = ''
  }
}
</script>
<template>
  <div class="login-page">
    <section class="login-visual">
      <span class="login-kicker">CAMPUS · ACADEMIC SERVICES</span>
      <h1>新学期，<br />从这里开始。</h1>
      <p>查找适合你的课程，安排每一周的学习。课程、教学与教务服务，在一个地方有序展开。</p>
      <div class="login-points">
        <span class="login-point"><i class="point-dot" />课程目录与选课计划</span
        ><span class="login-point"><i class="point-dot" />个人课表与学期成绩</span
        ><span class="login-point"><i class="point-dot" />教学安排与教务管理</span>
      </div>
    </section>
    <section class="login-card-wrap">
      <div class="login-card surface">
        <span
          class="login-kicker"
          style="color: #168276"
          >欢迎回来</span
        >
        <h2>登录教务门户</h2>
        <p class="subtitle">使用学校分配的账号进入个人工作台。</p>
        <ErrorNotice :error="error" /><el-form
          :model="form"
          label-position="top"
          @submit.prevent="submit"
          ><el-form-item
            label="账号"
            required
            ><el-input
              v-model="form.username"
              size="large"
              maxlength="64"
              autocomplete="username"
              placeholder="请输入账号"
              :disabled="auth.loading" /></el-form-item
          ><el-form-item
            label="密码"
            required
            ><el-input
              v-model="form.password"
              size="large"
              type="password"
              autocomplete="current-password"
              placeholder="请输入密码"
              :disabled="auth.loading" /></el-form-item
          ><el-button
            native-type="submit"
            type="primary"
            size="large"
            :loading="auth.loading"
            style="width: 100%"
            >登录</el-button
          ></el-form
        >
        <div class="login-footer">账号由教务人员维护。如需帮助，请联系教务办公室。</div>
      </div>
    </section>
  </div>
</template>
