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
// Aggregate search across all sources; backend caps each source at ~8s
export const searchAll = (params, signal) =>
  api.get('/api/search/all', { params, signal, timeout: 20000 })
export const getDetail = (params, signal) => api.get('/api/site/detail', { params, signal })
export const resolvePlayUrl = (params, signal) => api.get('/api/site/resolve', { params, signal })

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
