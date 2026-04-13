<template>
  <div class="page-layout">
    <div class="page-header">
      <div>
        <h2>⚙️ {{ t.settings }}</h2>
        <p class="subtitle">配置大模型接口，支持本地部署模型和远程 API 调用</p>
      </div>
      <el-button type="primary" @click="openDialog()">
        <el-icon><Plus /></el-icon> 添加配置
      </el-button>
    </div>

    <!-- Type tabs -->
    <div class="type-tabs">
      <div
        v-for="tab in modelTabs" :key="tab.value"
        class="type-tab" :class="{ active: activeTab === tab.value }"
        @click="activeTab = tab.value"
      >
        <span>{{ tab.icon }}</span>
        <span>{{ tab.label }}</span>
        <span class="tab-count">{{ countByType(tab.value) }}</span>
      </div>
    </div>

    <!-- Quick presets -->
    <div class="presets-section">
      <div class="presets-title">快速添加</div>
      <div class="presets-grid">
        <div
          v-for="preset in filteredPresets"
          :key="preset.provider"
          class="preset-card"
          @click="openDialog(preset)"
        >
          <span class="preset-icon">{{ preset.icon }}</span>
          <div class="preset-info">
            <div class="preset-name">{{ preset.name }}</div>
            <div class="preset-desc">{{ preset.desc }}</div>
          </div>
          <el-tag size="small" :type="preset.type === 'local' ? 'success' : 'primary'">
            {{ preset.type === 'local' ? t.localModel : t.apiCall }}
          </el-tag>
        </div>
      </div>
    </div>

    <!-- Config cards -->
    <div class="config-list">
      <div v-if="filteredConfigs.length === 0" class="empty-state">
        <el-icon style="font-size:40px;color:var(--df-text-muted)"><Setting /></el-icon>
        <p>暂无配置，点击上方快速添加</p>
      </div>
      <div v-for="cfg in filteredConfigs" :key="cfg.id" class="config-card">
        <div class="config-badge" v-if="cfg.isDefault"><el-tag type="success" size="small">默认</el-tag></div>

        <div class="config-header">
          <div class="config-icon">{{ getProviderIcon(cfg.provider) }}</div>
          <div class="config-title">
            <div class="config-name">{{ cfg.configName }}</div>
            <div class="config-model">{{ cfg.modelName }}</div>
          </div>
          <el-tag size="small" :type="cfg.modelCategory === 'local' ? 'success' : 'primary'">
            {{ cfg.modelCategory === 'local' ? t.localModel : t.apiCall }}
          </el-tag>
          <el-tag size="small" :type="cfg.isActive ? 'info' : ''">{{ cfg.isActive ? '启用' : '停用' }}</el-tag>
        </div>

        <div class="config-details">
          <div class="detail-item"><span class="dl">服务商</span><span>{{ cfg.provider }}</span></div>
          <div class="detail-item"><span class="dl">接口地址</span><span class="detail-url">{{ cfg.baseUrl }}</span></div>
          <div class="detail-item"><span class="dl">Max Tokens</span><span>{{ cfg.maxTokens }}</span></div>
          <div class="detail-item"><span class="dl">Temperature</span><span>{{ cfg.temperature }}</span></div>
        </div>

        <div class="config-actions">
          <el-button size="small" @click="testConfig(cfg)" :loading="testingId === cfg.id">
            <el-icon><Connection /></el-icon> 测试连接
          </el-button>
          <el-button size="small" @click="setDefault(cfg)" :disabled="cfg.isDefault">
            <el-icon><StarFilled /></el-icon> 设为默认
          </el-button>
          <el-button size="small" @click="openDialog(null, cfg)">
            <el-icon><Edit /></el-icon> 编辑
          </el-button>
          <el-button size="small" type="danger" text @click="deleteConfig(cfg)">
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
      </div>
    </div>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="showDialog" :title="editingConfig?.id ? '编辑配置' : '添加模型配置'" width="580px" :close-on-click-modal="false">
      <el-form :model="form" label-width="110px" :rules="rules" ref="formRef">

        <el-form-item label="配置名称" prop="configName">
          <el-input v-model="form.configName" placeholder="如：GPT-4o生产环境" />
        </el-form-item>

        <!-- Model category selector -->
        <el-form-item label="模型类型" prop="modelCategory">
          <el-radio-group v-model="form.modelCategory" @change="onCategoryChange">
            <el-radio-button label="api">
              <el-icon><Cloud /></el-icon> API 调用
            </el-radio-button>
            <el-radio-button label="local">
              <el-icon><Monitor /></el-icon> 本地模型
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <!-- Local model sub-type -->
        <el-form-item v-if="form.modelCategory === 'local'" label="本地框架">
          <el-select v-model="form.provider" style="width:100%" @change="onProviderChange">
            <el-option label="🦙 Ollama（推荐）" value="ollama" />
            <el-option label="⚡ llama.cpp" value="llamacpp" />
            <el-option label="🐍 LM Studio" value="lmstudio" />
            <el-option label="🔧 本地训练模型（OpenAI兼容）" value="local-custom" />
          </el-select>
        </el-form-item>

        <!-- API provider -->
        <el-form-item v-if="form.modelCategory === 'api'" label="服务商" prop="provider">
          <el-select v-model="form.provider" style="width:100%" @change="onProviderChange">
            <el-option label="🤖 OpenAI" value="openai" />
            <el-option label="🌊 DeepSeek" value="deepseek" />
            <el-option label="🌙 Moonshot (Kimi)" value="moonshot" />
            <el-option label="🤝 智谱 GLM" value="zhipu" />
            <el-option label="🌐 通义千问" value="qianwen" />
            <el-option label="⚙️ 自定义接口" value="custom" />
          </el-select>
        </el-form-item>

        <el-form-item label="API Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" :placeholder="baseUrlPlaceholder" />
        </el-form-item>

        <el-form-item :label="form.modelCategory === 'local' ? '模型路径/名称' : '模型名称'" prop="modelName">
          <el-input v-model="form.modelName" :placeholder="modelNamePlaceholder" />
        </el-form-item>

        <el-form-item v-if="form.modelCategory === 'api'" label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password placeholder="本地模型可留空" />
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Max Tokens">
              <el-input-number v-model="form.maxTokens" :min="256" :max="32768" :step="512" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Temperature">
              <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="1" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="设为默认"><el-switch v-model="form.isDefault" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="启用"><el-switch v-model="form.isActive" /></el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button @click="testDialogConfig" :loading="testingDialog">测试连接</el-button>
        <el-button type="primary" @click="saveConfig" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { llmApi } from '@/api/index.js'
import { useLlmStore, useSettingsStore } from '@/store/index.js'
import { storeToRefs } from 'pinia'

const llmStore = useLlmStore()
const settingsStore = useSettingsStore()
const { t } = storeToRefs(settingsStore)

const configs = ref([])
const activeTab = ref('all')
const showDialog = ref(false)
const saving = ref(false)
const testingId = ref(null)
const testingDialog = ref(false)
const editingConfig = ref(null)
const formRef = ref(null)

const form = ref({
  configName: '', modelCategory: 'api', provider: 'openai',
  baseUrl: 'https://api.openai.com/v1', apiKey: '', modelName: 'gpt-4o',
  maxTokens: 4096, temperature: 0.7, isDefault: false, isActive: true
})

const rules = {
  configName: [{ required: true, message: '请输入配置名称' }],
  provider: [{ required: true, message: '请选择服务商' }],
  baseUrl: [{ required: true, message: '请输入API地址' }],
  modelName: [{ required: true, message: '请输入模型名称' }],
}

const modelTabs = computed(() => [
  { value: 'all', icon: '📋', label: '全部' },
  { value: 'api', icon: '☁️', label: t.value.apiCall },
  { value: 'local', icon: '💻', label: t.value.localModel },
])

const filteredConfigs = computed(() => {
  if (activeTab.value === 'all') return configs.value
  return configs.value.filter(c => c.modelCategory === activeTab.value)
})

const filteredPresets = computed(() => {
  const all = presets.value
  if (activeTab.value === 'all') return all
  return all.filter(p => p.type === activeTab.value)
})

function countByType(type) {
  if (type === 'all') return configs.value.length
  return configs.value.filter(c => c.modelCategory === type).length
}

const providerConfig = {
  openai:       { url: 'https://api.openai.com/v1', model: 'gpt-4o' },
  deepseek:     { url: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  moonshot:     { url: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  zhipu:        { url: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  qianwen:      { url: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-max' },
  ollama:       { url: 'http://localhost:11434', model: 'llama3:8b' },
  llamacpp:     { url: 'http://localhost:8080', model: 'local-model' },
  lmstudio:     { url: 'http://localhost:1234/v1', model: 'local-model' },
  'local-custom': { url: 'http://localhost:8000/v1', model: 'my-model' },
}

const presets = ref([
  { icon:'🤖', name:'OpenAI', desc:'GPT-4o / GPT-4-turbo', provider:'openai', type:'api', modelCategory:'api' },
  { icon:'🌊', name:'DeepSeek', desc:'deepseek-chat / coder', provider:'deepseek', type:'api', modelCategory:'api' },
  { icon:'🌙', name:'Moonshot', desc:'moonshot-v1-8k', provider:'moonshot', type:'api', modelCategory:'api' },
  { icon:'🤝', name:'智谱 GLM', desc:'glm-4-flash / glm-4', provider:'zhipu', type:'api', modelCategory:'api' },
  { icon:'🦙', name:'Ollama', desc:'本地部署，无需API Key', provider:'ollama', type:'local', modelCategory:'local' },
  { icon:'⚡', name:'llama.cpp', desc:'高性能本地推理', provider:'llamacpp', type:'local', modelCategory:'local' },
  { icon:'🎨', name:'LM Studio', desc:'可视化本地模型管理', provider:'lmstudio', type:'local', modelCategory:'local' },
  { icon:'🔧', name:'本地训练模型', desc:'自定义OpenAI兼容接口', provider:'local-custom', type:'local', modelCategory:'local' },
])

const getProviderIcon = (p) => {
  const m = { openai:'🤖', deepseek:'🌊', moonshot:'🌙', zhipu:'🤝', qianwen:'🌐',
               ollama:'🦙', llamacpp:'⚡', lmstudio:'🎨', 'local-custom':'🔧', custom:'⚙️' }
  return m[p] || '⚙️'
}

const baseUrlPlaceholder = computed(() => {
  return providerConfig[form.value.provider]?.url || 'http://localhost:8000/v1'
})

const modelNamePlaceholder = computed(() => {
  const p = providerConfig[form.value.provider]
  if (form.value.modelCategory === 'local') return '如：llama3:8b 或 /path/to/model.gguf'
  return p?.model || 'gpt-4o'
})

function onCategoryChange(cat) {
  if (cat === 'local') {
    form.value.provider = 'ollama'
    form.value.baseUrl = 'http://localhost:11434'
    form.value.modelName = 'llama3:8b'
    form.value.apiKey = ''
  } else {
    form.value.provider = 'openai'
    form.value.baseUrl = 'https://api.openai.com/v1'
    form.value.modelName = 'gpt-4o'
  }
}

function onProviderChange(p) {
  const pc = providerConfig[p]
  if (pc) { form.value.baseUrl = pc.url; form.value.modelName = pc.model }
}

function openDialog(preset = null, config = null) {
  editingConfig.value = config
  if (config) {
    form.value = { ...config, modelCategory: config.modelCategory || 'api' }
  } else if (preset) {
    form.value = {
      configName: preset.name, modelCategory: preset.modelCategory || 'api',
      provider: preset.provider, baseUrl: providerConfig[preset.provider]?.url || '',
      apiKey: '', modelName: providerConfig[preset.provider]?.model || '',
      maxTokens: 4096, temperature: 0.7, isDefault: false, isActive: true
    }
  } else {
    form.value = {
      configName: '', modelCategory: 'api', provider: 'openai',
      baseUrl: 'https://api.openai.com/v1', apiKey: '', modelName: 'gpt-4o',
      maxTokens: 4096, temperature: 0.7, isDefault: false, isActive: true
    }
  }
  showDialog.value = true
}

async function saveConfig() {
  await formRef.value?.validate()
  saving.value = true
  try {
    if (editingConfig.value?.id) {
      await llmApi.update(editingConfig.value.id, form.value)
      ElMessage.success('配置已更新')
    } else {
      await llmApi.save(form.value)
      ElMessage.success('配置已添加')
    }
    showDialog.value = false
    await fetchConfigs()
  } finally { saving.value = false }
}

async function testConfig(cfg) {
  testingId.value = cfg.id
  const res = await llmApi.test(cfg)
  testingId.value = null
  res.success ? ElMessage.success(res.message) : ElMessage.error(res.message)
}

async function testDialogConfig() {
  testingDialog.value = true
  const res = await llmApi.test(form.value)
  testingDialog.value = false
  res.success ? ElMessage.success(res.message || '连接成功') : ElMessage.error(res.message || '连接失败')
}

async function setDefault(cfg) {
  await llmApi.setDefault(cfg.id)
  ElMessage.success(`「${cfg.configName}」已设为默认`)
  await fetchConfigs(); await llmStore.fetchDefault()
}

async function deleteConfig(cfg) {
  await ElMessageBox.confirm(`确定删除「${cfg.configName}」？`, '确认', { type: 'warning' })
  await llmApi.delete(cfg.id)
  ElMessage.success('已删除')
  await fetchConfigs()
}

async function fetchConfigs() {
  const res = await llmApi.list()
  if (res.success) configs.value = res.data
  await llmStore.fetchConfigs()
}

onMounted(fetchConfigs)
</script>

<style scoped>
.page-layout { height: 100vh; display: flex; flex-direction: column; overflow-y: auto; background: var(--df-bg); }

.page-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 24px 14px;
  background: var(--df-surface); border-bottom: 1px solid var(--df-border);
  position: sticky; top: 0; z-index: 10;
}
.page-header h2 { font-size: 18px; font-weight: 600; margin-bottom: 3px; }
.subtitle { font-size: 12px; color: var(--df-text-muted); }

.type-tabs {
  display: flex; gap: 0;
  background: var(--df-surface); border-bottom: 1px solid var(--df-border);
  padding: 0 24px;
}
.type-tab {
  display: flex; align-items: center; gap: 6px;
  padding: 12px 18px; cursor: pointer;
  font-size: 13px; color: var(--df-text-muted);
  border-bottom: 2px solid transparent; transition: all 0.18s;
}
.type-tab:hover { color: var(--df-text); }
.type-tab.active { color: var(--df-primary); border-bottom-color: var(--df-primary); }
.tab-count {
  background: var(--df-surface2); padding: 1px 7px; border-radius: 10px;
  font-size: 11px; color: var(--df-text-muted);
}

.presets-section { padding: 16px 24px 0; }
.presets-title { font-size: 11px; color: var(--df-text-muted); font-weight: 600; text-transform: uppercase; margin-bottom: 10px; letter-spacing: 0.5px; }

.presets-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; }
.preset-card {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 14px; background: var(--df-surface);
  border: 1px solid var(--df-border); border-radius: 10px; cursor: pointer; transition: all 0.18s;
}
.preset-card:hover { border-color: var(--df-primary); background: rgba(91,138,240,0.04); }
.preset-icon { font-size: 22px; flex-shrink: 0; }
.preset-info { flex: 1; }
.preset-name { font-size: 13px; font-weight: 500; }
.preset-desc { font-size: 11px; color: var(--df-text-muted); margin-top: 1px; }

.config-list { padding: 16px 24px; display: flex; flex-direction: column; gap: 10px; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 50px; color: var(--df-text-muted); }

.config-card {
  position: relative; background: var(--df-surface);
  border: 1px solid var(--df-border); border-radius: 12px; padding: 16px 18px; transition: border-color 0.18s;
}
.config-card:hover { border-color: var(--df-primary); }
.config-badge { position: absolute; top: 12px; right: 12px; }

.config-header { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.config-icon { font-size: 26px; }
.config-title { flex: 1; }
.config-name { font-size: 14px; font-weight: 600; }
.config-model { font-size: 11px; color: var(--df-text-muted); margin-top: 1px; }

.config-details {
  display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px;
  margin-bottom: 12px; padding: 10px; background: var(--df-surface2); border-radius: 7px;
}
.detail-item { display: flex; gap: 8px; font-size: 12px; }
.dl { color: var(--df-text-muted); flex-shrink: 0; }
.detail-url { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.config-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
