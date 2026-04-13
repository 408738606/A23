<template>
  <div class="app-layout">
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
        <div class="model-indicator" v-if="defaultConfig">
          <span class="dot active"></span>
          <span class="model-name">{{ defaultConfig.modelName }}</span>
        </div>
        <div class="model-indicator" v-else>
          <span class="dot inactive"></span>
          <span class="model-name" style="color:var(--df-warning)">未配置模型</span>
        </div>

        <div class="general-settings-btn" @click="showSettings = true">
          <el-icon><Tools /></el-icon>
          <span>{{ t.generalSettings }}</span>
        </div>
      </div>
    </aside>

    <main class="main-content">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>

    <!-- General Settings Dialog -->
    <el-dialog v-model="showSettings" :title="t.generalSettings" width="460px" :close-on-click-modal="true">
      <div class="gs-body">
        <div class="gs-section">
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
              <span class="theme-name">{{ th.label }}</span>
              <el-icon v-if="settingsStore.theme === th.value" class="theme-check"><Select /></el-icon>
            </div>
          </div>
        </div>

        <div class="gs-section">
          <div class="gs-label">{{ t.language }}</div>
          <div class="lang-btns">
            <div
              class="lang-btn" :class="{ active: settingsStore.language === 'zh' }"
              @click="settingsStore.setLanguage('zh')"
            >🇨🇳 中文</div>
            <div
              class="lang-btn" :class="{ active: settingsStore.language === 'en' }"
              @click="settingsStore.setLanguage('en')"
            >🇺🇸 English</div>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useLlmStore, useSettingsStore } from '@/store/index.js'
import { storeToRefs } from 'pinia'

const llmStore = useLlmStore()
const settingsStore = useSettingsStore()
const { defaultConfig } = storeToRefs(llmStore)
const { t } = storeToRefs(settingsStore)
const showSettings = ref(false)

const navItems = computed(() => [
  { path: '/chat', icon: 'ChatLineRound', label: t.value.chat },
  { path: '/knowledge', icon: 'Folder', label: t.value.knowledge },
  { path: '/table-fill', icon: 'Grid', label: t.value.tableFill },
  { path: '/settings', icon: 'Setting', label: t.value.settings },
])

const themes = computed(() => [
  { value: 'dark', label: t.value.darkTheme, color: '#1a1d27', color2: '#5b8af0' },
  { value: 'light', label: t.value.lightTheme, color: '#f0f2f7', color2: '#4070e0' },
  { value: 'blue', label: t.value.blueTheme, color: '#0d1a35', color2: '#3d9fff' },
  { value: 'green', label: t.value.greenTheme, color: '#111f17', color2: '#3dcc7a' },
])

onMounted(() => {
  settingsStore.init()
  llmStore.fetchConfigs()
  llmStore.fetchDefault()
})
</script>

<style scoped>
.app-layout { display: flex; height: 100vh; overflow: hidden; }

.sidebar {
  width: 220px; min-width: 220px;
  background: var(--df-surface);
  border-right: 1px solid var(--df-border);
  display: flex; flex-direction: column;
}

.logo {
  display: flex; align-items: center; gap: 10px;
  padding: 20px 20px 16px;
  border-bottom: 1px solid var(--df-border);
}
.logo-icon { font-size: 22px; }
.logo-text {
  font-size: 18px; font-weight: 700;
  background: linear-gradient(135deg, var(--df-primary), var(--df-accent));
  -webkit-background-clip: text; -webkit-text-fill-color: transparent;
  background-clip: text; letter-spacing: 1px;
}

.nav-menu { flex: 1; padding: 12px 10px; display: flex; flex-direction: column; gap: 4px; }

.nav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border-radius: 8px;
  color: var(--df-text-muted); text-decoration: none;
  transition: all 0.18s; font-size: 14px;
}
.nav-item:hover { background: var(--df-surface2); color: var(--df-text); }
.nav-item.active { background: rgba(91,138,240,0.15); color: var(--df-primary); }
.nav-item .el-icon { font-size: 16px; }

.sidebar-bottom {
  padding: 12px 14px 16px;
  border-top: 1px solid var(--df-border);
  display: flex; flex-direction: column; gap: 8px;
}

.model-indicator { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--df-text-muted); padding: 0 2px; }
.dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.dot.active { background: var(--df-success); box-shadow: 0 0 6px var(--df-success); }
.dot.inactive { background: var(--df-warning); }
.model-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.general-settings-btn {
  display: flex; align-items: center; gap: 8px;
  padding: 9px 14px; border-radius: 8px;
  cursor: pointer; color: var(--df-text-muted);
  transition: all 0.18s; font-size: 13px;
  border: 1px dashed var(--df-border);
}
.general-settings-btn:hover { background: var(--df-surface2); color: var(--df-text); border-color: var(--df-primary); }

.main-content { flex: 1; overflow: hidden; display: flex; flex-direction: column; }

/* General Settings Dialog */
.gs-body { display: flex; flex-direction: column; gap: 24px; }
.gs-section { display: flex; flex-direction: column; gap: 12px; }
.gs-label { font-size: 13px; font-weight: 600; color: var(--df-text-muted); text-transform: uppercase; letter-spacing: 0.5px; }

.theme-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }

.theme-card {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  padding: 10px 6px; border-radius: 10px; cursor: pointer;
  border: 2px solid var(--df-border); transition: all 0.18s; position: relative;
}
.theme-card:hover { border-color: var(--df-primary); transform: translateY(-2px); }
.theme-card.active { border-color: var(--df-primary); background: rgba(91,138,240,0.08); }

.theme-preview {
  width: 64px; height: 44px; border-radius: 6px; overflow: hidden;
  background: var(--tc); border: 1px solid rgba(255,255,255,0.1);
}
.tp-bar { height: 10px; background: var(--tc2); opacity: 0.7; }
.tp-content { display: flex; height: 34px; }
.tp-sidebar { width: 18px; background: rgba(255,255,255,0.06); }
.tp-main { flex: 1; background: rgba(255,255,255,0.03); }

.theme-name { font-size: 11px; color: var(--df-text-muted); }
.theme-check { color: var(--df-primary); font-size: 14px; position: absolute; top: 4px; right: 4px; }

.lang-btns { display: flex; gap: 10px; }
.lang-btn {
  flex: 1; padding: 10px; text-align: center; border-radius: 8px;
  border: 2px solid var(--df-border); cursor: pointer;
  transition: all 0.18s; font-size: 14px; color: var(--df-text-muted);
}
.lang-btn:hover { border-color: var(--df-primary); color: var(--df-text); }
.lang-btn.active { border-color: var(--df-primary); color: var(--df-primary); background: rgba(91,138,240,0.08); }
</style>
