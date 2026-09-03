<script setup>
import { ref } from 'vue'
import { connection, request, saveConnection } from '../services/api'

const props = defineProps({ open: Boolean })
const emit = defineEmits(['close', 'success', 'message'])
const username = ref('admin')
const password = ref('admin123')
const baseUrl = ref(connection.baseUrl)
const loading = ref(false)

async function login() {
  loading.value = true
  connection.baseUrl = baseUrl.value.replace(/\/$/, '')
  try {
    const data = await request('/user/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value, password: password.value })
    })
    const token = data.token || data.data?.token
    if (!token) throw new Error(data.msg || '登录响应中没有访问令牌')
    saveConnection(connection.baseUrl, token, data.username || username.value)
    emit('success')
  } catch (error) {
    emit('message', error.message, true)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div v-if="props.open" class="modal">
    <div class="login-card">
      <h2>登录智韵教声</h2><p>登录后即可调用语音教学服务</p>
      <input v-model="username" placeholder="用户名">
      <input v-model="password" type="password" placeholder="密码" @keyup.enter="login">
      <button class="primary login-submit" :disabled="loading" @click="login">{{ loading ? '登录中…' : '登录' }}</button>
      <div class="api-line"><input v-model="baseUrl" placeholder="后端接口地址"></div>
      <button class="link-btn" @click="emit('close')">仅预览界面</button>
    </div>
  </div>
</template>
