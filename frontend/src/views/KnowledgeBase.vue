<template>
  <div class="page-layout">
    <div class="page-header">
      <div>
        <h2>📚 {{ t.knowledge }}</h2>
        <p class="subtitle">管理文档库，支持「数据库」和「学习库」两种类型</p>
      </div>
      <el-button type="primary" @click="showUpload = true">
        <el-icon><Upload /></el-icon> 上传文档
      </el-button>
    </div>

    <!-- Library type tabs -->
    <div class="lib-tabs">
      <div
        v-for="tab in libTabs" :key="tab.value"
        class="lib-tab" :class="{ active: activeLibTab === tab.value }"
        @click="switchLibTab(tab.value)"
      >
        <span>{{ tab.icon }}</span>
        <span>{{ tab.label }}</span>
        <span class="tab-count">{{ countByLib(tab.value) }}</span>
      </div>
    </div>

    <!-- Database sub-navigation -->
    <div v-if="activeLibTab === 'database'" class="subdb-bar">
      <div class="subdb-label">子数据库：</div>
      <div class="subdb-list">
        <div
          class="subdb-item" :class="{ active: activeSubDb === '' }"
          @click="activeSubDb = ''"
        >全部</div>
        <div
          v-for="sub in subDatabases" :key="sub"
          class="subdb-item" :class="{ active: activeSubDb === sub }"
          @click="activeSubDb = sub"
        >
          <el-icon><FolderOpened /></el-icon> {{ sub }}
          <el-button size="small" text type="danger" @click.stop="deleteSubDb(sub)" style="padding:0;min-height:auto">
            <el-icon style="font-size:10px"><Close /></el-icon>
          </el-button>
        </div>
        <div class="subdb-add" @click="showCreateSubDb = true">
          <el-icon><Plus /></el-icon> {{ t.createSubDb }}
        </div>
      </div>
    </div>

    <!-- Stats -->
    <div class="stats-bar">
      <div class="stat-item" v-for="s in stats" :key="s.label">
        <span class="stat-num">{{ s.num }}</span>
        <span class="stat-label">{{ s.label }}</span>
      </div>
    </div>

    <!-- Filter bar -->
    <div class="filter-bar">
      <el-input v-model="searchText" placeholder="搜索文件名..." clearable style="width:200px" size="small">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="filterType" placeholder="文件类型" clearable size="small" style="width:120px">
        <el-option label="全部" value="" />
        <el-option label="Word (.docx)" value="docx" />
        <el-option label="Excel (.xlsx)" value="xlsx" />
        <el-option label="Markdown (.md)" value="md" />
        <el-option label="文本 (.txt)" value="txt" />
      </el-select>
      <el-button size="small" @click="refreshDocs" :loading="loadingDocs"><el-icon><Refresh /></el-icon></el-button>
    </div>

    <!-- Table -->
    <div class="table-wrapper">
      <el-table :data="filteredDocs" v-loading="loadingDocs" empty-text="暂无文档">
        <el-table-column width="40">
          <template #default="{ row }"><span style="font-size:18px">{{ getFileIcon(row.fileType) }}</span></template>
        </el-table-column>
        <el-table-column prop="fileName" label="文件名" min-width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="75">
          <template #default="{ row }">
            <el-tag size="small" :type="typeColor(row.fileType)">{{ row.fileType?.toUpperCase() }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="80">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="库类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.libraryType === 'learning' ? 'warning' : 'primary'">
              {{ row.libraryType === 'learning' ? t.learningLib : t.database }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="子数据库" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.subDatabase || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.processed ? 'success' : 'warning'">
              {{ row.processed ? '✓ 已处理' : '⟳ 处理中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" width="130">
          <template #default="{ row }">{{ formatDate(row.uploadTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="viewText(row)" :disabled="!row.processed">
              <el-icon><View /></el-icon> 查看
            </el-button>
            <el-button size="small" text @click="downloadDoc(row)">
              <el-icon><Download /></el-icon>
            </el-button>
            <el-button size="small" text type="danger" @click="deleteDoc(row)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- Upload Dialog -->
    <el-dialog v-model="showUpload" title="上传文档" width="560px" :close-on-click-modal="false">
      <el-form :model="uploadForm" label-width="90px">
        <el-form-item label="库类型">
          <el-radio-group v-model="uploadForm.libraryType">
            <el-radio-button label="database">{{ t.database }}</el-radio-button>
            <el-radio-button label="learning">{{ t.learningLib }}</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="uploadForm.libraryType === 'database'" label="子数据库">
          <el-select v-model="uploadForm.subDatabase" placeholder="选择或输入子数据库" allow-create filterable clearable style="width:100%">
            <el-option v-for="sub in subDatabases" :key="sub" :label="sub" :value="sub" />
          </el-select>
        </el-form-item>

        <el-form-item label="文件分类">
          <el-input v-model="uploadForm.category" placeholder="自定义分类标签（可选）" />
        </el-form-item>

        <el-form-item label="说明" v-if="uploadForm.libraryType === 'learning'" >
          <div style="font-size:12px;color:var(--df-text-muted);line-height:1.6">
            📖 学习库用于存放辅助模型理解的参考资料、领域知识、专业词典等内容，可在对话中关联使用提升模型回答质量。
          </div>
        </el-form-item>
      </el-form>

      <el-upload
        drag multiple :auto-upload="false"
        :file-list="uploadFileList"
        :on-change="handleFileChange"
        :on-remove="handleFileRemove"
        accept=".docx,.doc,.xlsx,.xls,.md,.txt"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text" style="font-size:13px">拖拽文件或<em>点击选择</em></div>
        <template #tip>
          <div style="font-size:11px;color:var(--df-text-muted);text-align:center;margin-top:4px">支持 .docx .xlsx .md .txt</div>
        </template>
      </el-upload>

      <template #footer>
        <el-button @click="showUpload = false">取消</el-button>
        <el-button type="primary" @click="doUpload" :loading="uploading" :disabled="uploadFileList.length === 0">
          上传 {{ uploadFileList.length > 0 ? `(${uploadFileList.length}个)` : '' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Create Sub-DB Dialog -->
    <el-dialog v-model="showCreateSubDb" title="新建子数据库" width="380px">
      <el-input v-model="newSubDbName" placeholder="输入子数据库名称" @keyup.enter="createSubDb" />
      <template #footer>
        <el-button @click="showCreateSubDb = false">取消</el-button>
        <el-button type="primary" @click="createSubDb" :disabled="!newSubDbName.trim()">创建</el-button>
      </template>
    </el-dialog>

    <!-- Text Preview Dialog -->
    <el-dialog v-model="showPreview" :title="previewDoc?.fileName" width="760px">
      <div class="preview-toolbar">
        <el-button size="small" @click="copyPreview"><el-icon><CopyDocument /></el-icon> 复制</el-button>
        <span style="font-size:12px;color:var(--df-text-muted)">共 {{ previewText.length }} 字符</span>
      </div>
      <div class="preview-text">{{ previewText }}</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { knowledgeApi, fileApi } from '@/api/index.js'
import { useKnowledgeStore, useSettingsStore } from '@/store/index.js'
import { storeToRefs } from 'pinia'

const kbStore = useKnowledgeStore()
const settingsStore = useSettingsStore()
const { t } = storeToRefs(settingsStore)

const docs = ref([])
const loadingDocs = ref(false)
const activeLibTab = ref('database')
const activeSubDb = ref('')
const subDatabases = ref([])
const searchText = ref('')
const filterType = ref('')
const showUpload = ref(false)
const uploading = ref(false)
const uploadForm = ref({ category: '', libraryType: 'database', subDatabase: '' })
const uploadFileList = ref([])
const showCreateSubDb = ref(false)
const newSubDbName = ref('')
const showPreview = ref(false)
const previewDoc = ref(null)
const previewText = ref('')

const libTabs = computed(() => [
  { value: 'database', icon: '🗄️', label: t.value.database },
  { value: 'learning', icon: '📖', label: t.value.learningLib },
])

function countByLib(type) {
  return docs.value.filter(d => d.libraryType === type || (!d.libraryType && type === 'database')).length
}

const filteredDocs = computed(() => {
  let list = docs.value.filter(d => {
    const lib = d.libraryType || 'database'
    return lib === activeLibTab.value
  })
  if (activeSubDb.value) list = list.filter(d => d.subDatabase === activeSubDb.value)
  if (searchText.value) list = list.filter(d => d.fileName.toLowerCase().includes(searchText.value.toLowerCase()))
  if (filterType.value) list = list.filter(d => d.fileType === filterType.value)
  return list
})

const stats = computed(() => {
  const list = filteredDocs.value
  const processed = list.filter(d => d.processed).length
  const types = {}
  list.forEach(d => { types[d.fileType] = (types[d.fileType] || 0) + 1 })
  return [
    { num: list.length, label: '文档数' },
    { num: processed, label: '已处理' },
    { num: types.docx || 0, label: 'Word' },
    { num: types.xlsx || 0, label: 'Excel' },
    { num: types.md || 0, label: 'MD' },
    { num: types.txt || 0, label: 'TXT' },
  ]
})

const getFileIcon = (type) => ({ docx:'📄', doc:'📄', xlsx:'📊', xls:'📊', md:'📝', txt:'📃' })[type?.toLowerCase()] || '📎'
const typeColor = (type) => ({ xlsx:'success', xls:'success', md:'info', txt:'warning' })[type?.toLowerCase()] || ''
const formatSize = (b) => !b ? '-' : b < 1048576 ? (b/1024).toFixed(1)+' KB' : (b/1048576).toFixed(1)+' MB'
const formatDate = (d) => !d ? '-' : new Date(d).toLocaleString('zh-CN', { month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit' })

async function switchLibTab(tab) {
  activeLibTab.value = tab
  activeSubDb.value = ''
}

async function refreshDocs() {
  loadingDocs.value = true
  const res = await knowledgeApi.list()
  if (res.success) { docs.value = res.data; kbStore.documents = res.data }
  const subRes = await knowledgeApi.getSubDatabases()
  if (subRes.success) subDatabases.value = subRes.data
  loadingDocs.value = false
}

function handleFileChange(file, fileList) { uploadFileList.value = fileList }
function handleFileRemove(file, fileList) { uploadFileList.value = fileList }

async function doUpload() {
  if (uploadFileList.value.length === 0) return
  uploading.value = true
  const files = uploadFileList.value.map(f => f.raw)
  const res = await knowledgeApi.uploadBatch(
    files, uploadForm.value.category || '默认',
    uploadForm.value.libraryType, uploadForm.value.subDatabase || null
  )
  uploading.value = false
  if (res.success) {
    ElMessage.success(`成功上传 ${res.data.length} 个文件`)
    showUpload.value = false
    uploadFileList.value = []
    await refreshDocs()
  }
}

function createSubDb() {
  const name = newSubDbName.value.trim()
  if (!name) return
  if (!subDatabases.value.includes(name)) subDatabases.value.push(name)
  activeSubDb.value = name
  showCreateSubDb.value = false
  newSubDbName.value = ''
  ElMessage.success(`子数据库「${name}」已创建`)
}

async function deleteSubDb(name) {
  await ElMessageBox.confirm(`删除子数据库「${name}」不会删除其中文档，只是取消分组。`, '确认', { type: 'warning' })
  subDatabases.value = subDatabases.value.filter(s => s !== name)
  if (activeSubDb.value === name) activeSubDb.value = ''
  ElMessage.success('已删除子数据库分组')
}

async function viewText(doc) {
  const res = await knowledgeApi.getText(doc.id)
  if (res.success) {
    previewDoc.value = doc
    previewText.value = res.data.text || '（暂无提取文本）'
    showPreview.value = true
  }
}

function downloadDoc(doc) { window.open(fileApi.downloadKnowledge(doc.id), '_blank') }

async function deleteDoc(doc) {
  await ElMessageBox.confirm(`确定删除「${doc.fileName}」？`, '确认', { type: 'warning' })
  await knowledgeApi.delete(doc.id)
  ElMessage.success('已删除')
  await refreshDocs()
}

function copyPreview() {
  navigator.clipboard.writeText(previewText.value).then(() => ElMessage.success('已复制'))
}

onMounted(refreshDocs)
</script>

<style scoped>
.page-layout { height: 100vh; display: flex; flex-direction: column; overflow: hidden; background: var(--df-bg); }

.page-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 24px 14px; background: var(--df-surface); border-bottom: 1px solid var(--df-border);
}
.page-header h2 { font-size: 18px; font-weight: 600; margin-bottom: 3px; }
.subtitle { font-size: 12px; color: var(--df-text-muted); }

.lib-tabs { display: flex; background: var(--df-surface); border-bottom: 1px solid var(--df-border); padding: 0 24px; }
.lib-tab {
  display: flex; align-items: center; gap: 6px;
  padding: 11px 18px; cursor: pointer; font-size: 13px; color: var(--df-text-muted);
  border-bottom: 2px solid transparent; transition: all 0.18s;
}
.lib-tab:hover { color: var(--df-text); }
.lib-tab.active { color: var(--df-primary); border-bottom-color: var(--df-primary); }
.tab-count { background: var(--df-surface2); padding: 1px 7px; border-radius: 10px; font-size: 11px; }

.subdb-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 24px; background: var(--df-surface);
  border-bottom: 1px solid var(--df-border); flex-wrap: wrap;
}
.subdb-label { font-size: 12px; color: var(--df-text-muted); flex-shrink: 0; }
.subdb-list { display: flex; gap: 6px; flex-wrap: wrap; }
.subdb-item {
  display: flex; align-items: center; gap: 4px;
  padding: 4px 12px; border-radius: 14px; cursor: pointer;
  font-size: 12px; color: var(--df-text-muted);
  background: var(--df-surface2); border: 1px solid var(--df-border); transition: all 0.15s;
}
.subdb-item:hover { border-color: var(--df-primary); color: var(--df-text); }
.subdb-item.active { background: rgba(91,138,240,0.15); color: var(--df-primary); border-color: var(--df-primary); }
.subdb-add {
  display: flex; align-items: center; gap: 4px;
  padding: 4px 12px; border-radius: 14px; cursor: pointer;
  font-size: 12px; color: var(--df-primary);
  border: 1px dashed var(--df-primary); transition: all 0.15s;
}
.subdb-add:hover { background: rgba(91,138,240,0.1); }

.stats-bar { display: flex; background: var(--df-surface); border-bottom: 1px solid var(--df-border); }
.stat-item { flex: 1; display: flex; flex-direction: column; align-items: center; padding: 10px 6px; border-right: 1px solid var(--df-border); }
.stat-item:last-child { border-right: none; }
.stat-num { font-size: 20px; font-weight: 700; color: var(--df-primary); }
.stat-label { font-size: 10px; color: var(--df-text-muted); margin-top: 2px; }

.filter-bar { display: flex; gap: 8px; align-items: center; padding: 10px 20px; background: var(--df-surface); border-bottom: 1px solid var(--df-border); }

.table-wrapper { flex: 1; overflow: auto; padding: 14px 20px; }

.preview-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.preview-text {
  background: var(--df-bg); border: 1px solid var(--df-border); border-radius: 8px;
  padding: 14px; font-family: 'Fira Code', monospace; font-size: 12.5px;
  line-height: 1.65; white-space: pre-wrap; max-height: 480px; overflow-y: auto; color: var(--df-text);
}
</style>
