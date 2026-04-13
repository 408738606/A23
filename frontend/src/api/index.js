import axios from 'axios'
import { ElMessage } from 'element-plus'

const api = axios.create({ baseURL: '/api', timeout: 600000 })

api.interceptors.response.use(
  res => res.data,
  err => {
    const msg = err.response?.data?.message || err.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default api

export const knowledgeApi = {
  upload: (file, category = '默认', libraryType = 'database', subDatabase = null) => {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('category', category)
    fd.append('libraryType', libraryType)
    if (subDatabase) fd.append('subDatabase', subDatabase)
    return api.post('/knowledge/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  },
  uploadBatch: (files, category = '默认', libraryType = 'database', subDatabase = null) => {
    const fd = new FormData()
    files.forEach(f => fd.append('files', f))
    fd.append('category', category)
    fd.append('libraryType', libraryType)
    if (subDatabase) fd.append('subDatabase', subDatabase)
    return api.post('/knowledge/upload/batch', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  },
  list: (params = {}) => api.get('/knowledge/list', { params }),
  getById: (id) => api.get(`/knowledge/${id}`),
  getText: (id) => api.get(`/knowledge/${id}/text`),
  delete: (id) => api.delete(`/knowledge/${id}`),
  getCategories: () => api.get('/knowledge/categories'),
  getSubDatabases: () => api.get('/knowledge/sub-databases'),
}

export const llmApi = {
  list: () => api.get('/llm-config/list'),
  getDefault: () => api.get('/llm-config/default'),
  save: (data) => api.post('/llm-config/save', data),
  update: (id, data) => api.put(`/llm-config/${id}`, data),
  delete: (id) => api.delete(`/llm-config/${id}`),
  setDefault: (id) => api.post(`/llm-config/${id}/set-default`),
  test: (data) => api.post('/llm-config/test', data),
}

export const tableFillApi = {
  fill: (template, sourceDocIds, llmConfigId, sessionId) => {
    const fd = new FormData()
    fd.append('template', template)
    sourceDocIds.forEach(id => fd.append('sourceDocIds', id))
    if (llmConfigId) fd.append('llmConfigId', llmConfigId)
    if (sessionId) fd.append('sessionId', sessionId)
    return api.post('/table-fill/fill', fd, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 600000 })
  },
  getOutputs: (sessionId) => api.get('/table-fill/outputs', { params: { sessionId } }),
  saveToKb: (id) => api.post(`/table-fill/outputs/${id}/save-to-kb`),
  deleteOutput: (id) => api.delete(`/table-fill/outputs/${id}`),
}

export const chatApi = {
  send: (data) => api.post('/chat/send', data),
  getHistory: (sessionId) => api.get(`/chat/history/${sessionId}`),
  clearHistory: (sessionId) => api.delete(`/chat/history/${sessionId}`),
  getSessions: () => api.get('/chat/sessions'),
}

export const fileApi = {
  downloadOutput: (id) => `/api/file/download/output/${id}`,
  downloadKnowledge: (id) => `/api/file/download/knowledge/${id}`,
}
