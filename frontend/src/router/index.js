import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import KnowledgeBase from '@/views/KnowledgeBase.vue'
import TableFill from '@/views/TableFill.vue'
import Settings from '@/views/Settings.vue'
import DocFormat from '@/views/DocFormat.vue'
import AuthView from '@/views/AuthView.vue'
import { useAuthStore } from '@/store/index.js'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/auth', name: 'Auth', component: AuthView, meta: { title: '登录', requiresAuth: false } },
  { path: '/chat', name: 'Chat', component: ChatView, meta: { title: '智能对话', requiresAuth: true } },
  { path: '/knowledge', name: 'Knowledge', component: KnowledgeBase, meta: { title: '知识库', requiresAuth: true } },
  { path: '/table-fill', name: 'TableFill', component: TableFill, meta: { title: '表格填写', requiresAuth: true } },
  { path: '/doc-format', name: 'DocFormat', component: DocFormat, meta: { title: '文档排版', requiresAuth: true } },
  { path: '/settings', name: 'Settings', component: Settings, meta: { title: '模型配置', requiresAuth: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  if (authStore.token && !authStore.user) {
    await authStore.restore()
  }
  const requiresAuth = to.meta.requiresAuth !== false
  if (requiresAuth && !authStore.isLoggedIn) {
    return { path: '/auth', query: { redirect: to.fullPath } }
  }
  if (to.path === '/auth' && authStore.isLoggedIn) {
    return { path: '/chat' }
  }
})

export default router
