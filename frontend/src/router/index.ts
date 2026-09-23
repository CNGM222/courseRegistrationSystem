import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import type { Role } from '../types/api'
import AppShell from '../layouts/AppShell.vue'
export const homeFor = (role: Role) =>
  ({
    STUDENT: '/student/registration',
    PROFESSOR: '/professor/teaching',
    REGISTRAR: '/registrar/registration',
  })[role]
const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', name: 'home', redirect: '/home' },
        { path: 'home', component: () => import('../views/NotFoundView.vue') },
        {
          path: 'catalog',
          component: () => import('../views/CatalogView.vue'),
          meta: { title: '课程目录' },
        },
        {
          path: 'student/registration',
          component: () => import('../views/student/StudentRegistrationView.vue'),
          meta: { roles: ['STUDENT'], title: '选课中心' },
        },
        {
          path: 'student/schedule',
          component: () => import('../views/student/StudentScheduleView.vue'),
          meta: { roles: ['STUDENT'], title: '当前课表' },
        },
        {
          path: 'student/history',
          component: () => import('../views/student/StudentHistoryView.vue'),
          meta: { roles: ['STUDENT'], title: '选课历史' },
        },
        {
          path: 'student/report-card',
          component: () => import('../views/student/StudentReportCardView.vue'),
          meta: { roles: ['STUDENT'], title: '成绩单' },
        },
        {
          path: 'professor/teaching',
          component: () => import('../views/professor/ProfessorTeachingView.vue'),
          meta: { roles: ['PROFESSOR'], title: '授课选择' },
        },
        {
          path: 'professor/classes',
          component: () => import('../views/professor/ProfessorClassesView.vue'),
          meta: { roles: ['PROFESSOR'], title: '班级与成绩' },
        },
        {
          path: 'professor/offerings/:offeringId/roster',
          component: () => import('../views/professor/ProfessorRosterView.vue'),
          meta: { roles: ['PROFESSOR'], title: '学生名单与成绩' },
        },
        {
          path: 'registrar/registration',
          component: () => import('../views/registrar/RegistrarRegistrationView.vue'),
          meta: { roles: ['REGISTRAR'], title: '注册管理' },
        },
        {
          path: 'registrar/students',
          component: () => import('../views/registrar/PeopleView.vue'),
          props: { kind: 'students' },
          meta: { roles: ['REGISTRAR'], title: '学生信息' },
        },
        {
          path: 'registrar/professors',
          component: () => import('../views/registrar/PeopleView.vue'),
          props: { kind: 'professors' },
          meta: { roles: ['REGISTRAR'], title: '教师信息' },
        },
        {
          path: 'forbidden',
          component: () => import('../views/NotFoundView.vue'),
          props: { forbidden: true },
          meta: { title: '无权访问' },
        },
        {
          path: ':pathMatch(.*)*',
          component: () => import('../views/NotFoundView.vue'),
          meta: { title: '页面不存在' },
        },
      ],
    },
  ],
})
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.restore()
  if (to.meta.public) return auth.user ? homeFor(auth.user.role) : true
  if (!auth.user) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.path === '/home') return homeFor(auth.user.role)
  const roles = to.meta.roles as string[] | undefined
  if (roles && !roles.includes(auth.user.role)) return '/forbidden'
  document.title = `${to.meta.title || '教务门户'} · 高校课程注册系统`
  return true
})
export default router
