/**
 * Shared API client — centralizes axios config, error handling, and common calls.
 */
import axios from 'axios'

const api = axios.create({
  timeout: 16000, // 16s, slightly longer than backend's 15s
  headers: { 'Content-Type': 'application/json' },
})

// Global error interceptor: normalize backend errors
api.interceptors.response.use(
  (res) => res,
  (err) => {
    const detail = err.response?.data?.detail
    if (detail) {
      err.message = typeof detail === 'string' ? detail : JSON.stringify(detail)
    }
    return Promise.reject(err)
  },
)

// ── Source APIs ──────────────────────────────────────
export const getSources = () => api.get('/api/sources')
export const addSource = (data) => api.post('/api/sources', data)
export const deleteSource = (id) => api.delete(`/api/sources/${id}`)
export const getSites = (sourceId, signal) =>
  api.get(`/api/sources/${sourceId}/sites`, { signal, timeout: 12000 })

// ── Site Content APIs ────────────────────────────────
export const getCategories = (params, signal) =>
  api.get('/api/site/categories', { params, signal })
export const searchVideos = (params, signal) =>
  api.get('/api/site/search', { params, signal })
// Aggregate search across all sources; backend caps each source at ~10s.
// Also the fallback path for searchStream on old TV WebViews, so keep the
// timeout above the backend's worst case.
export const searchAll = (params, signal) =>
  api.get('/api/search/all', { params, signal, timeout: 30000 })

/**
 * 流式聚合搜索：逐源回调 onEvent({type:'meta'|'source'|'done', ...})，
 * 结果一到就上屏，不用等最慢的源。
 * 旧电视内核不支持 fetch 流式读取（无 resp.body）时，自动回退到
 * /api/search/all 一次性接口，对外仍是相同的 onEvent 事件流。
 */
export async function searchStream(params, { onEvent, signal }) {
  const emitEvent = (raw) => {
    const line = raw.split('\n').find((l) => l.startsWith('data:'))
    if (!line) return
    try {
      onEvent(JSON.parse(line.slice(5).trim()))
    } catch { /* 忽略坏帧 */ }
  }

  let resp
  try {
    resp = await fetch(`/api/search/stream?${new URLSearchParams(params)}`, {
      signal,
      headers: { Accept: 'text/event-stream' },
    })
  } catch (e) {
    if (e.name === 'AbortError') throw e
    // 网络层就失败，直接走 JSON 接口拿一个明确的错误信息
    await fallback()
    return
  }

  if (!resp.ok || !resp.body) {
    await fallback()
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      emitEvent(buf.slice(0, idx))
      buf = buf.slice(idx + 2)
    }
  }
  if (buf.trim()) emitEvent(buf)

  async function fallback() {
    const { data } = await searchAll(params, signal)
    const results = data?.results || []
    onEvent({ type: 'meta', total: results.length, cached: !!data?.cached })
    for (const r of results) onEvent({ type: 'source', result: r })
    onEvent({ type: 'done', elapsed_ms: data?.elapsed_ms, cached: !!data?.cached })
  }
}
export const getDetail = (params, signal) => api.get('/api/site/detail', { params, signal })
export const resolvePlayUrl = (params, signal) => api.get('/api/site/resolve', { params, signal })
// 拼音首字母猜测：wmsj → [完美世界, ...]（后端本地标题索引，非实时）
export const searchSuggest = (wd, signal) =>
  api.get('/api/search/suggest', { params: { wd }, signal })

// ── History & Favorites ─────────────────────────────
export const getHistory = () => api.get('/api/history')
export const saveHistory = (data) => api.post('/api/history', data)
export const deleteHistory = (siteKey, vodId) =>
  api.delete(`/api/history/${siteKey}/${vodId}`)
export const clearHistory = () => api.delete('/api/history')

export const getFavorites = () => api.get('/api/favorites')
export const checkFavorite = (params, signal) =>
  api.get('/api/favorites/check', { params, signal })
export const addFavorite = (data) => api.post('/api/favorites', data)
export const deleteFavorite = (siteKey, vodId) =>
  api.delete(`/api/favorites/${siteKey}/${vodId}`)

// Legacy records: find which source/site serves a site_key
export const siteLookup = (params, signal) =>
  api.get('/api/site-lookup', { params, signal, timeout: 30000 })

// ── Health / Settings / Backup ───────────────────────
export const sourcesHealth = (signal) =>
  api.get('/api/sources/health', { signal, timeout: 60000 })
export const probeSites = (apiUrls, signal) =>
  api.post('/api/site/probe', { api_urls: apiUrls }, { signal, timeout: 20000 })
export const getSettings = (signal) => api.get('/api/settings', { signal })
export const updateSettings = (patch) => api.put('/api/settings', patch)
export const restoreBackup = (payload) => api.post('/api/restore', payload)

// ── Favorite updates (追更) ──────────────────────────
export const checkFavoriteUpdates = () =>
  api.post('/api/favorites/check-updates', {}, { timeout: 60000 })
export const clearFavoriteUpdate = (siteKey, vodId) =>
  api.post('/api/favorites/clear-update', { site_key: siteKey, vod_id: vodId })

export default api
