<script setup>
import { computed, onMounted, ref } from 'vue'
import AppHeader from './components/AppHeader.vue'
import LoginModal from './components/LoginModal.vue'
import { connection, multipart, request, streamRequest } from './services/api'

const page = ref('home')
const loginOpen = ref(!connection.token)
const toast = ref({ text: '', error: false, show: false })
const working = ref('')
const text = ref('')
const voice = ref('样本')
const readingStyle = ref('natural')
const speed = ref(1.05)
const audioUrl = ref('')
const streamStatus = ref('')
let liveAudioContext = null
const clone = ref({ file: null, promptText: '', promptLang: 'zh', text: '', lang: 'zh', audio: '' })
const summaryText = ref('')
const summaryResult = ref('摘要结果将显示在这里')
const readFile = ref(null)
const readResult = ref('等待上传文件')
const asrFile = ref(null)
const asrResult = ref('识别文本及评分会显示在这里')
const noteTitle = ref('')
const noteFile = ref(null)
const noteResult = ref('可上传录音生成文字笔记')
const notes = ref([])
const pptFile = ref(null)
const pptResult = ref('对话与总结内容')
const videoFile = ref(null)
const videoUrl = ref('')
const scenario = ref('supermarket')
const dialogue = ref('选择场景后点击“开始对话”，跟随系统进行口语练习。')
const speakingText = ref('')
const speakingFile = ref(null)
const speakingResult = ref('评测后展示流利度、发音和准确度')
const scores = ref([76, 79, 82, 78, 85, 88, 86])
const connected = computed(() => Boolean(connection.token))
const readingStyles = [
  { id: 'natural', name: '自然', speed: 1.05 },
  { id: 'gentle', name: '温柔', speed: 0.9 },
  { id: 'lively', name: '活泼', speed: 1.15 }
]
const scenarios = [
  ['supermarket', '🛒', '超市', '购物与结账情景'],
  ['airport', '✈️', '机场', '值机与问路情景'],
  ['restaurant', '🍽️', '餐厅', '点餐与交流情景']
]

function notify(message, error = false) {
  toast.value = { text: message, error, show: true }
  setTimeout(() => { toast.value.show = false }, 3200)
}
async function run(name, action) {
  working.value = name
  try { await action() } catch (error) {
    if (error.status === 401) loginOpen.value = true
    notify(error.message, true)
  } finally { working.value = '' }
}
function blobUrl(blob) { return URL.createObjectURL(blob) }
function fileFrom(event) { return event.target.files?.[0] || null }
function selectPage(next) {
  page.value = next
  if (next === 'report') loadReport()
}
function selectReadingStyle(style) {
  readingStyle.value = style.id
  speed.value = style.speed
  streamStatus.value = `已选择${style.name}风格，语速 ${style.speed}×`
}
function appendBytes(first, second) {
  const result = new Uint8Array(first.length + second.length)
  result.set(first)
  result.set(second, first.length)
  return result
}
function consumePcmChunk(context, state, chunk) {
  let bytes = chunk
  if (!state.headerParsed) {
    state.header = appendBytes(state.header, bytes)
    if (state.header.length < 44) return
    const view = new DataView(state.header.buffer, state.header.byteOffset, state.header.byteLength)
    state.sampleRate = view.getUint32(24, true) || 32000
    bytes = state.header.slice(44)
    state.headerParsed = true
  }
  bytes = appendBytes(state.remainder, bytes)
  const usableLength = bytes.length - (bytes.length % 2)
  if (!usableLength) {
    state.remainder = bytes
    return
  }
  const samples = new Float32Array(usableLength / 2)
  const view = new DataView(bytes.buffer, bytes.byteOffset, usableLength)
  for (let index = 0; index < samples.length; index += 1) {
    samples[index] = view.getInt16(index * 2, true) / 32768
  }
  state.remainder = bytes.slice(usableLength)
  const buffer = context.createBuffer(1, samples.length, state.sampleRate)
  buffer.copyToChannel(samples, 0)
  const source = context.createBufferSource()
  source.buffer = buffer
  source.connect(context.destination)
  const startAt = Math.max(context.currentTime + 0.08, state.nextStartTime)
  source.start(startAt)
  state.nextStartTime = startAt + buffer.duration
}
function completeStreamingWav(chunks) {
  const length = chunks.reduce((sum, chunk) => sum + chunk.length, 0)
  const bytes = new Uint8Array(length)
  let offset = 0
  chunks.forEach(chunk => {
    bytes.set(chunk, offset)
    offset += chunk.length
  })
  if (length >= 44 && String.fromCharCode(...bytes.slice(0, 4)) === 'RIFF') {
    const view = new DataView(bytes.buffer)
    view.setUint32(4, length - 8, true)
    view.setUint32(40, length - 44, true)
  }
  return new Blob([bytes], { type: 'audio/wav' })
}
async function synthesize() {
  if (!text.value.trim()) throw new Error('请输入需要合成的文本')
  if (liveAudioContext) await liveAudioContext.close()
  liveAudioContext = new (window.AudioContext || window.webkitAudioContext)()
  await liveAudioContext.resume()
  streamStatus.value = '正在连接语音模型…'
  const response = await streamRequest('/voice/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: text.value, voice: voice.value, speed: +speed.value })
  })
  if (!response.body) {
    const blob = await response.blob()
    if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
    audioUrl.value = blobUrl(blob)
    streamStatus.value = '生成完成'
    notify('语音生成完成')
    return
  }
  const reader = response.body.getReader()
  const chunks = []
  const playback = {
    header: new Uint8Array(),
    headerParsed: false,
    remainder: new Uint8Array(),
    sampleRate: 32000,
    nextStartTime: liveAudioContext.currentTime + 0.1
  }
  let received = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    chunks.push(value)
    received += value.length
    consumePcmChunk(liveAudioContext, playback, value)
    streamStatus.value = `正在生成并播放 · ${(received / 1024).toFixed(0)} KB`
  }
  const blob = completeStreamingWav(chunks)
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  audioUrl.value = blobUrl(blob)
  streamStatus.value = '生成完成，可重新播放或下载'
  notify('语音生成完成')
}
async function cloneVoice() {
  if (!clone.value.file || !clone.value.promptText || !clone.value.text) throw new Error('请完整填写参考音频、参考文本和合成文本')
  const blob = await request('/sound_clone/upload', { method: 'POST', body: multipart([
    ['prompt_text', clone.value.promptText], ['prompt_lang', clone.value.promptLang],
    ['text', clone.value.text], ['text_lang', clone.value.lang], ['audioFile', clone.value.file]
  ]) })
  clone.value.audio = blobUrl(blob)
  notify('克隆语音生成完成')
}
async function summarize() {
  const data = await request('/accessibility/generate-summary', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: summaryText.value })
  })
  summaryResult.value = data.summary || data.content || JSON.stringify(data, null, 2)
}
async function parseFile() {
  if (!readFile.value) throw new Error('请选择文件')
  const data = await request('/accessibility/read-file', { method: 'POST', body: multipart([['file', readFile.value]]) })
  readResult.value = data.text || data.content || JSON.stringify(data, null, 2)
}
async function transcribe() {
  if (!asrFile.value) throw new Error('请选择音频文件')
  const data = await request('/asr/transcribe', { method: 'POST', body: multipart([['file', asrFile.value], ['language', 'zh']]) })
  asrResult.value = `识别文本：${data.text || ''}\n流利度：${data.fluency ?? '-'}\n发音：${data.pronunciation ?? '-'}\n准确度：${data.accuracy ?? '-'}`
}
async function saveVoiceNote() {
  if (!noteFile.value) throw new Error('请选择语音笔记录音')
  const data = await request('/accessibility/voice-note', {
    method: 'POST',
    body: multipart([['audio', noteFile.value], ['title', noteTitle.value || '未命名笔记']])
  })
  noteResult.value = data.text || data.content || data.msg || JSON.stringify(data, null, 2)
  notify('语音笔记已保存')
  await loadNotes()
}
async function loadNotes() {
  const data = await request('/accessibility/voice-notes')
  notes.value = Array.isArray(data) ? data : (data.notes || data.data || [])
}
async function handlePpt(read = false) {
  if (!pptFile.value) throw new Error('请选择 PPT 文件')
  const data = await request(read ? '/accessibility/read-ppt' : '/courseware/summary', { method: 'POST', body: multipart([['file', pptFile.value]]) })
  pptResult.value = typeof data === 'string' ? data : (data.text || data.summary || JSON.stringify(data, null, 2))
}
async function processVideo() {
  if (!videoFile.value) throw new Error('请选择视频')
  const data = await request('/video_voice_swap/process', { method: 'POST', body: multipart([['video', videoFile.value], ['voiceType', voice.value]]) })
  if (data instanceof Blob) videoUrl.value = blobUrl(data)
  notify('视频处理完成')
}
async function startDialogue() {
  const data = await request('/speaking_practice/dialogue/start', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ scenarioId: scenario.value })
  })
  dialogue.value = data.text || data.message || data.dialogue || '对话已经开始，请根据提示练习。'
}
async function evaluate() {
  if (!speakingFile.value || !speakingText.value.trim()) throw new Error('请填写参考文本并上传录音')
  const data = await request('/speaking_practice/evaluate', { method: 'POST', body: multipart([
    ['file', speakingFile.value], ['text', speakingText.value], ['mode', 'standard'], ['language', 'zh']
  ]) })
  speakingResult.value = typeof data === 'string' ? data : JSON.stringify(data, null, 2)
}
async function loadReport() {
  try {
    const data = await request('/speaking_practice/history')
    const rows = Array.isArray(data) ? data : (data.history || data.data || [])
    if (rows.length) scores.value = rows.slice(-7).map(item => +(item.score || item.fluency || 75))
  } catch {}
}
function onVideo(event) {
  videoFile.value = fileFrom(event)
  if (videoFile.value) videoUrl.value = blobUrl(videoFile.value)
}
onMounted(() => {
  if (connected.value) loadReport()
})
</script>

<template>
  <AppHeader :page="page" :username="connection.username" :connected="connected" @navigate="selectPage" @login="loginOpen = true" />
  <main>
    <section v-show="page === 'home'" class="page">
      <div class="hero-grid">
        <aside class="side"><h3>使用说明</h3><div class="rank"><b>1</b><div>输入任意文字<small>输入框内容才是最终朗读内容</small></div></div><div class="rank"><b>2</b><div>选择参考音色<small>参考音频只决定说话人的声音</small></div></div><div class="rank"><b>3</b><div>生成并播放<small>生成后可重新播放或下载</small></div></div><button class="more-button" @click="selectPage('voices')">查看可用音色 →</button></aside>
        <div class="workspace">
          <textarea v-model="text" class="editor" maxlength="5000" aria-label="要合成的文字" placeholder="请输入希望语音朗读的文字，例如：欢迎使用智韵教声。"></textarea>
          <div class="editor-foot"><span>{{ text.length }} / 5000</span><span>输入的文字将作为实际合成内容</span></div>
          <div class="settings">
            <div class="box"><h4>选择声音</h4><label>参考音色<select v-model="voice"><option value="样本">自然女声（推荐）</option><option value="红豆生南国">古诗女声</option></select></label><p class="field-help">GPT-SoVITS 需要一段本地参考音频来确定说话人音色，但不会把它当作要朗读的内容。</p><div class="chips"><button v-for="style in readingStyles" :key="style.id" class="chip" :class="{ active: readingStyle === style.id }" :aria-pressed="readingStyle === style.id" @click="selectReadingStyle(style)">{{ style.name }}</button></div></div>
            <div class="box"><h4>朗读设置</h4><label>语速 <b>{{ Number(speed).toFixed(1) }}×</b><input v-model="speed" type="range" min=".5" max="2" step=".1" aria-label="语速"></label><p class="field-help">当前 GPT-SoVITS v2 接口支持语速调节；音调和韵律不提供独立参数。</p></div>
          </div>
          <div class="actions"><span v-if="streamStatus" class="stream-status">{{ streamStatus }}</span><a v-if="audioUrl" :href="audioUrl" class="secondary download" download="speech.wav">下载音频</a><button class="primary" :disabled="working === 'tts'" @click="run('tts', synthesize)">{{ working === 'tts' ? '生成中…' : '生成语音' }}</button></div>
          <audio v-if="audioUrl" :src="audioUrl" class="audio" controls></audio>
        </div>
      </div>
    </section>

    <section v-show="page === 'voices'" class="page panel">
      <h2 class="panel-title">可用音色</h2><div class="panel-body">
        <div class="featured"><div class="voice-card"><span>👩🏻</span><div><b>自然女声</b><small>连贯自然，推荐用于课文与讲稿朗读</small></div><button class="use-voice" @click="voice = '样本'; selectPage('home'); notify('已选择自然女声')">使用</button></div><div class="voice-card"><span>👩🏻</span><div><b>古诗女声</b><small>节奏舒缓，适合诗词朗读</small></div><button class="use-voice" @click="voice = '红豆生南国'; selectPage('home'); notify('已选择古诗女声')">使用</button></div></div>
        <p class="empty-hint">暂无其他已配置的 GPT-SoVITS 音色。要增加音色，请先提供参考音频及其准确朗读文本。</p>
      </div>
    </section>

    <section v-show="page === 'clone'" class="page panel">
      <h2 class="panel-title">声音克隆</h2><div class="panel-body two">
        <div><h3>上传参考音频</h3><label class="drop">建议使用 3–10 秒、环境安静的人声<input type="file" accept="audio/*" @change="clone.file = fileFrom($event)"></label><input v-model="clone.promptText" class="wide" placeholder="参考音频中说出的内容"><select v-model="clone.promptLang" class="wide"><option value="zh">中文</option><option value="en">英文</option></select></div>
        <div><h3>合成内容</h3><textarea v-model="clone.text" class="editor" placeholder="输入希望克隆音色朗读的文本"></textarea><button class="primary" @click="run('clone', cloneVoice)">生成克隆语音</button><audio v-if="clone.audio" :src="clone.audio" class="audio" controls></audio></div>
      </div>
    </section>

    <section v-show="page === 'assist'" class="page three">
      <article class="panel"><h2 class="panel-title">文本朗读</h2><div class="panel-body"><textarea v-model="summaryText" class="editor" placeholder="输入大文本内容……"></textarea><button class="primary" @click="run('summary', summarize)">智能摘要</button><pre class="result">{{ summaryResult }}</pre></div></article>
      <article class="panel"><h2 class="panel-title">文件朗读</h2><div class="panel-body"><label class="drop">上传 TXT / DOCX<input type="file" @change="readFile = fileFrom($event)"></label><button class="primary" @click="run('read', parseFile)">解析文件</button><pre class="result">{{ readResult }}</pre></div></article>
      <article class="panel"><h2 class="panel-title">语音识别</h2><div class="panel-body"><label class="drop">上传录音<input type="file" accept="audio/*" @change="asrFile = fileFrom($event)"></label><button class="primary" @click="run('asr', transcribe)">开始转写</button><pre class="result">{{ asrResult }}</pre></div></article>
    </section>
    <section v-show="page === 'assist'" class="page panel mt">
      <h2 class="panel-title">语音笔记</h2>
      <div class="panel-body notes-grid">
        <div>
          <input v-model="noteTitle" class="wide" placeholder="笔记标题">
          <label class="drop compact-drop">上传录音生成文字笔记<input type="file" accept="audio/*" @change="noteFile = fileFrom($event)"></label>
          <div class="actions"><button class="secondary" @click="run('notes', loadNotes)">查看笔记列表</button><button class="primary" @click="run('note', saveVoiceNote)">保存笔记</button></div>
        </div>
        <div>
          <pre class="result">{{ noteResult }}</pre>
          <div v-if="notes.length" class="note-list"><p v-for="(item, index) in notes" :key="item.id || index"><b>{{ item.title || `笔记 ${index + 1}` }}</b><span>{{ item.text || item.content || '' }}</span></p></div>
        </div>
      </div>
    </section>

    <section v-show="page === 'teacher'" class="page two">
      <article class="panel"><h2 class="panel-title">PPT 课件总结</h2><div class="panel-body"><label class="drop">上传 PPT / PPTX<input type="file" accept=".ppt,.pptx" @change="pptFile = fileFrom($event)"></label><div class="actions"><button class="primary" @click="run('ppt', () => handlePpt(false))">生成总结</button><button class="secondary" @click="run('ppt-read', () => handlePpt(true))">提取文本</button></div><pre class="result">{{ pptResult }}</pre></div></article>
      <article class="panel"><h2 class="panel-title">视频换声</h2><div class="panel-body"><input type="file" accept="video/*" @change="onVideo"><video :src="videoUrl" class="video" controls></video><button class="primary" @click="run('video', processVideo)">开始处理</button></div></article>
    </section>

    <section v-show="page === 'speaking'" class="page">
      <article class="panel"><h2 class="panel-title">定制场景对话</h2><div class="panel-body"><div class="three"><button v-for="[id, icon, name, desc] in scenarios" :key="id" class="scenario" :class="{ active: scenario === id }" @click="scenario = id"><b>{{ icon }}</b><h3>{{ name }}</h3><span>{{ desc }}</span></button></div><div class="conversation"><p class="bubble">{{ dialogue }}</p></div><div class="actions"><button class="primary" @click="run('dialogue', startDialogue)">开始对话</button></div></div></article>
      <article class="panel mt"><h2 class="panel-title">口语评测</h2><div class="panel-body two"><div><textarea v-model="speakingText" class="editor" placeholder="输入本次朗读的参考文本"></textarea><input type="file" accept="audio/*" @change="speakingFile = fileFrom($event)"><button class="primary" @click="run('evaluate', evaluate)">开始评测</button></div><pre class="result">{{ speakingResult }}</pre></div></article>
    </section>

    <section v-show="page === 'report'" class="page">
      <h1 class="report-title">我的近一周口语测评报告</h1><div class="two"><article class="panel"><h2 class="panel-title">平均评测分数</h2><div class="chart"><i v-for="(score, index) in scores" :key="index" :style="{ height: score + '%' }"><small>07/{{ 22 + index }}</small></i></div></article><article class="panel"><h2 class="panel-title">正确率趋势</h2><div class="chart"><i v-for="(score, index) in scores" :key="index" :style="{ height: Math.min(98, score + 5) + '%' }"><small>07/{{ 22 + index }}</small></i></div></article></div>
      <article class="panel mt"><h2 class="panel-title">专项表现</h2><div class="metrics"><p><span>平均流利度</span><b>82 分</b></p><p><span>发音准确度</span><b>87%</b></p><p><span>完成练习</span><b>{{ scores.length }} 次</b></p><p><span>提升建议</span><b>加强重音与句尾语调</b></p></div></article>
    </section>
  </main>
  <LoginModal :open="loginOpen" @close="loginOpen = false" @success="loginOpen = false; notify('登录成功')" @message="notify" />
  <div class="toast" :class="{ show: toast.show, error: toast.error }">{{ toast.text }}</div>
</template>
