<template>
  <router-view v-if="isAuthPage" />
  <div v-else class="app-layout">
    <aside class="sidebar">
      <div class="logo">
        <span class="logo-icon">⚡</span>
        <span class="logo-text">DocFusion</span>
      </div>

      <nav class="nav-menu">
        <router-link
          v-for="item in navItems" :key="item.path" :to="item.path"
          class="nav-item" :class="{ active: $route.path === item.path }"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="sidebar-bottom">
        <div class="model-indicator">
          <span :class="['dot', defaultConfig ? 'active' : 'inactive']"></span>
          <span v-if="defaultConfig" class="model-name">当前模型：{{ defaultConfig.modelName }}</span>
          <span v-else class="model-name model-empty">未配置模型</span>
        </div>

        <el-popover placement="top-start" :width="320" trigger="click">
          <template #reference>
            <div class="profile-entry">
              <el-avatar :size="34" :src="authStore.user?.avatarUrl || ''">
                {{ avatarText }}
              </el-avatar>
              <div class="profile-name">
                <div>{{ authStore.user?.displayName || authStore.user?.username }}</div>
                <small>@{{ authStore.user?.username }}</small>
              </div>
              <el-icon><ArrowUp /></el-icon>
            </div>
          </template>
          <div class="profile-panel">
            <div class="panel-title">个人信息</div>
            <el-input v-model="profileForm.displayName" placeholder="昵称" />
            <el-input v-model="profileForm.avatarUrl" placeholder="头像 URL（可选）" style="margin-top:8px" />
            <el-input
              v-model="profileForm.bio"
              type="textarea"
              :rows="2"
              placeholder="个人简介（可选）"
              style="margin-top:8px"
            />
            <div class="panel-actions">
              <el-button size="small" @click="saveProfile">保存资料</el-button>
            </div>

            <div class="panel-title panel-gap">{{ t.generalSettings }}</div>
            <div class="gs-label">{{ t.theme }}</div>
            <div class="theme-grid">
              <div
                v-for="th in themes" :key="th.value"
                class="theme-card" :class="{ active: settingsStore.theme === th.value }"
                :style="{ '--tc': th.color, '--tc2': th.color2 }"
                @click="settingsStore.setTheme(th.value)"
              >
                <div class="theme-preview">
                  <div class="tp-bar"></div>
                  <div class="tp-content">
                    <div class="tp-sidebar"></div>
                    <div class="tp-main"></div>
                  </div>
                </div>
              </div>
            </div>
            <div class="gs-label">{{ t.language }}</div>
            <div class="lang-btns">
              <div class="lang-btn" :class="{ active: settingsStore.language === 'zh' }" @click="settingsStore.setLanguage('zh')">中文</div>
              <div class="lang-btn" :class="{ active: settingsStore.language === 'en' }" @click="settingsStore.setLanguage('en')">English</div>
            </div>
            <el-button size="small" text type="danger" style="margin-top:10px" @click="logout">
              退出登录
            </el-button>
          </div>
        </el-popover>
      </div>
    </aside>

    <main class="main-content">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useLlmStore, useSettingsStore, useAuthStore } from '@/store/index.js'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'

const llmStore = useLlmStore()
const settingsStore = useSettingsStore()
const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()

const { defaultConfig } = storeToRefs(llmStore)
const { t } = storeToRefs(settingsStore)

const navItems = computed(() => [
  { path: '/chat', icon: 'ChatLineRound', label: t.value.chat },
  { path: '/knowledge', icon: 'Folder', label: t.value.knowledge },
  { path: '/table-fill', icon: 'Grid', label: t.value.tableFill },
  { path: '/doc-format', icon: 'EditPen', label: t.value.docFormat || '文档排版' },
  { path: '/settings', icon: 'Setting', label: t.value.settings },
])

const themes = computed(() => [
  { value: 'dark', color: '#1a1d27', color2: '#5b8af0' },
  { value: 'light', color: '#f0f2f7', color2: '#4070e0' },
  { value: 'blue', color: '#0d1a35', color2: '#3d9fff' },
  { value: 'green', color: '#111f17', color2: '#3dcc7a' },
])

const profileForm = reactive({
  displayName: '',
  avatarUrl: '',
  bio: '',
})

const avatarText = computed(() => {
  const source = authStore.user?.displayName || authStore.user?.username || 'U'
  return source.slice(0, 1).toUpperCase()
})
const isAuthPage = computed(() => route.path === '/auth')

function syncProfileForm() {
  profileForm.displayName = authStore.user?.displayName || ''
  profileForm.avatarUrl = authStore.user?.avatarUrl || ''
  profileForm.bio = authStore.user?.bio || ''
}

async function saveProfile() {
  const res = await authStore.updateProfile({ ...profileForm })
  if (res.success) ElMessage.success('个人信息已更新')
}

async function logout() {
  await authStore.logout()
  router.replace('/auth')
}

let modelTimer = null
onMounted(async () => {
  settingsStore.init()
  await llmStore.fetchConfigs()
  await llmStore.fetchDefault()
  syncProfileForm()
  modelTimer = window.setInterval(() => llmStore.fetchDefault(), 10000)
})
watch(() => authStore.user, () => syncProfileForm(), { deep: true })
onBeforeUnmount(() => {
  if (modelTimer) window.clearInterval(modelTimer)
})
</script>

<style scoped>
.app-layout { display: flex; height: 100vh; overflow: hidden; }
.sidebar { width: 220px; min-width: 220px; background: var(--df-surface); border-right: 1px solid var(--df-border); display: flex; flex-direction: column; }
.logo { display: flex; align-items: center; gap: 10px; padding: 20px 20px 16px; border-bottom: 1px solid var(--df-border); }
.logo-icon { font-size: 22px; }
.logo-text { font-size: 18px; font-weight: 700; background: linear-gradient(135deg, var(--df-primary), var(--df-accent)); -webkit-background-clip: text; -webkit-text-fill-color: transparent; background-clip: text; letter-spacing: 1px; }
.nav-menu { flex: 1; padding: 12px 10px; display: flex; flex-direction: column; gap: 4px; }
.nav-item { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-radius: 8px; color: var(--df-text-muted); text-decoration: none; transition: all 0.18s; font-size: 14px; }
.nav-item:hover { background: var(--df-surface2); color: var(--df-text); }
.nav-item.active { background: rgba(91,138,240,0.15); color: var(--df-primary); }
.nav-item .el-icon { font-size: 16px; }

.sidebar-bottom { padding: 12px 14px 16px; border-top: 1px solid var(--df-border); display: flex; flex-direction: column; gap: 10px; }
.model-indicator { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--df-text-muted); padding: 0 2px; }
.dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.dot.active { background: var(--df-success); box-shadow: 0 0 6px var(--df-success); }
.dot.inactive { background: var(--df-warning); }
.model-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model-empty { color: var(--df-warning); }

.profile-entry {
  display: flex; align-items: center; gap: 10px; cursor: pointer;
  border: 1px solid var(--df-border); background: var(--df-surface2);
  border-radius: 10px; padding: 8px;
}
.profile-name { flex: 1; min-width: 0; }
.profile-name > div { font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.profile-name > small { color: var(--df-text-muted); }

.profile-panel { color: var(--df-text); }
.panel-title { font-size: 12px; font-weight: 600; color: var(--df-text-muted); margin-bottom: 8px; }
.panel-gap { margin-top: 14px; }
.panel-actions { margin-top: 8px; text-align: right; }
.gs-label { font-size: 12px; color: var(--df-text-muted); margin: 8px 0 6px; }
.theme-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.theme-card { border: 1px solid var(--df-border); border-radius: 8px; padding: 4px; cursor: pointer; }
.theme-card.active { border-color: var(--df-primary); }
.theme-preview { width: 100%; height: 36px; border-radius: 5px; overflow: hidden; background: var(--tc); }
.tp-bar { height: 8px; background: var(--tc2); opacity: 0.7; }
.tp-content { display: flex; height: 28px; }
.tp-sidebar { width: 12px; background: rgba(255,255,255,0.06); }
.tp-main { flex: 1; background: rgba(255,255,255,0.03); }
.lang-btns { display: flex; gap: 8px; }
.lang-btn { flex: 1; text-align: center; border: 1px solid var(--df-border); border-radius: 8px; padding: 6px; font-size: 12px; cursor: pointer; }
.lang-btn.active { color: var(--df-primary); border-color: var(--df-primary); }

.main-content { flex: 1; overflow: hidden; display: flex; flex-direction: column; }
</style>
