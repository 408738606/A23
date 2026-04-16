import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi, llmApi, knowledgeApi } from '@/api/index.js'

export const useLlmStore = defineStore('llm', () => {
  const configs = ref([])
  const defaultConfig = ref(null)
  async function fetchConfigs() {
    const res = await llmApi.list()
    if (res.success) configs.value = res.data
  }
  async function fetchDefault() {
    const res = await llmApi.getDefault()
    if (res.success) defaultConfig.value = res.data
  }
  const activeConfigs = computed(() => configs.value.filter(c => c.isActive))
  return { configs, defaultConfig, activeConfigs, fetchConfigs, fetchDefault }
})

export const useKnowledgeStore = defineStore('knowledge', () => {
  const documents = ref([])
  const selectedIds = ref([])
  async function fetchDocs(params = {}) {
    const res = await knowledgeApi.list(params)
    if (res.success) documents.value = res.data
  }
  function toggleSelect(id) {
    const idx = selectedIds.value.indexOf(id)
    if (idx >= 0) selectedIds.value.splice(idx, 1)
    else selectedIds.value.push(id)
  }
  function selectAll() { selectedIds.value = documents.value.map(d => d.id) }
  function clearSelection() { selectedIds.value = [] }
  return { documents, selectedIds, fetchDocs, toggleSelect, selectAll, clearSelection }
})

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref(localStorage.getItem('df-theme') || 'dark')
  const language = ref(localStorage.getItem('df-lang') || 'zh')

  function setTheme(t) {
    theme.value = t
    localStorage.setItem('df-theme', t)
    document.documentElement.setAttribute('data-theme', t)
  }

  function setLanguage(l) {
    language.value = l
    localStorage.setItem('df-lang', l)
  }

  function init() {
    document.documentElement.setAttribute('data-theme', theme.value)
  }

  const t = computed(() => {
    const zh = {
      chat: '智能对话', knowledge: '知识库', tableFill: '表格填写', docFormat: '文档排版', settings: '模型配置',
      generalSettings: '通用设置', theme: '界面主题', language: '界面语言',
      newSession: '新会话', clearHistory: '清空', send: '发送',
      darkTheme: '深色', lightTheme: '浅色', blueTheme: '深海蓝', greenTheme: '丛林绿',
      chinese: '中文', english: 'English',
      sessionHistory: '历史会话', noHistory: '暂无历史会话',
      database: '数据库', learningLib: '学习库',
      localModel: '本地模型', apiCall: 'API调用',
      subDatabase: '子数据库', createSubDb: '新建子数据库',
      cancel: '取消', confirm: '确认', save: '保存', delete: '删除',
      downloadXlsx: '下载 Excel', copyContent: '复制', downloadTxt: '下载TXT', downloadMd: '下载MD',
      modelConfig: '模型类型', ollama: 'Ollama本地', llamaCpp: 'llama.cpp', custom: '自定义本地',
    }
    const en = {
      chat: 'Chat', knowledge: 'Knowledge Base', tableFill: 'Table Fill', docFormat: 'Doc Format', settings: 'Model Config',
      generalSettings: 'Settings', theme: 'Theme', language: 'Language',
      newSession: 'New', clearHistory: 'Clear', send: 'Send',
      darkTheme: 'Dark', lightTheme: 'Light', blueTheme: 'Ocean Blue', greenTheme: 'Forest Green',
      chinese: '中文', english: 'English',
      sessionHistory: 'History', noHistory: 'No history yet',
      database: 'Database', learningLib: 'Learning Lib',
      localModel: 'Local Model', apiCall: 'API Call',
      subDatabase: 'Sub-Database', createSubDb: 'New Sub-DB',
      cancel: 'Cancel', confirm: 'OK', save: 'Save', delete: 'Delete',
      downloadXlsx: 'Download Excel', copyContent: 'Copy', downloadTxt: 'Download TXT', downloadMd: 'Download MD',
      modelConfig: 'Model Type', ollama: 'Ollama Local', llamaCpp: 'llama.cpp', custom: 'Custom Local',
    }
    return language.value === 'zh' ? zh : en
  })

  return { theme, language, setTheme, setLanguage, init, t }
})

export const useAuthStore = defineStore('auth', () => {
  const user = ref(null)
  const token = ref(localStorage.getItem('df-auth-token') || '')

  const isLoggedIn = computed(() => !!token.value && !!user.value)

  function persistToken(value) {
    token.value = value || ''
    if (value) localStorage.setItem('df-auth-token', value)
    else localStorage.removeItem('df-auth-token')
  }

  async function restore() {
    if (!token.value) return false
    try {
      const res = await authApi.me()
      if (res.success) {
        user.value = res.data
        return true
      }
    } catch (e) {
      // ignore invalid token
    }
    persistToken('')
    user.value = null
    return false
  }

  async function login(payload) {
    const res = await authApi.login(payload)
    if (res.success) {
      persistToken(res.token)
      user.value = res.data
    }
    return res
  }

  async function register(payload) {
    const res = await authApi.register(payload)
    if (res.success) {
      persistToken(res.token)
      user.value = res.data
    }
    return res
  }

  async function logout() {
    try { await authApi.logout() } catch (e) {}
    persistToken('')
    user.value = null
  }

  async function updateProfile(payload) {
    const res = await authApi.updateProfile(payload)
    if (res.success) user.value = res.data
    return res
  }

  return { user, token, isLoggedIn, restore, login, register, logout, updateProfile }
})
