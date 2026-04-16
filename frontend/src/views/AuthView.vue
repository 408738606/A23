<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="brand">⚡ DocFusion</div>
      <h2>{{ mode === 'login' ? '欢迎登录' : '创建账号' }}</h2>
      <p class="sub">让文档理解和智能填写更高效</p>

      <el-form :model="form" label-position="top" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="昵称">
          <el-input v-model="form.displayName" placeholder="请输入昵称（可选）" />
        </el-form-item>
      </el-form>

      <el-button type="primary" class="submit-btn" :loading="submitting" @click="submitAuth">
        {{ mode === 'login' ? '登录' : '注册并登录' }}
      </el-button>

      <div class="switch-line">
        <span>{{ mode === 'login' ? '还没有账号？' : '已有账号？' }}</span>
        <a @click="mode = mode === 'login' ? 'register' : 'login'">
          {{ mode === 'login' ? '去注册' : '去登录' }}
        </a>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/index.js'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const mode = ref('login')
const submitting = ref(false)
const form = ref({ username: '', password: '', displayName: '' })

async function submitAuth() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  submitting.value = true
  try {
    const action = mode.value === 'login' ? authStore.login : authStore.register
    const payload = mode.value === 'login'
      ? { username: form.value.username, password: form.value.password }
      : { username: form.value.username, password: form.value.password, displayName: form.value.displayName }
    const res = await action(payload)
    if (res.success) {
      ElMessage.success(mode.value === 'login' ? '登录成功' : '注册成功')
      const redirect = route.query.redirect || '/chat'
      router.replace(String(redirect))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.auth-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(circle at 20% 20%, rgba(91,138,240,0.35), transparent 38%),
              radial-gradient(circle at 80% 80%, rgba(78,205,196,0.2), transparent 40%),
              var(--df-bg);
}
.auth-card {
  width: 420px;
  background: var(--df-surface);
  border: 1px solid var(--df-border);
  border-radius: 16px;
  box-shadow: var(--df-shadow);
  padding: 28px;
}
.brand {
  font-weight: 700;
  font-size: 18px;
  margin-bottom: 10px;
  color: var(--df-primary);
}
h2 {
  margin-bottom: 6px;
}
.sub {
  color: var(--df-text-muted);
  margin-bottom: 18px;
}
.submit-btn {
  width: 100%;
  margin-top: 8px;
}
.switch-line {
  margin-top: 14px;
  text-align: center;
  color: var(--df-text-muted);
}
.switch-line a {
  color: var(--df-primary);
  margin-left: 6px;
  cursor: pointer;
}
</style>
