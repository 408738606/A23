<template>
  <div class="chat-layout">

    <!-- ① 历史会话侧边栏 -->
    <div class="history-sidebar">
      <div class="hs-header">
        <span>{{ t.sessionHistory }}</span>
        <el-button size="small" type="primary" @click="newSession" round>
          <el-icon><Plus /></el-icon> {{ t.newSession }}
        </el-button>
      </div>
      <div class="hs-list">
        <div v-if="sessions.length === 0" class="hs-empty">
          <el-icon><ChatDotRound /></el-icon>
          <span>{{ t.noHistory }}</span>
        </div>
        <div
          v-for="s in sessions" :key="s.id"
          class="hs-item" :class="{ active: s.id === sessionId }"
          @click="loadSession(s.id)"
        >
          <div class="hs-title">{{ s.title }}</div>
          <div class="hs-meta">
            <span class="hs-time">{{ s.time }}</span>
            <span class="hs-count">{{ s.count }}条</span>
          </div>
          <el-button
            class="hs-del" size="small" text type="danger"
            @click.stop="deleteSession(s.id)"
          ><el-icon><Delete /></el-icon></el-button>
        </div>
      </div>
    </div>

    <!-- ② 知识库文档选择 -->
    <div class="doc-sidebar">
      <div class="sidebar-header">
        <span>📚 {{ t.knowledge }}</span>
        <div class="sidebar-actions">
          <el-button size="small" text @click="kbStore.selectAll()">全选</el-button>
          <el-button size="small" text @click="kbStore.clearSelection()">清空</el-button>
        </div>
      </div>
      <div class="doc-search">
        <el-input v-model="docSearch" placeholder="搜索文档..." size="small" clearable>
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>
      <div class="doc-list">
        <div
          v-for="doc in filteredDocs" :key="doc.id"
          class="doc-item" :class="{ selected: kbStore.selectedIds.includes(doc.id) }"
          @click="kbStore.toggleSelect(doc.id)"
        >
          <span class="doc-icon">{{ getFileIcon(doc.fileType) }}</span>
          <div class="doc-info">
            <div class="doc-name" :title="doc.fileName">{{ doc.fileName }}</div>
            <div class="doc-meta2">
              <span class="doc-type">{{ doc.fileType?.toUpperCase() }}</span>
              <span v-if="doc.processed" class="doc-ready">✓</span>
              <span v-else class="doc-proc">⟳</span>
            </div>
          </div>
          <el-checkbox :model-value="kbStore.selectedIds.includes(doc.id)" @click.stop @change="kbStore.toggleSelect(doc.id)" />
        </div>
        <div v-if="filteredDocs.length === 0" class="doc-empty">
          <el-icon><FolderOpened /></el-icon>
          <router-link to="/knowledge">去上传文档</router-link>
        </div>
      </div>
      <div class="sidebar-footer" v-if="kbStore.selectedIds.length > 0">
        已关联 <strong>{{ kbStore.selectedIds.length }}</strong> 个文档
      </div>
    </div>

    <!-- ③ 主聊天区 -->
    <div class="chat-main">
      <div class="chat-header">
        <div class="header-left">
          <h2>{{ t.chat }}</h2>
          <span class="session-badge" v-if="sessionId">{{ sessionId.slice(0,8) }}...</span>
        </div>
        <div class="header-right">
          <el-select v-model="selectedConfigId" placeholder="选择模型" size="small" style="width:200px">
            <el-option
              v-for="cfg in llmStore.activeConfigs" :key="cfg.id"
              :label="`${cfg.configName} (${cfg.modelName})`" :value="cfg.id"
            />
          </el-select>
          <el-button size="small" text @click="clearHistory" :disabled="messages.length === 0">
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
      </div>

      <!-- Messages -->
      <div class="messages-area" ref="messagesAreaRef">
        <div v-if="messages.length === 0" class="empty-chat">
          <div class="empty-icon">⚡</div>
          <h3>DocFusion 智能文档助手</h3>
          <p>选择左侧知识库文档，开始智能对话</p>
          <div class="quick-prompts">
            <div class="quick-prompt" v-for="q in quickPrompts" :key="q" @click="sendQuick(q)">{{ q }}</div>
          </div>
        </div>

        <div v-for="(msg, idx) in messages" :key="idx" class="message-wrapper" :class="msg.role">
          <div class="message-avatar">
            <span v-if="msg.role === 'user'">👤</span>
            <span v-else>🤖</span>
          </div>
          <div class="message-content">
            <div class="message-bubble" :class="msg.role">
              <div v-if="msg.role === 'assistant'" class="md-content" v-html="renderMarkdown(msg.content)"></div>
              <div v-else class="user-text">{{ msg.content }}</div>
              <span v-if="msg.streaming" class="cursor-blink">▌</span>
            </div>

            <!-- Timestamp -->
            <div class="msg-timestamp">{{ formatTimestamp(msg.time) }}</div>

            <!-- Actions for assistant messages -->
            <div class="message-actions" v-if="msg.role === 'assistant' && !msg.streaming && msg.content">
              <el-button size="small" text @click="copyContent(msg.content)">
                <el-icon><CopyDocument /></el-icon> {{ t.copyContent }}
              </el-button>
              <el-button size="small" text @click="downloadAsTxt(msg.content, idx)">
                <el-icon><Download /></el-icon> TXT
              </el-button>
              <el-button size="small" text @click="downloadAsMd(msg.content, idx)">
                <el-icon><Download /></el-icon> MD
              </el-button>
              <el-button
                v-if="hasTable(msg.content)" size="small" text type="success"
                @click="downloadTableAsXlsx(msg.content, idx)"
              >
                <el-icon><Download /></el-icon> {{ t.downloadXlsx }}
              </el-button>
              <el-button v-if="msg.outputFileId" size="small" type="primary" text @click="downloadOutput(msg.outputFileId)">
                <el-icon><Download /></el-icon> 下载表格
              </el-button>
            </div>
          </div>
        </div>

        <div v-if="loading && !streaming" class="message-wrapper assistant">
          <div class="message-avatar"><span>🤖</span></div>
          <div class="message-content">
            <div class="message-bubble assistant">
              <span class="typing-dots"><span></span><span></span><span></span></span>
            </div>
          </div>
        </div>
      </div>

      <!-- Input -->
      <div class="input-area">
        <div class="input-hints" v-if="kbStore.selectedIds.length > 0">
          <el-tag size="small" type="info">已关联 {{ kbStore.selectedIds.length }} 个知识库文档</el-tag>
        </div>
        <div class="input-row">
          <el-input
            v-model="inputText" type="textarea" :rows="3"
            :placeholder="`输入问题或指令... (Ctrl+Enter 发送)`"
            resize="none" @keydown.ctrl.enter="sendMessage" :disabled="loading"
          />
          <el-button
            type="primary" :loading="loading"
            :disabled="!inputText.trim() || loading"
            @click="sendMessage" class="send-btn"
          >
            <el-icon v-if="!loading"><Promotion /></el-icon>
            <span>{{ t.send }}</span>
          </el-button>
        </div>
        <div class="input-footer">
          <span class="hint">Ctrl+Enter 发送 · 支持 Markdown</span>
          <span v-if="streaming" class="streaming-tip">
            <span class="dot-pulse"></span> 正在生成...
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import { useLlmStore, useKnowledgeStore, useSettingsStore } from '@/store/index.js'
import { storeToRefs } from 'pinia'
import { chatApi, fileApi } from '@/api/index.js'

const llmStore = useLlmStore()
const kbStore = useKnowledgeStore()
const settingsStore = useSettingsStore()
const { selectedIds } = storeToRefs(kbStore)
const { t } = storeToRefs(settingsStore)

const messages = ref([])
const inputText = ref('')
const loading = ref(false)
const streaming = ref(false)
const sessionId = ref('')
const selectedConfigId = ref(null)
const messagesAreaRef = ref(null)
const docSearch = ref('')

// Session history list
const sessions = ref([])

const quickPrompts = [
  '📋 提取文档中的关键信息并整理成表格',
  '🔍 分析并对比多个文档的主要内容',
  '📊 从文档中提取所有数字数据',
  '✏️ 根据知识库内容，生成摘要报告',
]

const filteredDocs = computed(() => {
  const docs = kbStore.documents
  if (!docSearch.value) return docs
  return docs.filter(d => d.fileName.toLowerCase().includes(docSearch.value.toLowerCase()))
})

const getFileIcon = (type) => {
  const m = { docx:'📄', doc:'📄', xlsx:'📊', xls:'📊', md:'📝', txt:'📃' }
  return m[type?.toLowerCase()] || '📎'
}

const renderMarkdown = (text) => {
  if (!text) return ''
  try { return marked.parse(text) } catch { return text }
}

const formatTimestamp = (t) => {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  const isToday = d.toDateString() === now.toDateString()
  if (isToday) return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

const scrollToBottom = async () => {
  await nextTick()
  if (messagesAreaRef.value) messagesAreaRef.value.scrollTop = messagesAreaRef.value.scrollHeight
}

// Detect if message contains a markdown table
const hasTable = (text) => {
  if (!text) return false
  return /\|.+\|.+\|/.test(text) && /\|[-:\s]+\|/.test(text)
}

// Parse markdown table → array of objects
function parseMarkdownTable(text) {
  const lines = text.split('\n')
  const tableLines = []
  let inTable = false
  for (const line of lines) {
    if (line.trim().startsWith('|') && line.trim().endsWith('|')) {
      inTable = true
      tableLines.push(line)
    } else if (inTable) break
  }
  if (tableLines.length < 2) return []
  const headers = tableLines[0].split('|').map(s => s.trim()).filter(Boolean)
  const rows = []
  for (let i = 2; i < tableLines.length; i++) {
    const cells = tableLines[i].split('|').map(s => s.trim()).filter(Boolean)
    if (cells.length === 0) continue
    const row = {}
    headers.forEach((h, idx) => { row[h] = cells[idx] || '' })
    rows.push(row)
  }
  return { headers, rows }
}

// Download table content as xlsx using SheetJS
async function downloadTableAsXlsx(content, idx) {
  try {
    const XLSX = await import('https://cdn.sheetjs.com/xlsx-0.20.1/package/xlsx.mjs')
    const parsed = parseMarkdownTable(content)
    if (!parsed || !parsed.rows || parsed.rows.length === 0) {
      ElMessage.warning('未能从内容中解析到表格数据')
      return
    }
    const ws = XLSX.utils.json_to_sheet(parsed.rows, { header: parsed.headers })
    const wb = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(wb, ws, 'Sheet1')
    XLSX.writeFile(wb, `table_output_${idx + 1}.xlsx`)
    ElMessage.success('Excel 下载成功')
  } catch (e) {
    // Fallback: download as CSV
    const parsed = parseMarkdownTable(content)
    if (!parsed) return
    const csv = [parsed.headers.join(','), ...parsed.rows.map(r => parsed.headers.map(h => `"${(r[h]||'').replace(/"/g,'""')}"`).join(','))].join('\n')
    const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = `table_${idx+1}.csv`; a.click()
    URL.revokeObjectURL(url)
    ElMessage.info('已下载为 CSV 格式')
  }
}

function copyContent(text) {
  navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制'))
}

function downloadAsTxt(content, idx) {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = `output_${idx+1}.txt`; a.click()
  URL.revokeObjectURL(url)
}

function downloadAsMd(content, idx) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = `output_${idx+1}.md`; a.click()
  URL.revokeObjectURL(url)
}

function downloadOutput(fileId) {
  window.open(fileApi.downloadOutput(fileId), '_blank')
}

// Session management
function newSession() {
  if (messages.value.length > 0) saveCurrentSession()
  sessionId.value = crypto.randomUUID()
  messages.value = []
}

function saveCurrentSession() {
  if (!sessionId.value || messages.value.length === 0) return
  const userMsgs = messages.value.filter(m => m.role === 'user')
  const title = userMsgs[0]?.content?.slice(0, 20) || '新对话'
  const existing = sessions.value.findIndex(s => s.id === sessionId.value)
  const entry = {
    id: sessionId.value,
    title: title + (title.length >= 20 ? '...' : ''),
    time: formatTimestamp(new Date()),
    count: messages.value.length,
    messages: JSON.parse(JSON.stringify(messages.value)),
    docIds: [...selectedIds.value],
  }
  if (existing >= 0) sessions.value[existing] = entry
  else sessions.value.unshift(entry)

  // Persist to localStorage
  try {
    localStorage.setItem('df-sessions', JSON.stringify(sessions.value.slice(0, 50)))
  } catch (e) {}
}

function loadSession(id) {
  if (messages.value.length > 0) saveCurrentSession()
  const s = sessions.value.find(s => s.id === id)
  if (!s) return
  sessionId.value = id
  messages.value = s.messages || []
  if (s.docIds) kbStore.selectedIds = s.docIds
  scrollToBottom()
}

async function deleteSession(id) {
  sessions.value = sessions.value.filter(s => s.id !== id)
  if (sessionId.value === id) {
    sessionId.value = crypto.randomUUID()
    messages.value = []
  }
  try { localStorage.setItem('df-sessions', JSON.stringify(sessions.value)) } catch {}
}

function loadSessions() {
  try {
    const stored = localStorage.getItem('df-sessions')
    if (stored) sessions.value = JSON.parse(stored)
  } catch {}
}

async function clearHistory() {
  await ElMessageBox.confirm('确定清空当前会话？', '提示', { type: 'warning' })
  if (sessionId.value) await chatApi.clearHistory(sessionId.value)
  messages.value = []
  sessions.value = sessions.value.filter(s => s.id !== sessionId.value)
  try { localStorage.setItem('df-sessions', JSON.stringify(sessions.value)) } catch {}
}

async function sendQuick(q) {
  inputText.value = q
  await sendMessage()
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  if (!selectedConfigId.value && llmStore.activeConfigs.length > 0) {
    selectedConfigId.value = llmStore.activeConfigs[0].id
  }
  if (!selectedConfigId.value) {
    ElMessage.warning('请先在【模型配置】中添加并启用大模型')
    return
  }

  messages.value.push({ role: 'user', content: text, time: new Date() })
  inputText.value = ''
  loading.value = true
  streaming.value = false
  await scrollToBottom()

  const assistantIdx = messages.value.length
  messages.value.push({ role: 'assistant', content: '', streaming: true, time: new Date() })

  try {
    const params = new URLSearchParams({
      message: text,
      sessionId: sessionId.value || '',
      llmConfigId: selectedConfigId.value,
    })
    selectedIds.value.forEach(id => params.append('selectedDocIds', id))

    const evtSource = new EventSource(`/api/chat/stream?${params.toString()}`)
    streaming.value = true

    evtSource.addEventListener('sessionId', (e) => { if (!sessionId.value) sessionId.value = e.data })

    evtSource.addEventListener('token', (e) => {
      messages.value[assistantIdx].content += e.data
      scrollToBottom()
    })

    evtSource.addEventListener('done', () => {
      messages.value[assistantIdx].streaming = false
      messages.value[assistantIdx].time = new Date()
      streaming.value = false
      loading.value = false
      evtSource.close()
      saveCurrentSession()
      scrollToBottom()
    })

    evtSource.addEventListener('error', (e) => {
      const errMsg = e.data || '生成失败，请检查模型配置'
      if (!messages.value[assistantIdx].content) messages.value[assistantIdx].content = '❌ ' + errMsg
      messages.value[assistantIdx].streaming = false
      streaming.value = false; loading.value = false
      evtSource.close()
      if (errMsg && errMsg !== '[object Event]') ElMessage.error(errMsg)
    })

    evtSource.onerror = () => {
      if (loading.value) {
        messages.value[assistantIdx].streaming = false
        streaming.value = false; loading.value = false
        evtSource.close()
      }
    }
  } catch (err) {
    messages.value[assistantIdx].content = '❌ 请求失败: ' + err.message
    messages.value[assistantIdx].streaming = false
    streaming.value = false; loading.value = false
    await scrollToBottom()
  }
}

onMounted(async () => {
  loadSessions()
  await llmStore.fetchConfigs()
  await kbStore.fetchDocs()
  if (llmStore.activeConfigs.length > 0) {
    const def = llmStore.activeConfigs.find(c => c.isDefault)
    selectedConfigId.value = def?.id || llmStore.activeConfigs[0].id
  }
  sessionId.value = crypto.randomUUID()
})
</script>

<style scoped>
.chat-layout { display: flex; height: 100vh; overflow: hidden; }

/* History sidebar */
.history-sidebar {
  width: 200px; min-width: 200px;
  background: var(--df-surface);
  border-right: 1px solid var(--df-border);
  display: flex; flex-direction: column;
}

.hs-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 12px 10px;
  border-bottom: 1px solid var(--df-border);
  font-size: 12px; font-weight: 600; color: var(--df-text-muted);
}

.hs-list { flex: 1; overflow-y: auto; padding: 6px 8px; }

.hs-empty {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 30px 10px; color: var(--df-text-muted); font-size: 12px;
}
.hs-empty .el-icon { font-size: 28px; }

.hs-item {
  padding: 8px 10px; border-radius: 7px; cursor: pointer;
  transition: background 0.15s; margin-bottom: 3px;
  position: relative; border: 1px solid transparent;
}
.hs-item:hover { background: var(--df-surface2); }
.hs-item.active { background: rgba(91,138,240,0.12); border-color: rgba(91,138,240,0.25); }

.hs-title {
  font-size: 12px; color: var(--df-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  padding-right: 20px;
}
.hs-meta { display: flex; justify-content: space-between; margin-top: 3px; }
.hs-time { font-size: 10px; color: var(--df-text-muted); }
.hs-count { font-size: 10px; color: var(--df-text-muted); }

.hs-del {
  position: absolute; right: 4px; top: 50%;
  transform: translateY(-50%); opacity: 0;
  padding: 2px 4px !important;
}
.hs-item:hover .hs-del { opacity: 1; }

/* Doc sidebar */
.doc-sidebar {
  width: 220px; min-width: 220px;
  background: var(--df-surface);
  border-right: 1px solid var(--df-border);
  display: flex; flex-direction: column;
}

.sidebar-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 12px 8px;
  font-size: 12px; font-weight: 600; color: var(--df-text-muted);
  border-bottom: 1px solid var(--df-border);
}
.sidebar-actions { display: flex; gap: 2px; }

.doc-search { padding: 8px 10px; }
.doc-list { flex: 1; overflow-y: auto; padding: 4px 8px; }

.doc-item {
  display: flex; align-items: center; gap: 7px;
  padding: 7px 8px; border-radius: 7px; cursor: pointer;
  transition: background 0.15s; margin-bottom: 2px;
}
.doc-item:hover { background: var(--df-surface2); }
.doc-item.selected { background: rgba(91,138,240,0.12); }

.doc-icon { font-size: 14px; flex-shrink: 0; }
.doc-info { flex: 1; min-width: 0; }
.doc-name { font-size: 11px; color: var(--df-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.doc-meta2 { display: flex; gap: 5px; margin-top: 1px; }
.doc-type { font-size: 9px; background: var(--df-surface2); padding: 1px 4px; border-radius: 3px; color: var(--df-text-muted); }
.doc-ready { font-size: 10px; color: var(--df-success); }
.doc-proc { font-size: 10px; color: var(--df-warning); }

.doc-empty {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 30px 10px; color: var(--df-text-muted); font-size: 12px;
}
.doc-empty .el-icon { font-size: 24px; }
.doc-empty a { color: var(--df-primary); text-decoration: none; }

.sidebar-footer {
  padding: 8px 12px; font-size: 12px; color: var(--df-text-muted);
  border-top: 1px solid var(--df-border);
}

/* Chat main */
.chat-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; }

.chat-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 18px;
  background: var(--df-surface); border-bottom: 1px solid var(--df-border);
}
.header-left { display: flex; align-items: center; gap: 10px; }
.header-left h2 { font-size: 16px; font-weight: 600; }
.session-badge {
  font-size: 10px; color: var(--df-text-muted); font-family: monospace;
  background: var(--df-surface2); padding: 2px 7px; border-radius: 4px;
}
.header-right { display: flex; align-items: center; gap: 8px; }

/* Messages */
.messages-area {
  flex: 1; overflow-y: auto; padding: 16px 20px;
  display: flex; flex-direction: column; gap: 16px;
}

.empty-chat {
  flex: 1; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 10px;
  color: var(--df-text-muted); text-align: center; padding: 40px;
}
.empty-icon { font-size: 44px; margin-bottom: 4px; }
.empty-chat h3 { font-size: 18px; color: var(--df-text); }

.quick-prompts { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 12px; max-width: 560px; }
.quick-prompt {
  background: var(--df-surface); border: 1px solid var(--df-border);
  padding: 8px 14px; border-radius: 18px; font-size: 12px; cursor: pointer;
  transition: all 0.18s; color: var(--df-text-muted);
}
.quick-prompt:hover { border-color: var(--df-primary); color: var(--df-primary); background: rgba(91,138,240,0.07); }

.message-wrapper { display: flex; gap: 10px; align-items: flex-start; max-width: 860px; }
.message-wrapper.user { flex-direction: row-reverse; align-self: flex-end; }

.message-avatar {
  width: 32px; height: 32px; border-radius: 50%;
  background: var(--df-surface); display: flex; align-items: center;
  justify-content: center; font-size: 14px; flex-shrink: 0;
  border: 1px solid var(--df-border);
}

.message-content { flex: 1; min-width: 0; }

.message-bubble { padding: 10px 14px; border-radius: 12px; line-height: 1.65; word-break: break-word; }
.message-bubble.user { background: var(--df-user-bubble); color: var(--df-user-text); border-bottom-right-radius: 4px; }
.message-bubble.assistant { background: var(--df-surface); border: 1px solid var(--df-border); border-bottom-left-radius: 4px; }

.user-text { white-space: pre-wrap; }

.cursor-blink { animation: blink 1s step-end infinite; color: var(--df-primary); }
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

.typing-dots { display: flex; gap: 4px; padding: 2px 0; }
.typing-dots span { width: 7px; height: 7px; border-radius: 50%; background: var(--df-text-muted); animation: typing 1.2s infinite; }
.typing-dots span:nth-child(2) { animation-delay: 0.2s; }
.typing-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes typing { 0%, 80%, 100% { transform: scale(0.8); opacity: 0.4; } 40% { transform: scale(1); opacity: 1; } }

.msg-timestamp { font-size: 10px; color: var(--df-text-muted); margin-top: 4px; padding: 0 2px; }
.message-wrapper.user .msg-timestamp { text-align: right; }

.message-actions { display: flex; flex-wrap: wrap; gap: 2px; margin-top: 4px; }

/* Input */
.input-area { padding: 12px 18px 14px; background: var(--df-surface); border-top: 1px solid var(--df-border); }
.input-hints { margin-bottom: 6px; }
.input-row { display: flex; gap: 10px; align-items: flex-end; }
.input-row .el-textarea { flex: 1; }
.send-btn { height: 74px !important; width: 72px; flex-direction: column; gap: 3px; flex-shrink: 0; }
.input-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 5px; }
.hint { font-size: 11px; color: var(--df-text-muted); }
.streaming-tip { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--df-accent); }
.dot-pulse { width: 7px; height: 7px; border-radius: 50%; background: var(--df-accent); animation: pulse 1s infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; transform: scale(1); } 50% { opacity: 0.5; transform: scale(0.8); } }
</style>
