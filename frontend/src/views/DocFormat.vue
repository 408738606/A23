<template>
  <div class="page-layout">
    <div class="page-header">
      <div>
        <h2>📝 文档排版对话</h2>
        <p class="subtitle">上传或选择知识库文档，让模型按你的要求完成排版与格式调整</p>
      </div>
    </div>

    <div class="content">
      <div class="left-panel">
        <div class="section-title">① 选择文档</div>
        <el-radio-group v-model="inputMode" size="small" style="margin-bottom:10px">
          <el-radio-button label="local">本地上传</el-radio-button>
          <el-radio-button label="knowledge">知识库选择</el-radio-button>
        </el-radio-group>

        <el-upload
          v-if="inputMode === 'local'"
          drag
          :auto-upload="false"
          :limit="1"
          :file-list="fileList"
          :on-change="onFileChange"
          :on-remove="onFileRemove"
          accept=".docx,.doc,.md,.txt,.xlsx,.xls"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖拽文件或<em>点击上传</em></div>
        </el-upload>

        <div v-else class="kb-box">
          <el-select v-model="selectedSubDb" clearable placeholder="先选择子数据库" style="width:100%;margin-bottom:8px">
            <el-option label="全部" value="" />
            <el-option v-for="s in subDatabases" :key="s" :label="s" :value="s" />
          </el-select>
          <el-select v-model="knowledgeDocId" filterable placeholder="再选择文档" style="width:100%">
            <el-option v-for="d in filteredKbDocs" :key="d.id" :label="d.fileName" :value="d.id" />
          </el-select>
        </div>

        <div class="section-title" style="margin-top:16px">② 输入排版要求</div>
        <el-input v-model="prompt" type="textarea" :rows="7" placeholder="例如：改为正式公文风格，按“背景-问题-建议-结论”结构输出，保留原始数据..." />

        <div class="section-title" style="margin-top:16px">③ 选择模型与输出格式</div>
        <el-select v-model="selectedConfigId" placeholder="选择模型" style="width:100%;margin-bottom:8px">
          <el-option v-for="cfg in llmConfigs" :key="cfg.id" :label="`${cfg.configName} (${cfg.modelName})`" :value="cfg.id" />
        </el-select>
        <el-select v-model="outputType" placeholder="输出格式" style="width:100%">
          <el-option label="Word (DOCX)" value="word" />
          <el-option label="Markdown" value="md" />
          <el-option label="TXT" value="txt" />
        </el-select>

        <el-button type="primary" :loading="processing" :disabled="!canSubmit" @click="submit" style="width:100%;margin-top:14px">
          开始处理
        </el-button>
      </div>

      <div class="right-panel">
        <div class="results-header">📂 处理结果</div>
        <div v-if="outputs.length === 0" class="empty">暂无结果</div>
        <div v-else class="output-list">
          <div v-for="out in outputs" :key="out.id" class="output-item">
            <div class="name">{{ out.fileName }}</div>
            <div class="meta">{{ formatSize(out.fileSize) }} · {{ formatDate(out.createdAt) }}</div>
            <div class="actions">
              <el-button size="small" type="primary" @click="download(out)">下载</el-button>
              <el-button size="small" @click="openSaveDialog(out)">存入知识库</el-button>
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
        <el-button type="primary" @click="saveOutputToKb">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { docFormatApi, fileApi, knowledgeApi, llmApi, tableFillApi } from '@/api/index.js'

const inputMode = ref('local')
const fileList = ref([])
const localFile = ref(null)
const knowledgeDocId = ref(null)
const selectedSubDb = ref('')
const subDatabases = ref([])
const kbDocs = ref([])

const prompt = ref('')
const llmConfigs = ref([])
const selectedConfigId = ref(null)
const outputType = ref('word')
const processing = ref(false)
const outputs = ref([])

const saveDialogVisible = ref(false)
const savingOutput = ref(null)
const saveForm = ref({ libraryType: 'database', subDatabase: '', category: '文档排版结果' })

const filteredKbDocs = computed(() => {
  let list = kbDocs.value
  if (selectedSubDb.value) list = list.filter(d => d.subDatabase === selectedSubDb.value)
  return list
})

const canSubmit = computed(() => {
  const hasInput = inputMode.value === 'local' ? !!localFile.value : !!knowledgeDocId.value
  return hasInput && !!prompt.value.trim() && !!selectedConfigId.value && !processing.value
})

function onFileChange(file) {
  localFile.value = file.raw
  fileList.value = [file]
}

function onFileRemove() {
  localFile.value = null
  fileList.value = []
}

async function submit() {
  processing.value = true
  try {
    const res = await docFormatApi.process({
      file: inputMode.value === 'local' ? localFile.value : null,
      knowledgeDocId: inputMode.value === 'knowledge' ? knowledgeDocId.value : null,
      prompt: prompt.value,
      outputType: outputType.value,
      llmConfigId: selectedConfigId.value,
      sessionId: null,
    })
    if (res.success) {
      outputs.value.unshift(res.data)
      ElMessage.success('处理完成')
    }
  } finally {
    processing.value = false
  }
}

function download(out) {
  window.open(fileApi.downloadOutput(out.id), '_blank')
}

function openSaveDialog(out) {
  savingOutput.value = out
  saveDialogVisible.value = true
}

async function saveOutputToKb() {
  if (!savingOutput.value) return
  const res = await tableFillApi.saveToKb(savingOutput.value.id, {
    libraryType: saveForm.value.libraryType,
    subDatabase: saveForm.value.subDatabase || null,
    category: saveForm.value.category || '文档排版结果',
  })
  if (res.success) {
    ElMessage.success('已存入知识库')
    saveDialogVisible.value = false
  }
}

const formatSize = (b) => !b ? '-' : b < 1048576 ? (b/1024).toFixed(1)+' KB' : (b/1048576).toFixed(1)+' MB'
const formatDate = (d) => !d ? '-' : new Date(d).toLocaleString('zh-CN', { month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit' })

onMounted(async () => {
  const [kbRes, subRes, cfgRes, outRes] = await Promise.all([
    knowledgeApi.list(),
    knowledgeApi.getSubDatabases(),
    llmApi.list(),
    tableFillApi.getOutputs(),
  ])
  if (kbRes.success) kbDocs.value = kbRes.data
  if (subRes.success) subDatabases.value = subRes.data
  if (cfgRes.success) {
    llmConfigs.value = cfgRes.data.filter(c => c.isActive)
    const def = llmConfigs.value.find(c => c.isDefault)
    selectedConfigId.value = def?.id || llmConfigs.value[0]?.id || null
  }
  if (outRes.success) outputs.value = outRes.data
})
</script>

<style scoped>
.page-layout { height: 100vh; display: flex; flex-direction: column; }
.page-header { padding: 18px 24px 12px; background: var(--df-surface); border-bottom: 1px solid var(--df-border); }
.subtitle { font-size: 12px; color: var(--df-text-muted); margin-top: 4px; }
.content { flex: 1; display: flex; overflow: hidden; }
.left-panel { width: 420px; border-right: 1px solid var(--df-border); background: var(--df-surface); padding: 16px; overflow-y: auto; }
.right-panel { flex: 1; padding: 16px; overflow-y: auto; }
.section-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
.results-header { font-size: 14px; font-weight: 600; margin-bottom: 10px; }
.empty { color: var(--df-text-muted); font-size: 13px; }
.output-list { display: flex; flex-direction: column; gap: 10px; }
.output-item { border: 1px solid var(--df-border); border-radius: 8px; padding: 10px; background: var(--df-surface); }
.name { font-size: 13px; font-weight: 600; }
.meta { font-size: 12px; color: var(--df-text-muted); margin-top: 2px; }
.actions { margin-top: 8px; display: flex; gap: 8px; }
.kb-box { background: var(--df-surface2); border: 1px solid var(--df-border); border-radius: 8px; padding: 10px; }
</style>
