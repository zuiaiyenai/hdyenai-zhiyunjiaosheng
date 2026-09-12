import { reactive } from 'vue'

const defaultBaseUrl = import.meta.env.VITE_API_BASE_URL
  || (import.meta.env.DEV ? 'http://localhost:8081' : '/api')

export const connection = reactive({
  baseUrl: localStorage.getItem('zyjs_api') || defaultBaseUrl,
  token: localStorage.getItem('token') || '',
  username: localStorage.getItem('username') || '访客'
})

export function saveConnection(baseUrl, token, username) {
  connection.baseUrl = baseUrl.replace(/\/$/, '')
  connection.token = token
  connection.username = username
  localStorage.setItem('zyjs_api', connection.baseUrl)
  localStorage.setItem('token', token)
  localStorage.setItem('username', username)
}

export async function request(path, options = {}) {
  const headers = { ...(options.headers || {}) }
  if (connection.token) headers.Authorization = `Bearer ${connection.token}`
  const response = await fetch(`${connection.baseUrl}${path}`, { ...options, headers })
  if (!response.ok) {
    const message = await response.text()
    const error = new Error(message || `请求失败：${response.status}`)
    error.status = response.status
    throw error
  }
  const type = response.headers.get('content-type') || ''
  if (type.includes('json')) return response.json()
  if (type.includes('audio') || type.includes('video') || type.includes('octet-stream')) return response.blob()
  return response.text()
}

export async function streamRequest(path, options = {}) {
  const headers = { ...(options.headers || {}) }
  if (connection.token) headers.Authorization = `Bearer ${connection.token}`
  const response = await fetch(`${connection.baseUrl}${path}`, { ...options, headers })
  if (!response.ok) {
    const raw = await response.text()
    let message = raw || `请求失败：${response.status}`
    try {
      const data = JSON.parse(raw)
      message = data.message || data.msg || message
    } catch {}
    const error = new Error(message)
    error.status = response.status
    throw error
  }
  return response
}

export async function submitTask(path, options = {}) {
  const submission = await request(path, options)
  if (!submission?.taskId) throw new Error('任务提交失败：未返回 taskId')
  const deadline = Date.now() + 16 * 60 * 1000
  let delay = 1000
  while (Date.now() < deadline) {
    const task = await request(`/api/tasks/${encodeURIComponent(submission.taskId)}`)
    if (task.status === 'SUCCESS') return task
    if (['FAILED', 'CANCELLED', 'TIMEOUT'].includes(task.status)) {
      throw new Error(task.errorMessage || `任务${task.status}`)
    }
    await new Promise(resolve => setTimeout(resolve, delay))
    delay = Math.min(5000, delay + 1000)
  }
  throw new Error('任务状态查询超时，请稍后重试')
}

export function taskResult(taskId) {
  return request(`/api/tasks/${encodeURIComponent(taskId)}/result`)
}

export function multipart(entries) {
  const data = new FormData()
  entries.forEach(([key, value]) => data.append(key, value))
  return data
}
