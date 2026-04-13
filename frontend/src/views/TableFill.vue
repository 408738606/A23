<template>
  <div class="page-layout">
    <div class="page-header">
      <div>
        <h2>📋 智能表格填写</h2>
        <p class="subtitle">支持模板、数据源、用户要求文档从知识库（先子数据库后文件）或本地上传选择</p>
      </div>
    </div>

    <div class="fill-content">
      <div class="config-panel">
        <div class="panel-section">
          <div class="section-title">① 选择模板</div>
          <el-radio-group v-model="templateMode" size="small">
            <el-radio-button label="local">本地上传</el-radio-button>
            <el-radio-button label="knowledge">知识库选择</el-radio-button>
          </el-radio-group>

          <el-upload
            v-if="templateMode === 'local'"
            drag
            :auto-upload="false"
            :limit="1"
            :file-list="templateFileList"
            :on-change="onTemplateChange"
            :on-remove="onTemplateRemove"
            accept=".docx,.doc,.xlsx,.xls,.md,.txt"
            class="template-upload"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽模板文件或<em>点击选择</em></div>
            <template #tip><div class="upload-tip">支持 doc/docx/xls/xlsx/md/txt</div></template>
          </el-upload>

          <div v-else class="selector-box">
            <el-select v-model="templateSubDb" clearable placeholder="先选择子数据库" style="width:100%;margin-bottom:8px">
              <el-option label="全部" value="" />
              <el-option v-for="s in subDatabases" :key="s" :label="s" :value="s" />
            </el-select>
            <el-select v-model="templateDocId" filterable placeholder="再选择模板文件" style="width:100%">
              <el-option
                v-for="d in templateKbDocs"
                :key="d.id"
                :label="d.fileName"
                :value="d.id"
              />
            </el-select>
          </div>
        </div>

        <div class="panel-section">
          <div class="section-title">② 选择数据源文档</div>
          <el-tabs v-model="sourceTab">
            <el-tab-pane label="知识库" name="kb">
              <el-select v-model="sourceSubDb" clearable placeholder="先选子数据库" style="width:100%;margin-bottom:8px">
                <el-option label="全部" value="" />
                <el-option v-for="s in subDatabases" :key="s" :label="s" :value="s" />
              </el-select>
              <div class="doc-list">
                <el-checkbox-group v-model="selectedSourceIds">
                  <div v-for="doc in sourceKbDocs" :key="doc.id" class="doc-item">
                    <el-checkbox :label="doc.id">
                      <span>{{ getFileIcon(doc.fileType) }} {{ doc.fileName }}</span>
                    </el-checkbox>
                  </div>
                </el-checkbox-group>
              </div>
            </el-tab-pane>
            <el-tab-pane label="本地上传" name="local">
              <el-upload
                drag
                multiple
                :auto-upload="false"
                :file-list="sourceFileList"
                :on-change="(_, list) => sourceFileList = list"
                :on-remove="(_, list) => sourceFileList = list"
                accept=".docx,.doc,.xlsx,.xls,.md,.txt"
              >
                <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                <div class="el-upload__text">拖拽或点击上传数据源文件</div>
              </el-upload>
            </el-tab-pane>
          </el-tabs>
        </div>

        <div class="panel-section">
          <div class="section-title">③ 用户要求文档（可选）</div>
          <el-tabs v-model="reqTab">
            <el-tab-pane label="知识库" name="kb">
              <el-select v-model="requirementSubDb" clearable placeholder="先选子数据库" style="width:100%;margin-bottom:8px">
                <el-option label="全部" value="" />
                <el-option v-for="s in subDatabases" :key="s" :label="s" :value="s" />
              </el-select>
              <div class="doc-list">
                <el-checkbox-group v-model="selectedRequirementIds">
                  <div v-for="doc in requirementKbDocs" :key="doc.id" class="doc-item">
                    <el-checkbox :label="doc.id">
                      <span>{{ getFileIcon(doc.fileType) }} {{ doc.fileName }}</span>
                    </el-checkbox>
                  </div>
                </el-checkbox-group>
              </div>
            </el-tab-pane>
            <el-tab-pane label="本地上传" name="local">
              <el-upload
                drag
                multiple
                :auto-upload="false"
                :file-list="requirementFileList"
                :on-change="(_, list) => requirementFileList = list"
                :on-remove="(_, list) => requirementFileList = list"
                accept=".docx,.doc,.xlsx,.xls,.md,.txt"
              >
                <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                <div class="el-upload__text">拖拽或点击上传用户要求文档</div>
              </el-upload>
            </el-tab-pane>
          </el-tabs>
        </div>

        <div class="panel-section">
          <div class="section-title">④ 选择模型</div>
          <el-select v-model="selectedConfigId" placeholder="选择大模型" style="width:100%">
            <el-option v-for="cfg in llmConfigs" :key="cfg.id" :label="`${cfg.configName} (${cfg.modelName})`" :value="cfg.id" />
          </el-select>
        </div>

        <el-button type="primary" :loading="filling" :disabled="!canSubmit" @click="startFill" style="width:100%;margin-top:6px">
          {{ filling ? 'AI 正在填写中...' : '开始智能填写' }}
        </el-button>
      </div>

      <div class="results-panel">
        <div class="results-header">
          <span>📂 填写结果</span>
          <el-button size="small" text @click="refreshOutputs"><el-icon><Refresh /></el-icon></el-button>
        </div>

        <div v-if="outputs.length === 0" class="results-empty">尚无填写结果</div>
        <div v-else class="output-list">
          <div v-for="output in outputs" :key="output.id" class="output-card">
            <div class="output-name">{{ output.fileName }}</div>
            <div class="output-meta">{{ formatSize(output.fileSize) }} · {{ formatDate(output.createdAt) }}</div>
            <div class="output-desc" v-if="output.description">{{ output.description }}</div>
            <div class="output-actions">
              <el-button type="primary" size="small" @click="downloadOutput(output)">下载</el-button>
              <el-button size="small" @click="openSaveDialog(output)" :disabled="output.savedToKnowledgeBase">存入知识库</el-button>
              <el-button size="small" text type="danger" @click="deleteOutput(output)">删除</el-button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="saveDialogVisible" title="存入知识库" width="420px">
      <el-form label-width="90px">
        <el-form-item label="库类型">
          <el-select v-model="saveForm.libraryType" style="width:100%">
            <el-option label="数据库" value="database" />
            <el-option label="学习库" value="learning" />
          </el-select>
        </el-form-item>
        <el-form-item label="子数据库" v-if="saveForm.libraryType === 'database'">
          <el-select v-model="saveForm.subDatabase" clearable allow-create filterable style="width:100%">
            <el-option v-for="s in subDatabases" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="saveForm.category" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveToKb">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi, tableFillApi, llmApi, fileApi } from '@/api/index.js'

const kbDocs = ref([])
const subDatabases = ref([])

const templateMode = ref('local')
const templateFile = ref(null)
const templateFileList = ref([])
const templateDocId = ref(null)
const templateSubDb = ref('')

const sourceTab = ref('kb')
const sourceSubDb = ref('')
const selectedSourceIds = ref([])
const sourceFileList = ref([])

const reqTab = ref('kb')
const requirementSubDb = ref('')
const selectedRequirementIds = ref([])
const requirementFileList = ref([])

const llmConfigs = ref([])
const selectedConfigId = ref(null)
const filling = ref(false)
const outputs = ref([])

const saveDialogVisible = ref(false)
const targetOutput = ref(null)
const saveForm = ref({ libraryType: 'database', subDatabase: '', category: 'AI填写结果' })

const validTemplateExt = ['docx', 'doc', 'xlsx', 'xls', 'md', 'txt']
const templateKbDocs = computed(() => {
  let list = kbDocs.value.filter(d => validTemplateExt.includes((d.fileType || '').toLowerCase()))
  if (templateSubDb.value) list = list.filter(d => d.subDatabase === templateSubDb.value)
  return list
})
const sourceKbDocs = computed(() => {
  let list = kbDocs.value
  if (sourceSubDb.value) list = list.filter(d => d.subDatabase === sourceSubDb.value)
  return list
})
const requirementKbDocs = computed(() => {
  let list = kbDocs.value
  if (requirementSubDb.value) list = list.filter(d => d.subDatabase === requirementSubDb.value)
  return list
})

const canSubmit = computed(() => {
  const hasTemplate = templateMode.value === 'local' ? !!templateFile.value : !!templateDocId.value
  const hasSource = selectedSourceIds.value.length > 0 || sourceFileList.value.length > 0
  return hasTemplate && hasSource && !!selectedConfigId.value
})

function onTemplateChange(file) {
  templateFile.value = file.raw
  templateFileList.value = [file]
}
function onTemplateRemove() {
  templateFile.value = null
  templateFileList.value = []
}

async function startFill() {
  if (!canSubmit.value) return
  filling.value = true
  try {
    const res = await tableFillApi.fill({
      template: templateMode.value === 'local' ? templateFile.value : null,
      templateDocId: templateMode.value === 'knowledge' ? templateDocId.value : null,
      sourceDocIds: selectedSourceIds.value,
      sourceFiles: sourceFileList.value.map(f => f.raw),
      requirementDocIds: selectedRequirementIds.value,
      requirementFiles: requirementFileList.value.map(f => f.raw),
      llmConfigId: selectedConfigId.value,
      sessionId: null,
    })
    if (res.success) {
      ElMessage.success(`表格填写完成，耗时 ${Math.round(res.elapsedMs / 1000)} 秒`)
      await refreshOutputs()
    }
  } finally {
    filling.value = false
  }
}

async function refreshKbDocs() {
  const [res, subRes] = await Promise.all([knowledgeApi.list(), knowledgeApi.getSubDatabases()])
  if (res.success) kbDocs.value = res.data
  if (subRes.success) subDatabases.value = subRes.data
}

async function refreshOutputs() {
  const res = await tableFillApi.getOutputs()
  if (res.success) outputs.value = res.data
}

function downloadOutput(output) {
  window.open(fileApi.downloadOutput(output.id), '_blank')
}

function openSaveDialog(output) {
  targetOutput.value = output
  saveDialogVisible.value = true
}

async function saveToKb() {
  if (!targetOutput.value) return
  const res = await tableFillApi.saveToKb(targetOutput.value.id, {
    libraryType: saveForm.value.libraryType,
    subDatabase: saveForm.value.subDatabase || null,
    category: saveForm.value.category || 'AI填写结果',
  })
  if (res.success) {
    ElMessage.success('已保存到知识库')
    targetOutput.value.savedToKnowledgeBase = true
    saveDialogVisible.value = false
    await refreshKbDocs()
  }
}

async function deleteOutput(output) {
  await ElMessageBox.confirm(`确定删除「${output.fileName}」？`, '确认', { type: 'warning' })
  await tableFillApi.deleteOutput(output.id)
  ElMessage.success('已删除')
  await refreshOutputs()
}

const getFileIcon = (type) => ({ docx: '📄', doc: '📄', xlsx: '📊', xls: '📊', md: '📝', txt: '📃' })[type?.toLowerCase()] || '📎'
const formatSize = (bytes) => !bytes ? '-' : bytes < 1024 * 1024 ? (bytes / 1024).toFixed(1) + ' KB' : (bytes / 1024 / 1024).toFixed(1) + ' MB'
const formatDate = (d) => !d ? '-' : new Date(d).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })

onMounted(async () => {
  await refreshKbDocs()
  await refreshOutputs()
  const cfgRes = await llmApi.list()
  if (cfgRes.success) {
    llmConfigs.value = cfgRes.data.filter(c => c.isActive)
    const def = llmConfigs.value.find(c => c.isDefault)
    selectedConfigId.value = def?.id || llmConfigs.value[0]?.id || null
  }
})
</script>

<style scoped>
.page-layout { height: 100vh; display: flex; flex-direction: column; overflow: hidden; }
.page-header { padding: 20px 24px 14px; background: var(--df-surface); border-bottom: 1px solid var(--df-border); }
.subtitle { font-size: 12px; color: var(--df-text-muted); }
.fill-content { flex: 1; display: flex; overflow: hidden; }
.config-panel { width: 410px; min-width: 410px; background: var(--df-surface); border-right: 1px solid var(--df-border); padding: 16px; overflow-y: auto; }
.results-panel { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.results-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid var(--df-border); background: var(--df-surface); }
.results-empty { padding: 20px; color: var(--df-text-muted); }
.output-list { flex: 1; overflow-y: auto; padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.output-card { background: var(--df-surface); border: 1px solid var(--df-border); border-radius: 8px; padding: 12px; }
.output-name { font-size: 14px; font-weight: 600; }
.output-meta { font-size: 12px; color: var(--df-text-muted); margin-top: 2px; }
.output-desc { margin-top: 8px; font-size: 12px; color: var(--df-text-muted); }
.output-actions { margin-top: 8px; display: flex; gap: 8px; }
.panel-section { margin-bottom: 16px; }
.section-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
.template-upload { width: 100%; }
.upload-tip { font-size: 11px; color: var(--df-text-muted); text-align: center; margin-top: 4px; }
.selector-box { background: var(--df-surface2); border: 1px solid var(--df-border); border-radius: 8px; padding: 10px; }
.doc-list { max-height: 180px; overflow-y: auto; background: var(--df-surface2); border-radius: 8px; padding: 8px; }
.doc-item { padding: 4px 2px; }
</style>
