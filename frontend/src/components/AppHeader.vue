<script setup>
defineProps({
  page: { type: String, required: true },
  username: { type: String, default: '访客' },
  connected: Boolean
})
defineEmits(['navigate', 'login'])

const nav = [
  ['home', '首页'],
  ['voices', '文本转语音'],
  ['clone', '声音克隆'],
  ['assist', '语音辅助'],
  ['teacher', '教师辅助工具'],
  ['speaking', '口语练习'],
  ['report', '学习报告']
]
</script>

<template>
  <header class="topbar">
    <div class="brand"><span class="brand-mark">◖◗</span><span>智韵教声</span></div>
    <nav>
      <button v-for="[id, label] in nav" :key="id" class="nav-btn" :class="{ active: page === id }" @click="$emit('navigate', id)">{{ label }}</button>
    </nav>
    <button class="user" @click="$emit('login')">
      <span>{{ connected ? '● 服务已连接' : '● 预览模式' }}</span>
      <span class="avatar">👩‍🏫</span><span>{{ username }}</span>
    </button>
  </header>
</template>
