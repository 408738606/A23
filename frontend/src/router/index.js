import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '@/views/ChatView.vue'
import KnowledgeBase from '@/views/KnowledgeBase.vue'
import TableFill from '@/views/TableFill.vue'
import Settings from '@/views/Settings.vue'
import DocFormat from '@/views/DocFormat.vue'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'Chat', component: ChatView, meta: { title: '智能对话' } },
  { path: '/knowledge', name: 'Knowledge', component: KnowledgeBase, meta: { title: '知识库' } },
  { path: '/table-fill', name: 'TableFill', component: TableFill, meta: { title: '表格填写' } },
  { path: '/doc-format', name: 'DocFormat', component: DocFormat, meta: { title: '文档排版' } },
  { path: '/settings', name: 'Settings', component: Settings, meta: { title: '模型配置' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
