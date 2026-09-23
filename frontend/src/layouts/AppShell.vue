<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Reading,
  Calendar,
  Document,
  School,
  List,
  User,
  Setting,
  SwitchButton,
  Menu,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { label, errorMessage } from '../utils/format'
const route = useRoute(),
  router = useRouter(),
  auth = useAuthStore(),
  drawer = ref(false)
const links = computed(() => {
  const common = [{ label: '课程目录', to: '/catalog', icon: Reading }]
  if (auth.user?.role === 'STUDENT')
    return [
      { label: '选课中心', to: '/student/registration', icon: School },
      { label: '当前课表', to: '/student/schedule', icon: Calendar },
      { label: '选课历史', to: '/student/history', icon: List },
      { label: '成绩单', to: '/student/report-card', icon: Document },
      ...common,
    ]
  if (auth.user?.role === 'PROFESSOR')
    return [
      { label: '授课选择', to: '/professor/teaching', icon: School },
      { label: '班级与成绩', to: '/professor/classes', icon: List },
      ...common,
    ]
  return [
    { label: '注册管理', to: '/registrar/registration', icon: Setting },
    { label: '学生信息', to: '/registrar/students', icon: User },
    { label: '教师信息', to: '/registrar/professors', icon: User },
    ...common,
  ]
})
async function logout() {
  try {
    await ElMessageBox.confirm('确认退出？尚未保存的修改将被清除。', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await auth.logout()
    await router.replace('/login')
  } catch (e) {
    ElMessage.error(errorMessage(e))
  }
}
</script>
<template>
  <div class="app-shell">
    <aside class="side-nav">
      <router-link
        to="/home"
        class="brand"
        ><span class="brand-mark">课</span
        ><span
          ><div class="brand-title">高校课程注册系统</div>
          <div class="brand-subtitle">COURSE REGISTRATION</div></span
        ></router-link
      >
      <nav aria-label="主导航">
        <div class="nav-section">{{ label(auth.user?.role) }}工作台</div>
        <router-link
          v-for="item in links"
          :key="item.to"
          :to="item.to"
          class="nav-link"
          ><el-icon><component :is="item.icon" /></el-icon>{{ item.label }}</router-link
        >
      </nav>
      <div class="nav-spacer" />
      <div class="user-mini">
        <span class="avatar">{{ auth.user?.displayName?.slice(0, 1) }}</span
        ><span
          ><div class="user-name">{{ auth.user?.displayName }}</div>
          <div class="user-role">{{ label(auth.user?.role) }}</div></span
        ><el-button
          text
          circle
          aria-label="退出登录"
          @click="logout"
          ><el-icon color="#d8eeee"><SwitchButton /></el-icon
        ></el-button>
      </div>
    </aside>
    <main class="main-panel">
      <header class="top-bar">
        <span class="breadcrumb"
          ><el-button
            class="mobile-menu"
            text
            aria-label="打开导航"
            @click="drawer = true"
            ><el-icon><Menu /></el-icon></el-button
          >教务门户 / <strong>{{ route.meta.title }}</strong></span
        ><el-tag
          type="success"
          effect="plain"
          >{{ label(auth.user?.role) }}</el-tag
        >
      </header>
      <section class="page-content"><router-view :key="route.path" /></section>
    </main>
    <el-drawer
      v-model="drawer"
      title="页面导航"
      direction="ltr"
      size="280px"
      ><nav class="mobile-nav">
        <router-link
          v-for="item in links"
          :key="item.to"
          :to="item.to"
          class="nav-link"
          @click="drawer = false"
          ><el-icon><component :is="item.icon" /></el-icon>{{ item.label }}</router-link
        >
      </nav></el-drawer
    >
  </div>
</template>
