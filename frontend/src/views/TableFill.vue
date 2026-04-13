<template>
  <div class="page-layout">
    <div class="page-header">
      <div>
        <h2>📋 智能表格填写</h2>
        <p class="subtitle">上传表格模板，AI将从知识库文档中自动提取信息并填写，AI分批提取，支持100+条数据，大数据集耗时较长请耐心等待</p>
      </div>
    </div>

    <div class="fill-content">
      <!-- Left: Config Panel -->
      <div class="config-panel">
        <div class="panel-section">
          <div class="section-title">① 上传表格模板</div>
          <el-upload
            drag
            :auto-upload="false"
            :limit="1"
            :file-list="templateFileList"
            :on-change="onTemplateChange"
            :on-remove="onTemplateRemove"
            accept=".docx,.doc,.xlsx,.xls"
            class="template-upload"
          >
            <el-icon class="el-icon--upload" style="font-size: 28px; color: var(--df-text-muted)"><UploadFilled /></el-icon>
            <div class="el-upload__text" style="margin-top: 8px; font-size: 13px;">
              拖拽模板文件或<em>点击选择</em>
            </div>
            <template #tip>
              <div class="upload-tip">支持 Word (.docx) 和 Excel (.xlsx)</div>
            </template>
          </el-upload>

          <div v-if="templateFile" class="template-info">
            <span>{{ getFileIcon(templateFile.name) }} {{ templateFile.name }}</span>
            <span class="file-size">{{ formatSize(templateFile.size) }}</span>
          </div>
        </div>

        <div class="panel-section">
          <div class="section-title">② 选择数据来源文档</div>
          <div class="source-hint">从知识库中选择包含填写数据的文档</div>

          <div class="doc-select-all">
            <el-checkbox v-model="allSelected" @change="toggleAllDocs" :indeterminate="indeterminate">
              全选（{{ selectedSourceIds.length }}/{{ kbDocs.length }}）
            </el-checkbox>
            <el-button size="small" text @click="refreshKbDocs">
              <el-icon><Refresh /></el-icon>
            </el-button>
          </div>

          <div class="doc-select-list">
            <div v-if="kbDocs.length === 0" class="no-docs">
              <el-icon><FolderOpened /></el-icon>
              <span>知识库为空</span>
              <router-link to="/knowledge">前往上传</router-link>
            </div>
            <el-checkbox-group v-model="selectedSourceIds">
              <div v-for="doc in kbDocs" :key="doc.id" class="doc-check-item">
                <el-checkbox :label="doc.id">
                  <div class="doc-label">
                    <span>{{ getFileIcon(doc.fileType) }}</span>
                    <span class="doc-name-text" :title="doc.fileName">{{ doc.fileName }}</span>
                    <el-tag size="small" :type="doc.processed ? 'success' : 'warning'" style="margin-left: auto; flex-shrink:0">
                      {{ doc.processed ? '就绪' : '处理中' }}
                    </el-tag>
                  </div>
                </el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
        </div>

        <div class="panel-section">
          <div class="section-title">③ 选择模型</div>
          <el-select v-model="selectedConfigId" placeholder="选择大模型" style="width: 100%">
            <el-option
              v-for="cfg in llmConfigs"
              :key="cfg.id"
              :label="`${cfg.configName} (${cfg.modelName})`"
              :value="cfg.id"
            />
          </el-select>
        </div>

        <el-button
          type="primary"
          :loading="filling"
          :disabled="!templateFile || selectedSourceIds.length === 0 || !selectedConfigId"
          @click="startFill"
          style="width: 100%; height: 44px; font-size: 15px; margin-top: 8px"
        >
          <el-icon v-if="!filling"><MagicStick /></el-icon>
          {{ filling ? 'AI 正在填写中...' : '开始智能填写' }}
        </el-button>

        <div v-if="filling" class="progress-area">
          <el-progress :percentage="fillProgress" :stroke-width="6" :show-text="false" />
          <div class="progress-tips">{{ progressTip }}</div>
          <div class="elapsed" v-if="elapsedTime > 0">已用时 {{ elapsedTime }}s（大数据集可能需要数分钟）</div>
        </div>
      </div>

      <!-- Right: Results Panel -->
      <div class="results-panel">
        <div class="results-header">
          <span>📂 填写结果</span>
          <el-button size="small" text @click="refreshOutputs">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>

        <div v-if="outputs.length === 0" class="results-empty">
          <el-icon style="font-size: 48px; color: var(--df-text-muted)"><Document /></el-icon>
          <p>尚无填写结果</p>
          <p style="font-size: 12px; color: var(--df-text-muted)">完成填写后结果将显示在此处</p>
        </div>

        <div v-else class="output-list">
          <div v-for="output in outputs" :key="output.id" class="output-card">
            <div class="output-header">
              <span class="output-icon">{{ getFileIcon(output.fileType) }}</span>
              <div class="output-info">
                <div class="output-name" :title="output.fileName">{{ output.fileName }}</div>
                <div class="output-meta">
                  {{ formatSize(output.fileSize) }} · {{ formatDate(output.createdAt) }}
                </div>
              </div>
              <el-tag v-if="output.savedToKnowledgeBase" size="small" type="success">已入库</el-tag>
            </div>

            <div class="output-desc" v-if="output.description">{{ output.description }}</div>

            <div class="output-actions">
              <el-button type="primary" size="small" @click="downloadOutput(output)">
                <el-icon><Download /></el-icon> 下载文件
              </el-button>
              <el-button size="small" @click="saveToKb(output)" :disabled="output.savedToKnowledgeBase">
                <el-icon><FolderAdd /></el-icon> 存入知识库
              </el-button>
              <el-button size="small" text type="danger" @click="deleteOutput(output)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi, tableFillApi, llmApi, fileApi } from '@/api/index.js'

const kbDocs = ref([])
const selectedSourceIds = ref([])
const templateFile = ref(null)
const templateFileList = ref([])
const llmConfigs = ref([])
const selectedConfigId = ref(null)
const outputs = ref([])
const filling = ref(false)
const fillProgress = ref(0)
const progressTip = ref('')
const elapsedTime = ref(0)
let progressTimer = null

const allSelected = computed({
  get: () => kbDocs.value.length > 0 && selectedSourceIds.value.length === kbDocs.value.length,
  set: () => {}
})

const indeterminate = computed(() =>
  selectedSourceIds.value.length > 0 && selectedSourceIds.value.length < kbDocs.value.length
)

function toggleAllDocs(val) {
  selectedSourceIds.value = val ? kbDocs.value.map(d => d.id) : []
}

const getFileIcon = (type) => {
  const m = { docx: '📄', doc: '📄', xlsx: '📊', xls: '📊', md: '📝', txt: '📃' }
  return m[type?.toLowerCase()] || '📎'
}

const formatSize = (bytes) => {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

const formatDate = (d) => {
  if (!d) return '-'
  return new Date(d).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function onTemplateChange(file) {
  templateFile.value = file.raw
  templateFileList.value = [file]
}

function onTemplateRemove() {
  templateFile.value = null
  templateFileList.value = []
}

async function refreshKbDocs() {
  const res = await knowledgeApi.list()
  if (res.success) kbDocs.value = res.data
}

async function refreshOutputs() {
  const res = await tableFillApi.getOutputs()
  if (res.success) outputs.value = res.data
}

const progressTips = [
  '① 正在解析模板列头结构...',
  '② 读取知识库文档全文...',
  '③ 将文档分块，准备批量提取...',
  '④ AI正在处理第1批数据...',
  '⑤ AI正在处理数据，大文档多次调用请稍候...',
  '⑥ AI正在提取并去重所有行...',
  '⑦ 正在将提取结果写入表格...',
  '⑧ 生成最终文件中...',
]

async function startFill() {
  if (!templateFile.value || selectedSourceIds.value.length === 0) return

  filling.value = true
  fillProgress.value = 0
  elapsedTime.value = 0

  let tipIdx = 0
  progressTip.value = progressTips[0]

  progressTimer = setInterval(() => {
    elapsedTime.value++
    fillProgress.value = Math.min(fillProgress.value + 100 / 300, 94)
    tipIdx = Math.floor((fillProgress.value / 100) * progressTips.length)
    progressTip.value = progressTips[Math.min(tipIdx, progressTips.length - 1)]
  }, 1000)

  try {
    const res = await tableFillApi.fill(
      templateFile.value,
      selectedSourceIds.value,
      selectedConfigId.value,
      null
    )
    clearInterval(progressTimer)
    fillProgress.value = 100
    progressTip.value = '✅ 填写完成！'

    if (res.success) {
      ElMessage.success(`表格填写完成，耗时 ${Math.round(res.elapsedMs / 1000)} 秒`)
      await refreshOutputs()
    }
  } catch (e) {
    clearInterval(progressTimer)
    ElMessage.error('填写失败: ' + (e.response?.data?.message || e.message))
  } finally {
    filling.value = false
    progressTimer = null
  }
}

function downloadOutput(output) {
  window.open(fileApi.downloadOutput(output.id), '_blank')
}

async function saveToKb(output) {
  const res = await tableFillApi.saveToKb(output.id)
  if (res.success) {
    ElMessage.success('已保存到知识库')
    output.savedToKnowledgeBase = true
    await refreshKbDocs()
  }
}

async function deleteOutput(output) {
  await ElMessageBox.confirm(`确定删除「${output.fileName}」？`, '确认', { type: 'warning' })
  await tableFillApi.deleteOutput(output.id)
  ElMessage.success('已删除')
  await refreshOutputs()
}

onMounted(async () => {
  await refreshKbDocs()
  await refreshOutputs()
  const res = await llmApi.list()
  if (res.success) {
    llmConfigs.value = res.data.filter(c => c.isActive)
    const def = llmConfigs.value.find(c => c.isDefault)
    if (def) selectedConfigId.value = def.id
    else if (llmConfigs.value.length > 0) selectedConfigId.value = llmConfigs.value[0].id
  }
})
</script>

<style scoped>
.page-layout { height: 100vh; display: flex; flex-direction: column; overflow: hidden; }

.page-header {
  padding: 20px 24px 16px;
  background: var(--df-surface);
  border-bottom: 1px solid var(--df-border);
}

.page-header h2 { font-size: 18px; font-weight: 600; margin-bottom: 4px; }
.subtitle { font-size: 13px; color: var(--df-text-muted); }

.fill-content {
  flex: 1;
  display: flex;
  gap: 0;
  overflow: hidden;
}

.config-panel {
  width: 360px;
  min-width: 360px;
  background: var(--df-surface);
  border-right: 1px solid var(--df-border);
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.panel-section { display: flex; flex-direction: column; gap: 10px; }

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--df-text);
  padding-bottom: 6px;
  border-bottom: 1px solid var(--df-border);
}

.template-upload { width: 100%; }

.upload-tip { font-size: 11px; color: var(--df-text-muted); text-align: center; margin-top: 4px; }

.template-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--df-surface2);
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--df-text);
}

.file-size { font-size: 12px; color: var(--df-text-muted); }

.source-hint { font-size: 12px; color: var(--df-text-muted); }

.doc-select-all {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.doc-select-list {
  max-height: 240px;
  overflow-y: auto;
  background: var(--df-surface2);
  border-radius: 8px;
  padding: 8px;
}

.no-docs {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px;
  color: var(--df-text-muted);
  font-size: 13px;
}

.no-docs a { color: var(--df-primary); text-decoration: none; }

.doc-check-item { padding: 6px 4px; }

.doc-check-item .el-checkbox { width: 100%; }

.doc-label {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}

.doc-name-text {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.progress-area {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background: var(--df-surface2);
  border-radius: 8px;
}

.progress-tips { font-size: 13px; color: var(--df-accent); text-align: center; }

.elapsed { font-size: 12px; color: var(--df-text-muted); text-align: center; }

/* Results panel */
.results-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--df-bg);
}

.results-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid var(--df-border);
  background: var(--df-surface);
}

.results-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--df-text-muted);
}

.output-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.output-card {
  background: var(--df-surface);
  border: 1px solid var(--df-border);
  border-radius: 10px;
  padding: 16px;
  transition: border-color 0.2s;
}

.output-card:hover { border-color: var(--df-primary); }

.output-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.output-icon { font-size: 24px; }

.output-info { flex: 1; min-width: 0; }

.output-name {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.output-meta { font-size: 12px; color: var(--df-text-muted); margin-top: 2px; }

.output-desc {
  font-size: 12px;
  color: var(--df-text-muted);
  margin-bottom: 10px;
  padding: 6px 10px;
  background: var(--df-surface2);
  border-radius: 6px;
}

.output-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
