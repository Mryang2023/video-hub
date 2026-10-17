<template>
  <div class="h-screen flex flex-col">
    <!-- Header -->
    <header class="h-14 bg-dark-800 border-b border-dark-600 flex items-center px-3 sm:px-4 gap-2 sm:gap-4 shrink-0 z-50">
      <button v-show="!(isMobile && searchOpen)" @click="sidebarOpen = !sidebarOpen" class="text-gray-400 hover:text-white transition p-1">
        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16"/>
        </svg>
      </button>

      <router-link v-show="!(isMobile && searchOpen)" to="/" class="flex items-center gap-2 shrink-0">
        <span class="text-lg font-bold bg-gradient-to-r from-accent-light to-accent bg-clip-text text-transparent">
          VideoHub
        </span>
      </router-link>

      <!-- Source Selector -->
      <div v-show="!(isMobile && searchOpen)" class="relative" ref="sourceDropdown">
        <button @click="showSourceMenu = !showSourceMenu"
          class="flex items-center gap-1.5 px-2 sm:px-3 py-1.5 bg-dark-600 rounded-lg text-sm hover:bg-dark-500 transition">
          <span class="text-gray-300 truncate max-w-[100px] sm:max-w-none">{{ currentSource?.name || '选择源' }}</span>
          <svg class="w-3.5 h-3.5 text-gray-500 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"/>
          </svg>
        </button>
        <div v-if="showSourceMenu"
          class="absolute top-full left-0 mt-1 w-64 bg-dark-700 border border-dark-500 rounded-lg shadow-xl py-1 z-50">
          <div class="flex items-center justify-between px-3 py-1.5">
            <span class="text-[10px] text-gray-500">影视源</span>
            <button @click="checkHealth" :disabled="healthChecking"
              class="text-[10px] text-accent-light hover:text-accent disabled:opacity-40 transition">
              {{ healthChecking ? '检测中...' : '一键检测' }}
            </button>
          </div>
          <div v-for="s in sources" :key="s.id"
            @click="selectSource(s); showSourceMenu = false"
            class="px-3 py-2 hover:bg-dark-600 cursor-pointer flex items-center justify-between">
            <div class="flex items-center gap-2 min-w-0">
              <span class="w-1.5 h-1.5 rounded-full shrink-0" :class="healthDot(s.id)"
                :title="healthTitle(s.id)"></span>
              <div class="min-w-0">
                <div class="text-sm font-medium truncate">{{ s.name }}</div>
                <div class="text-xs text-gray-500">{{ healthText(s.id, s.site_count) }}</div>
              </div>
            </div>
            <button @click.stop="removeSource(s.id)"
              class="text-gray-500 hover:text-red-400 text-xs p-1 shrink-0">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
              </svg>
            </button>
          </div>
          <div class="border-t border-dark-500 mt-1 pt-1">
            <button @click="showAddSource = true; showSourceMenu = false"
              class="w-full px-3 py-2 text-sm text-accent-light hover:bg-dark-600 flex items-center gap-2">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/>
              </svg>
              添加影视源
            </button>
          </div>
        </div>
      </div>

      <!-- Search -->
      <div class="flex-1 max-w-xl mx-auto flex items-center gap-2 min-w-0">
        <template v-if="!isMobile || searchOpen">
          <div class="relative flex-1 min-w-0">
            <input ref="searchInput" v-model="searchQuery" @keyup.enter="doSearch"
              @focus="searchFocused = true" @blur="onSearchBlur"
              type="text" placeholder="搜索视频..."
              class="w-full bg-dark-600 border border-dark-500 rounded-lg px-4 py-1.5 pl-9 text-sm
                     focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent/30 transition" />
            <svg class="w-4 h-4 text-gray-500 absolute left-3 top-1/2 -translate-y-1/2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/>
            </svg>
            <!-- Search history dropdown -->
            <div v-if="searchFocused && searchHistory.length"
              class="absolute top-full left-0 right-0 mt-1 bg-dark-700 border border-dark-500 rounded-lg shadow-xl py-1 z-50">
              <div class="flex items-center justify-between px-3 py-1">
                <span class="text-[10px] text-gray-500">搜索历史</span>
                <button @mousedown.prevent="clearSearchHistory" class="text-[10px] text-gray-500 hover:text-red-400">清空</button>
              </div>
              <div v-for="(h, i) in searchHistory" :key="h"
                class="flex items-center justify-between px-3 py-1.5 hover:bg-dark-600 cursor-pointer group"
                @mousedown.prevent="applySearchHistory(h)">
                <span class="text-sm text-gray-300 truncate">{{ h }}</span>
                <button @mousedown.prevent.stop="removeSearchHistory(i)"
                  class="text-gray-600 hover:text-red-400 p-0.5 opacity-0 group-hover:opacity-100 transition">
                  <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
                  </svg>
                </button>
              </div>
            </div>
          </div>
          <!-- Search scope toggle -->
          <div class="flex bg-dark-600 rounded-lg overflow-hidden shrink-0 text-xs">
            <button @click="searchMode = 'site'" title="只搜索当前站点"
              class="px-2 py-1.5 transition"
              :class="searchMode === 'site' ? 'bg-accent text-white' : 'text-gray-400 hover:text-white'">
              本源
            </button>
            <button @click="searchMode = 'all'" title="并发搜索所有源"
              class="px-2 py-1.5 transition"
              :class="searchMode === 'all' ? 'bg-accent text-white' : 'text-gray-400 hover:text-white'">
              全源
            </button>
          </div>
        </template>
        <button v-else @click="openSearch" title="搜索"
          class="text-gray-400 hover:text-white transition p-1 shrink-0">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/>
          </svg>
        </button>
      </div>

      <!-- History link / mobile: close search -->
      <button v-if="isMobile && searchOpen" @click="closeSearch" title="关闭搜索"
        class="text-gray-400 hover:text-white transition p-1 shrink-0">
        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
        </svg>
      </button>
      <button v-else @click="goPage('/history')" title="观看历史"
        class="text-gray-400 hover:text-white transition p-1 shrink-0">
        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"/>
        </svg>
      </button>
    </header>

    <!-- APP 新版本横幅 -->
    <div v-if="appUpdate" class="bg-accent/20 border-b border-accent/30 px-3 py-2 flex items-center gap-2 text-sm shrink-0">
      <span class="text-accent-light truncate">🎉 新版本 v{{ appUpdate.version }} 可用{{ appUpdate.notes ? '：' + appUpdate.notes : '' }}</span>
      <button @click="doAppUpdate"
        class="ml-auto px-2.5 py-1 bg-accent rounded-lg text-white text-xs shrink-0">下载更新</button>
      <button @click="appUpdate = null" class="text-gray-400 hover:text-white text-xs px-1 shrink-0">✕</button>
    </div>

    <!-- Body -->
    <div class="flex flex-1 overflow-hidden">
      <!-- Mobile overlay when sidebar is open -->
      <div v-if="sidebarOpen && isMobile" class="fixed inset-0 bg-black/40 z-30 sm:hidden"
        @click="sidebarOpen = false"></div>

      <!-- Sidebar -->
      <transition name="slide">
        <aside v-show="sidebarOpen"
          class="w-56 bg-dark-800 border-r border-dark-600 overflow-y-auto shrink-0 z-40"
          :class="isMobile ? 'fixed top-14 bottom-0' : ''">
          <!-- Loading sites -->
          <div v-if="loadingSites" class="flex flex-col items-center justify-center py-8 gap-2">
            <div class="w-5 h-5 border-2 border-accent border-t-transparent rounded-full animate-spin"></div>
            <span class="text-xs text-gray-500">加载站点中...</span>
          </div>
          <!-- Sites loaded -->
          <div v-else-if="sites.length" class="py-2">
            <div class="px-3 py-1.5 text-xs text-gray-500 font-semibold uppercase tracking-wider flex items-center justify-between">
              <span>可用站点</span>
              <button @click="probeCurrentSites" :disabled="probing || !searchableSites.length"
                class="text-[10px] font-normal text-accent-light hover:text-accent disabled:opacity-40 transition">
                {{ probing ? '检测中…' : '检测站点' }}
              </button>
            </div>
            <div v-for="site in searchableSites" :key="site.key"
              @click="selectSite(site)"
              class="px-3 py-2 text-sm cursor-pointer transition flex items-center gap-2"
              :class="currentSite?.key === site.key
                ? 'bg-accent/15 text-accent-light border-r-2 border-accent'
                : 'text-gray-300 hover:bg-dark-700 hover:text-white'">
              <span class="w-2 h-2 rounded-full shrink-0" :class="siteDotClass(site)"></span>
              <span class="truncate">{{ site.name }}</span>
            </div>
            <div v-if="searchableSites.length === 0"
              class="px-3 py-4 text-xs text-gray-500 text-center leading-relaxed">
              当前源无可用站点<br>(仅支持标准 API 站点)
            </div>
            <div v-else-if="searchableSites.length < sites.length"
              class="px-3 py-2 text-[10px] text-gray-600 text-center">
              已过滤 {{ sites.length - searchableSites.length }} 个不支持的站点
            </div>
          </div>
          <!-- Error loading sites -->
          <div v-else-if="sitesError" class="p-4 text-center">
            <svg class="w-8 h-8 text-red-400/50 mx-auto mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"/>
            </svg>
            <p class="text-xs text-red-400/80 mb-2 leading-relaxed">{{ sitesError }}</p>
            <button @click="sitesCache.delete(currentSource?.id); selectSource(currentSource)"
              class="px-3 py-1.5 bg-dark-600 hover:bg-dark-500 rounded-lg text-xs text-gray-300 transition">
              重试
            </button>
          </div>
          <!-- No source selected / no sources -->
          <div v-else class="p-4 text-sm text-gray-500 text-center">
            {{ sources.length ? '请先选择源' : '请先添加影视源' }}
          </div>

          <!-- Favorites & settings entries (always visible) -->
          <div class="border-t border-dark-600 mt-2 pt-2 pb-3 px-2 space-y-0.5">
            <button @click="goPage('/favorites')"
              class="w-full px-3 py-2 text-sm rounded-lg flex items-center gap-2 transition"
              :class="route.path === '/favorites'
                ? 'bg-accent/15 text-accent-light'
                : 'text-gray-300 hover:bg-dark-700 hover:text-white'">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                  d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.196-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118L2.01 9.411c-.783-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"/>
              </svg>
              <span>我的收藏</span>
            </button>
            <button @click="goPage('/settings')"
              class="w-full px-3 py-2 text-sm rounded-lg flex items-center gap-2 transition"
              :class="route.path === '/settings'
                ? 'bg-accent/15 text-accent-light'
                : 'text-gray-300 hover:bg-dark-700 hover:text-white'">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                  d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"/>
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
              </svg>
              <span>设置</span>
            </button>
          </div>
        </aside>
      </transition>

      <!-- Main Content -->
      <main class="flex-1 overflow-y-auto">
        <router-view
          :current-source="currentSource"
          :current-site="currentSite"
          :sites="sites"
          :search-query="searchQuery"
          :search-mode="searchMode"
          @search="doSearch"
          @play="goToPlayer"
          @select-site="selectSite"
          @toast="handleToast"
        />
      </main>
    </div>

    <!-- Mobile bottom nav -->
    <nav v-if="isMobile" class="bg-dark-800 border-t border-dark-600 flex shrink-0 z-40">
      <button v-for="item in mobileNav" :key="item.path" @click="goPage(item.path)"
        class="flex-1 py-1.5 flex flex-col items-center gap-0.5 transition"
        :class="route.path === item.path ? 'text-accent-light' : 'text-gray-500 hover:text-gray-300'">
        <svg v-if="item.icon === 'home'" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
            d="M3 12l9-9 9 9M5 10v10a1 1 0 001 1h3a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1h3a1 1 0 001-1V10"/>
        </svg>
        <svg v-else-if="item.icon === 'history'" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"/>
        </svg>
        <svg v-else-if="item.icon === 'settings'" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
            d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"/>
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
        </svg>
        <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
            d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.196-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118L2.01 9.411c-.783-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"/>
        </svg>
        <span class="text-[10px]">{{ item.label }}</span>
      </button>
    </nav>

    <!-- Add Source Dialog -->
    <SourceManager v-if="showAddSource"
      @close="showAddSource = false"
      @added="onSourceAdded"
    />

    <!-- Toast notifications -->
    <ToastContainer :toasts="toasts" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSources, addSource, deleteSource, getSites, sourcesHealth, probeSites } from './api'
import { useToast } from './composables/useToast'
import SourceManager from './components/SourceManager.vue'
import ToastContainer from './components/ToastContainer.vue'

const route = useRoute()
const router = useRouter()
const { toasts, toast, error: toastError } = useToast()

// State
const sources = ref([])
const currentSource = ref(null)
const sites = ref([])
const currentSite = ref(null)
const searchQuery = ref('')
const searchMode = ref('all') // 'site' = current site only, 'all' = aggregate；默认全源（第三方站点不稳定，全源带故障转移命中率更高）
const searchOpen = ref(false) // mobile: search input starts collapsed
const searchInput = ref(null)

// 移动端展开搜索：隐藏标题/源选择，聚焦输入框并拉起键盘
async function openSearch() {
  searchOpen.value = true
  await nextTick()
  searchInput.value?.focus()
}

function closeSearch() {
  searchOpen.value = false
  searchQuery.value = ''
  searchFocused.value = false
}

const mobileNav = [
  { path: '/', label: '首页', icon: 'home' },
  { path: '/history', label: '历史', icon: 'history' },
  { path: '/favorites', label: '收藏', icon: 'star' },
  { path: '/settings', label: '设置', icon: 'settings' },
]

// Source health: source_id -> {ok, latency_ms, error}
const healthMap = ref({})
const healthChecking = ref(false)

// Search history (localStorage)
const searchFocused = ref(false)
const searchHistory = ref([])
const SEARCH_HIST_KEY = 'videohub_search_history'
const sidebarOpen = ref(window.innerWidth >= 640)
const showSourceMenu = ref(false)
const showAddSource = ref(false)
const sourceDropdown = ref(null)
const isMobile = ref(window.innerWidth < 640)

// Sites cache: source_id -> { sites: [...], loaded_at: timestamp }
const sitesCache = new Map()
let sitesAbort = null
const loadingSites = ref(false)
const sitesError = ref('')

// 站点健康探测：api_url -> {ok, latency_ms}
const probeState = ref({})
const probing = ref(false)

// APP 版本更新
const appUpdate = ref(null)

const searchableSites = computed(() =>
  sites.value.filter(s => s.type === 1 && (s.searchable === 1 || s.searchable === undefined))
)

// Lifecycle
onMounted(async () => {
  await loadSources()
  loadSearchHistory()
  checkAppUpdate()
  document.addEventListener('click', closeSourceMenu)
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  document.removeEventListener('click', closeSourceMenu)
  window.removeEventListener('resize', onResize)
})

function onResize() {
  isMobile.value = window.innerWidth < 640
}

function closeSourceMenu(e) {
  if (sourceDropdown.value && !sourceDropdown.value.contains(e.target)) {
    showSourceMenu.value = false
  }
}

// API calls
async function loadSources() {
  try {
    const { data } = await getSources()
    sources.value = data
    // Auto-select first source
    if (data.length && !currentSource.value) {
      await selectSource(data[0])
    }
  } catch (e) {
    console.error('Failed to load sources:', e)
  }
}

// 站点经常失联：探测候选站点并自动选中第一个可用的（后端探测缓存 5 分钟）
async function autoPickSite(list) {
  const candidates = list.filter(s => s.type === 1 && s.api).slice(0, 6)
  if (!candidates.length) return
  let picked = candidates[0]
  try {
    const { data } = await probeSites(candidates.map(s => s.api))
    const okSite = candidates.find(c => (data.results || []).find(r => r.api_url === c.api && r.ok))
    if (okSite) picked = okSite
  } catch { /* 探测失败就选第一个候选 */ }
  if (!currentSite.value || currentSite.value.key !== picked.key) {
    // 启动自动选站不改变当前路由，保住 #/player 等深链接
    selectSite(picked, { keepRoute: true })
  }
}

// 手动批量检测当前源的可搜索站点健康状态
async function probeCurrentSites() {
  if (!currentSource.value || probing.value) return
  probing.value = true
  try {
    const urls = searchableSites.value.slice(0, 80).map(s => s.api)
    if (!urls.length) return
    const { data } = await probeSites(urls)
    const map = {}
    for (const r of data.results || []) map[r.api_url] = r
    probeState.value = map
    const ok = (data.results || []).filter(r => r.ok).length
    handleToast({ message: `检测完成：${ok}/${(data.results || []).length} 个站点可用`, type: 'info' })
  } catch (e) {
    toastError(`检测失败: ${e.message}`)
  } finally {
    probing.value = false
  }
}

function siteDotClass(site) {
  if (currentSite.value?.key === site.key) return 'bg-accent'
  const p = probeState.value[site.api]
  if (p) return p.ok ? 'bg-green-500' : 'bg-red-500'
  return 'bg-gray-600'
}

// 检查 APP 新版本（仅 APP 内生效，浏览器无 AppNative）
async function checkAppUpdate() {
  if (!window.AppNative?.getAppVersion) return
  try {
    const cur = window.AppNative.getAppVersion()
    const resp = await fetch('/apk/version.json', { cache: 'no-store' })
    if (!resp.ok) return
    const meta = await resp.json()
    if (meta && meta.version && meta.version !== cur) {
      appUpdate.value = meta
    }
  } catch { /* 无更新清单或网络失败，忽略 */ }
}

function doAppUpdate() {
  if (!appUpdate.value?.url) return
  try {
    if (window.AppNative?.downloadApk) {
      window.AppNative.downloadApk(appUpdate.value.url)
      handleToast({ message: '已开始下载，完成后在通知栏点击安装', type: 'info' })
    } else {
      window.open(appUpdate.value.url, '_blank')
    }
    appUpdate.value = null
  } catch (e) {
    toastError(`下载失败: ${e.message}`)
  }
}

async function selectSource(source) {
  currentSource.value = source
  currentSite.value = null
  sitesError.value = ''

  // Check cache first — instant switch back
  if (sitesCache.has(source.id)) {
    sites.value = sitesCache.get(source.id)
    await autoPickSite(sites.value)
    return
  }

  // Cancel previous in-flight request
  if (sitesAbort) sitesAbort.abort()
  sitesAbort = new AbortController()
  sites.value = []
  loadingSites.value = true

  try {
    const { data } = await getSites(source.id, sitesAbort.signal)
    const loaded = data.sites || []
    sites.value = loaded
    sitesCache.set(source.id, loaded)
    await autoPickSite(loaded)
  } catch (e) {
    if (e.name !== 'CanceledError' && e.name !== 'AbortError') {
      console.error('Failed to load sites:', e)
      const msg = e.message || '未知错误'
      sitesError.value = msg
      toastError(`加载站点失败: ${msg}`)
    }
  } finally {
    loadingSites.value = false
  }
}

function selectSite(site, opts = {}) {
  currentSite.value = site
  // Close sidebar on mobile after selection
  if (isMobile.value) sidebarOpen.value = false
  if (opts.keepRoute !== true) router.push('/')
}

async function removeSource(id) {
  if (!confirm('确定删除此源？')) return
  try {
    await deleteSource(id)
    if (currentSource.value?.id === id) {
      currentSource.value = null
      sites.value = []
      currentSite.value = null
    }
    sitesCache.delete(id)
    await loadSources()
    toast('源已删除')
  } catch (e) {
    toastError(`删除失败: ${e.message}`)
  }
}

async function onSourceAdded() {
  showAddSource.value = false
  await loadSources()
}

function doSearch() {
  if (searchQuery.value.trim()) {
    pushSearchHistory()
    searchFocused.value = false
    router.push('/')
    if (isMobile.value) searchOpen.value = false
  }
}

// ── Search history ────────────────────────────────────
function loadSearchHistory() {
  try {
    searchHistory.value = JSON.parse(localStorage.getItem(SEARCH_HIST_KEY) || '[]')
  } catch {
    searchHistory.value = []
  }
}

function persistSearchHistory() {
  try { localStorage.setItem(SEARCH_HIST_KEY, JSON.stringify(searchHistory.value)) } catch {}
}

function pushSearchHistory() {
  const q = searchQuery.value.trim()
  if (!q) return
  searchHistory.value = [q, ...searchHistory.value.filter(x => x !== q)].slice(0, 8)
  persistSearchHistory()
}

function applySearchHistory(h) {
  searchQuery.value = h
  searchFocused.value = false
  doSearch()
}

function removeSearchHistory(i) {
  searchHistory.value.splice(i, 1)
  persistSearchHistory()
}

function clearSearchHistory() {
  searchHistory.value = []
  persistSearchHistory()
}

function onSearchBlur() {
  setTimeout(() => { searchFocused.value = false }, 150)
}

// ── Source health ─────────────────────────────────────
async function checkHealth() {
  healthChecking.value = true
  try {
    const { data } = await sourcesHealth()
    const map = {}
    for (const r of data.results || []) map[r.source_id] = r
    healthMap.value = map
    const okCount = (data.results || []).filter(r => r.ok).length
    handleToast({ message: `检测完成：${okCount}/${(data.results || []).length} 个源可用`, type: 'info' })
  } catch (e) {
    toastError(`健康检测失败: ${e.message}`)
  } finally {
    healthChecking.value = false
  }
}

function healthDot(id) {
  const h = healthMap.value[id]
  if (!h) return 'bg-gray-600'
  return h.ok ? 'bg-green-500' : 'bg-red-500'
}

function healthText(id, siteCount) {
  const h = healthMap.value[id]
  if (!h) return `${siteCount} 个站点`
  if (h.ok) return `${siteCount} 个站点 · ${h.latency_ms}ms`
  return `${siteCount} 个站点 · ${h.error || '不可用'}`
}

function healthTitle(id) {
  const h = healthMap.value[id]
  if (!h) return '未检测'
  return h.ok ? '可用' : `不可用: ${h.error || ''}`
}

function goToPlayer(params) {
  router.push({ path: '/player', query: params })
}

function goPage(path) {
  router.push(path)
  if (isMobile.value) sidebarOpen.value = false
}

function handleToast({ message, type }) {
  if (type === 'error') toastError(message)
  else toast(message, type)
}
</script>
