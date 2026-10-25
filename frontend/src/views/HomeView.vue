<template>
  <div class="p-3 sm:p-4 min-h-full">
    <!-- No source/site prompt -->
    <div v-if="!currentSource || !currentSite" class="flex flex-col items-center justify-center h-[60vh] text-gray-500">
      <svg class="w-16 h-16 mb-4 text-dark-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
          d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"/>
      </svg>
      <p class="text-lg">{{ !currentSource ? '请先添加并选择影视源' : '正在加载站点...' }}</p>
    </div>

    <template v-else>
      <!-- Search Results -->
      <div v-if="isSearching">
        <div class="flex items-center gap-2 mb-4">
          <h2 class="text-lg font-semibold">搜索结果</h2>
          <span class="text-sm text-gray-500">"{{ searchQuery }}"</span>
          <span v-if="searchLoading" class="text-sm text-gray-500 animate-pulse">
            {{ searchMode === 'all' ? (aggTotal ? `已返回 ${aggDone}/${aggTotal} 个源` : '聚合搜索中...') : '搜索中...' }}
          </span>
        </div>

        <!-- 拼音首字母猜测：输入 wmsj 这类字母时给出候选片名 -->
        <div v-if="sugResults.length" class="mb-5">
          <div class="flex items-center gap-2 mb-2">
            <h3 class="text-sm font-semibold text-gray-200">拼音猜测</h3>
            <span class="text-[10px] text-gray-500">根据「{{ searchQuery.trim() }}」匹配的片名，点击直接搜索</span>
          </div>
          <div class="flex flex-wrap gap-2">
            <button v-for="m in sugResults" :key="m.name" type="button"
              @click="emit('search-word', m.query)"
              class="px-3 py-1.5 bg-dark-600 hover:bg-accent/30 rounded-lg text-sm text-gray-300 hover:text-white transition"
              :title="'片名：' + m.name">
              {{ m.query }}
            </button>
          </div>
        </div>
        <div v-else-if="lettersOnly && !sugReady" class="mb-4 text-xs text-gray-500">
          拼音索引还在后台构建，稍后再输入字母试试…
        </div>

        <!-- Aggregate: grouped by source -->
        <template v-if="searchMode === 'all'">
          <div v-for="g in aggResults" :key="g.source_id" class="mb-6">
            <div class="flex items-center gap-2 mb-2 cursor-pointer select-none" @click="toggleGroup(g.source_id)">
              <h3 class="text-sm font-semibold text-gray-200">{{ g.source_name }}</h3>
              <span v-if="g.ok" class="text-[10px] text-gray-500">{{ g.site_name }} · {{ g.list.length }} 条</span>
              <span v-else class="text-[10px] text-red-400/70">失败: {{ g.error }}</span>
              <svg class="w-3 h-3 text-gray-600 transition-transform"
                :class="isCollapsed(g.source_id) ? '' : 'rotate-90'" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/>
              </svg>
            </div>
            <template v-if="!isCollapsed(g.source_id)">
              <div v-if="g.ok && g.list.length"
                class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
                <VideoCard v-for="v in g.list" :key="g.source_id + '-' + v.vod_id" :video="v"
                  @click="viewDetail(v, { api_url: g.api_url, site_key: g.site_key, site_name: g.site_name, source_id: g.source_id })" />
              </div>
              <div v-else-if="g.ok" class="text-xs text-gray-600 py-1">该源无结果</div>
            </template>
          </div>
          <div v-if="!searchLoading && !aggResults.some(g => g.ok && g.list.length)"
            class="text-center text-gray-500 py-12">无搜索结果</div>
          <!-- Aggregate pagination -->
          <div v-if="aggResults.length" class="flex justify-center gap-2 mt-2 pb-4">
            <button @click="aggPrev" :disabled="aggPage <= 1"
              class="px-3 py-1.5 bg-dark-600 rounded-lg text-sm disabled:opacity-30 hover:bg-dark-500 transition">
              上一页
            </button>
            <span class="px-3 py-1.5 text-sm text-gray-400">第 {{ aggPage }} 页</span>
            <button @click="aggNext"
              class="px-3 py-1.5 bg-dark-600 rounded-lg text-sm hover:bg-dark-500 transition">
              下一页
            </button>
          </div>
        </template>

        <!-- Single-site results -->
        <template v-else>
          <div v-if="searchResults.length" class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
            <VideoCard v-for="v in searchResults" :key="v.vod_id" :video="v"
              :site="currentSite" @click="viewDetail(v)" />
          </div>
          <EmptyState v-else-if="!searchLoading" text="无搜索结果" />
        </template>
      </div>

      <!-- Browse Mode -->
      <div v-else>
        <!-- Continue watching -->
        <div v-if="continueWatching.length" class="mb-5">
          <h2 class="text-sm font-semibold text-gray-300 mb-2">继续观看</h2>
          <div class="flex gap-3 overflow-x-auto pb-2 scrollbar-hide">
            <div v-for="r in continueWatching" :key="'cw-' + r.site_key + '-' + r.vod_id"
              class="w-24 sm:w-28 shrink-0 cursor-pointer group" @click="resumeRecord(r)">
              <div class="relative aspect-[2/3] rounded-lg overflow-hidden bg-dark-600 mb-1.5">
                <img v-if="r.vod_pic" :src="`/api/proxy/image?url=${encodeURIComponent(r.vod_pic)}`"
                  class="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
                  loading="lazy" @error="$event.target.style.display='none'" />
                <div v-else class="w-full h-full flex items-center justify-center text-gray-600">
                  <svg class="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
                      d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"/>
                  </svg>
                </div>
                <div class="absolute bottom-0 left-0 right-0 h-1 bg-black/50">
                  <div class="h-full bg-red-500" :style="{ width: recPct(r) + '%' }"></div>
                </div>
              </div>
              <p class="text-xs text-gray-200 truncate group-hover:text-accent-light transition">{{ r.vod_name }}</p>
              <p class="text-[10px] text-gray-500 truncate">第{{ (r.episode_index || 0) + 1 }}集 · {{ fmtTime(r.current_time) }}</p>
            </div>
          </div>
        </div>

        <!-- Category tabs -->
        <div class="flex items-center gap-1 mb-4 overflow-x-auto pb-1 scrollbar-hide">
          <button @click="loadCategory('')"
            class="px-3 py-1.5 rounded-lg text-sm whitespace-nowrap transition shrink-0"
            :class="!activeCategory ? 'bg-accent text-white' : 'bg-dark-600 text-gray-300 hover:bg-dark-500'">
            推荐
          </button>
          <button v-for="cat in categories" :key="cat.type_id"
            @click="loadCategory(cat.type_id)"
            class="px-3 py-1.5 rounded-lg text-sm whitespace-nowrap transition shrink-0"
            :class="activeCategory === cat.type_id
              ? 'bg-accent text-white'
              : 'bg-dark-600 text-gray-300 hover:bg-dark-500'">
            {{ cat.type_name }}
          </button>
        </div>

        <!-- Video grid -->
        <div v-if="loading" class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
          <div v-for="i in 12" :key="i" class="animate-pulse">
            <div class="bg-dark-600 rounded-lg aspect-[2/3] mb-2"></div>
            <div class="bg-dark-600 rounded h-4 w-3/4 mb-1"></div>
            <div class="bg-dark-600 rounded h-3 w-1/2"></div>
          </div>
        </div>
        <div v-else-if="videos.length" class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
          <VideoCard v-for="v in videos" :key="v.vod_id" :video="v"
            :site="currentSite" @click="viewDetail(v)" />
        </div>
        <div v-else-if="errorMessage" class="text-center py-12">
          <svg class="w-10 h-10 text-red-400/60 mx-auto mb-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
              d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"/>
          </svg>
          <p class="text-red-400/80 text-sm mb-2">{{ errorMessage }}</p>
          <div class="flex items-center justify-center gap-2">
            <button @click="loadContent()"
              class="px-3 py-1.5 bg-dark-600 hover:bg-dark-500 rounded-lg text-xs text-gray-300 transition">
              重试
            </button>
            <button v-if="nextSite" @click="emit('select-site', nextSite)"
              class="px-3 py-1.5 bg-accent/20 hover:bg-accent/30 rounded-lg text-xs text-accent-light transition">
              试试其他站点
            </button>
          </div>
        </div>
        <EmptyState v-else text="暂无内容" />

        <!-- Pagination -->
        <div v-if="totalPages > 1" class="flex justify-center gap-2 mt-6 pb-4">
          <button @click="loadPage(page - 1)" :disabled="page <= 1"
            class="px-3 py-1.5 bg-dark-600 rounded-lg text-sm disabled:opacity-30 hover:bg-dark-500 transition">
            上一页
          </button>
          <span class="px-3 py-1.5 text-sm text-gray-400">{{ page }} / {{ totalPages }}</span>
          <button @click="loadPage(page + 1)" :disabled="page >= totalPages"
            class="px-3 py-1.5 bg-dark-600 rounded-lg text-sm disabled:opacity-30 hover:bg-dark-500 transition">
            下一页
          </button>
        </div>
      </div>

      <!-- Detail Modal -->
      <transition name="modal">
        <div v-if="showDetail" class="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[100] p-0 sm:p-4"
          @click.self="showDetail = false">
          <div class="bg-dark-700 border border-dark-500 w-full h-full sm:h-auto sm:max-h-[85vh] sm:max-w-2xl sm:rounded-xl overflow-y-auto shadow-2xl">
            <div class="p-4 sm:p-5">
              <!-- Header -->
              <div class="flex gap-4">
                <img v-if="detailData.vod_pic"
                  :src="`/api/proxy/image?url=${encodeURIComponent(detailData.vod_pic)}`"
                  class="w-28 h-40 sm:w-32 sm:h-44 object-cover rounded-lg shrink-0 bg-dark-600"
                  @error="$event.target.style.display='none'" />
                <div class="flex-1 min-w-0">
                  <h3 class="text-lg font-bold mb-1.5 truncate">{{ detailData.vod_name }}</h3>
                  <div class="flex flex-wrap gap-1 mb-2">
                    <span v-if="detailData.vod_year" class="px-1.5 py-0.5 bg-dark-600 rounded text-[10px] text-gray-400">{{ detailData.vod_year }}</span>
                    <span v-if="detailData.vod_area" class="px-1.5 py-0.5 bg-dark-600 rounded text-[10px] text-gray-400">{{ detailData.vod_area }}</span>
                    <span v-if="detailData.type_name" class="px-1.5 py-0.5 bg-dark-600 rounded text-[10px] text-gray-400">{{ detailData.type_name }}</span>
                    <span v-if="detailData.vod_remarks" class="px-1.5 py-0.5 bg-dark-600 rounded text-[10px] text-amber-400">{{ detailData.vod_remarks }}</span>
                  </div>
                  <p class="text-xs text-gray-500 leading-relaxed" :class="introExpanded ? '' : 'line-clamp-3'">
                    {{ detailData.vod_content || '暂无简介' }}
                  </p>
                  <button v-if="(detailData.vod_content || '').length > 60"
                    @click="introExpanded = !introExpanded"
                    class="text-[10px] text-accent-light hover:text-accent transition mt-0.5">
                    {{ introExpanded ? '收起 ▲' : '展开 ▼' }}
                  </button>
                </div>
                <button @click="toggleFavorite" class="p-1 shrink-0" :title="isFavorite ? '取消收藏' : '收藏'">
                  <svg v-if="isFavorite" class="w-5 h-5 text-amber-400" fill="currentColor" viewBox="0 0 24 24">
                    <path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"/>
                  </svg>
                  <svg v-else class="w-5 h-5 text-gray-500 hover:text-amber-400 transition" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.196-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118L2.01 9.411c-.783-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"/>
                  </svg>
                </button>
                <button @click="showDetail = false" class="text-gray-500 hover:text-white p-1 shrink-0">
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
                  </svg>
                </button>
              </div>

              <!-- Detail loading spinner -->
              <div v-if="detailLoading" class="flex items-center justify-center py-8 gap-2">
                <div class="w-5 h-5 border-2 border-accent border-t-transparent rounded-full animate-spin"></div>
                <span class="text-sm text-gray-400">加载详情...</span>
              </div>

              <!-- Detail error -->
              <div v-else-if="detailError" class="mt-4 text-center py-6">
                <p class="text-red-400/80 text-sm mb-2">{{ detailError }}</p>
                <button @click="retryDetail"
                  class="px-3 py-1.5 bg-dark-600 hover:bg-dark-500 rounded-lg text-xs text-gray-300 transition">
                  重试
                </button>
              </div>

              <!-- Episodes -->
              <div v-else-if="currentEpisodes.length" class="mt-4">
                <!-- Play source tabs -->
                <div v-if="playSources.length > 1" class="flex gap-1 mb-2 overflow-x-auto">
                  <button v-for="(ps, idx) in playSources" :key="idx"
                    @click="activePlaySource = idx"
                    class="px-2.5 py-1 rounded text-xs whitespace-nowrap transition shrink-0"
                    :class="activePlaySource === idx ? 'bg-accent text-white' : 'bg-dark-600 text-gray-400 hover:bg-dark-500'">
                    {{ ps.source_name || `线路${idx + 1}` }}
                  </button>
                </div>
                <div class="grid grid-cols-4 sm:grid-cols-6 md:grid-cols-8 gap-1.5 max-h-60 overflow-y-auto">
                  <button v-for="(ep, idx) in currentEpisodes" :key="idx"
                    @click="playEpisode(ep, idx)"
                    class="px-2 py-1.5 bg-dark-600 hover:bg-accent/30 rounded text-xs text-center
                           text-gray-300 hover:text-white transition truncate">
                    {{ ep.name || `第${idx + 1}集` }}
                  </button>
                </div>
              </div>
              <div v-else class="mt-4 text-center text-gray-500 text-sm py-4">
                暂无播放资源
              </div>

              <!-- Alt-source search: find this show on other sources -->
              <div v-if="!detailLoading && !detailError" class="mt-3 border-t border-dark-600 pt-2.5">
                <button @click="toggleAltSearch" class="text-xs text-accent-light hover:text-accent transition">
                  {{ showAlt ? '收起其他源 ▲' : '在其他源找这部剧 ▼' }}
                </button>
                <div v-if="showAlt && altLoading" class="text-xs text-gray-500 mt-2 animate-pulse">全源聚合搜索中...</div>
                <div v-if="showAlt && !altLoading && altResults.length" class="mt-2 space-y-1.5">
                  <div v-for="m in altResults" :key="m.source_id" class="flex items-center gap-2 text-xs">
                    <span class="text-gray-500 w-16 sm:w-20 truncate shrink-0">{{ m.source_name }}</span>
                    <button @click="switchToAlt(m)"
                      class="flex-1 text-left px-2 py-1.5 bg-dark-600 hover:bg-dark-500 rounded truncate text-gray-300 hover:text-white transition">
                      {{ m.hit.vod_name }}<span class="text-gray-600"> · {{ m.site_name }}{{ m.hit.vod_remarks ? ' · ' + m.hit.vod_remarks : '' }}</span>
                    </button>
                  </div>
                </div>
                <div v-if="showAlt && !altLoading && !altResults.length" class="text-xs text-gray-600 mt-1.5">
                  其他源没有找到同名剧集
                </div>
              </div>
            </div>
          </div>
        </div>
      </transition>
    </template>
  </div>
</template>

<script setup>
import { ref, watch, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getCategories, searchVideos, searchAll, getDetail, searchSuggest,
  checkFavorite, addFavorite, deleteFavorite,
  getHistory, siteLookup,
} from '../api'
import VideoCard from '../components/VideoCard.vue'
import EmptyState from '../components/EmptyState.vue'

const props = defineProps({
  currentSource: Object,
  currentSite: Object,
  sites: Array,
  searchQuery: String,
  searchMode: String, // 'site' | 'all'
})
const emit = defineEmits(['play', 'select-site', 'toast', 'search-word'])
const route = useRoute()
const router = useRouter()

// Browse state
const videos = ref([])
const categories = ref([])
const activeCategory = ref('')
const page = ref(1)
const totalPages = ref(1)
const loading = ref(false)
const errorMessage = ref('')

// Category cache: site_key -> categories[]
const categoryCache = new Map()
let contentAbort = null
let searchAbort = null
let searchDebounce = null

// Search state
const searchResults = ref([])
const searchLoading = ref(false)
// Aggregate search state (searchMode === 'all')
const aggResults = ref([])
const aggDone = ref(0)
const aggTotal = ref(0)
const aggPage = ref(1)
const collapsedGroups = ref([])
const isSearching = computed(() => props.searchQuery && props.searchQuery.trim())

// 拼音猜测（纯字母关键词，如 wmsj → 完美世界）
const sugResults = ref([])
const sugReady = ref(true)
const lettersOnly = computed(() => /^[a-z0-9]{2,}$/.test((props.searchQuery || '').trim().toLowerCase()))
let sugAbort = null
let sugDebounce = null

watch(() => props.searchQuery, (q) => {
  clearTimeout(sugDebounce)
  const kw = (q || '').trim().toLowerCase()
  if (/^[a-z0-9]{2,}$/.test(kw)) {
    sugDebounce = setTimeout(fetchSuggest, 350, kw)
  } else {
    if (sugAbort) sugAbort.abort()
    sugResults.value = []
  }
})

async function fetchSuggest(kw) {
  if (sugAbort) sugAbort.abort()
  sugAbort = new AbortController()
  try {
    const { data } = await searchSuggest(kw, sugAbort.signal)
    sugReady.value = data.ready !== false
    // 同一核心词的多个变体只留一条（精编版/2024 都指向"完美世界"时只显示一个）
    const seen = new Set()
    sugResults.value = (data.matches || []).filter(m => m.query && !seen.has(m.query) && seen.add(m.query))
  } catch (e) {
    if (e.name !== 'CanceledError' && e.name !== 'AbortError') {
      sugResults.value = []
    }
  }
}

// 当前站点加载失败时，提供一个备选站点（同源内的下一个可搜索站点）
const nextSite = computed(() => {
  const list = (props.sites || []).filter(s => s.type === 1 && s.api)
  if (!props.currentSite || list.length < 2) return null
  const idx = list.findIndex(s => s.key === props.currentSite.key)
  return list[(idx + 1) % list.length] || null
})

// Detail modal
const showDetail = ref(false)
const detailData = ref({})
const detailLoading = ref(false)
const detailError = ref('')
const playSources = ref([])
const activePlaySource = ref(0)
const isFavorite = ref(false)
const introExpanded = ref(false)

// Continue watching (latest history entries)
const continueWatching = ref([])

// Alt-source search ("find this show on other sources")
const showAlt = ref(false)
const altLoading = ref(false)
const altResults = ref([])

onMounted(loadContinueWatching)

async function loadContinueWatching() {
  try {
    const { data } = await getHistory()
    continueWatching.value = (data || []).slice(0, 10)
  } catch {}
}

function recPct(r) {
  if (!r.duration) return 0
  return Math.min(100, Math.round((r.current_time / r.duration) * 100))
}

function fmtTime(sec) {
  if (!sec || isNaN(sec)) return '00:00'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = Math.floor(sec % 60)
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

async function resumeRecord(r) {
  try {
    let apiUrl = r.api_url
    if (!apiUrl && r.site_key) {
      apiUrl = (await siteLookup({ site_key: r.site_key })).data.api_url
    }
    if (!apiUrl) throw new Error('未找到站点')

    if (r.play_url) {
      emit('play', {
        api_url: apiUrl,
        site_key: r.site_key,
        site_name: r.site_name,
        vod_id: String(r.vod_id),
        vod_name: r.vod_name,
        vod_pic: r.vod_pic || '',
        episode_index: r.episode_index || 0,
        episode_name: r.episode_name || '',
        play_url: r.play_url,
      })
    } else {
      viewDetail(
        { vod_id: r.vod_id, vod_name: r.vod_name, vod_pic: r.vod_pic },
        { api_url: apiUrl, site_key: r.site_key, site_name: r.site_name },
      )
    }
  } catch {
    emit('toast', { message: '该记录所属站点已失效，无法续播', type: 'error' })
  }
}

const currentEpisodes = computed(() => {
  if (!playSources.value.length) return []
  return playSources.value[activePlaySource.value]?.episodes || []
})

// Watch for site changes
watch(() => props.currentSite, (site) => {
  if (site) {
    page.value = 1
    activeCategory.value = ''
    if (categoryCache.has(site.key)) {
      categories.value = categoryCache.get(site.key)
    } else {
      categories.value = []
    }
    loadContent()
  }
}, { immediate: true })

// Watch for search (query or scope change) with debounce
watch([() => props.searchQuery, () => props.searchMode], ([q, mode]) => {
  clearTimeout(searchDebounce)
  if (q && q.trim()) {
    searchDebounce = setTimeout(() => {
      aggPage.value = 1
      mode === 'all' ? doAggregateSearch() : doSearch()
    }, 400)
  } else {
    searchResults.value = []
    aggResults.value = []
  }
})

async function loadContent() {
  if (!props.currentSite) return

  if (contentAbort) contentAbort.abort()
  contentAbort = new AbortController()

  loading.value = true
  errorMessage.value = ''
  try {
    const params = {
      api_url: props.currentSite.api,
      page: page.value,
    }
    if (activeCategory.value) params.t = activeCategory.value

    const { data } = await getCategories(params, contentAbort.signal)

    if (data?.class && !categoryCache.has(props.currentSite.key)) {
      categoryCache.set(props.currentSite.key, data.class)
    }
    if (data?.class) categories.value = data.class
    if (data?.list) videos.value = data.list
    totalPages.value = data?.pagecount || 1
  } catch (e) {
    if (e.name !== 'CanceledError' && e.name !== 'AbortError') {
      console.error('Load content failed:', e)
      videos.value = []
      errorMessage.value = e.message || '加载失败'
    }
  } finally {
    loading.value = false
  }
}

function loadCategory(catId) {
  activeCategory.value = catId
  page.value = 1
  loadContent()
}

function loadPage(p) {
  page.value = p
  loadContent()
}

async function doSearch() {
  if (!props.currentSite || !props.searchQuery) return

  if (searchAbort) searchAbort.abort()
  searchAbort = new AbortController()

  searchLoading.value = true
  searchResults.value = []
  try {
    const { data } = await searchVideos(
      { api_url: props.currentSite.api, wd: props.searchQuery },
      searchAbort.signal,
    )
    searchResults.value = data?.list || []
  } catch (e) {
    if (e.name !== 'CanceledError' && e.name !== 'AbortError') {
      console.error('Search failed:', e)
      emit('toast', {
        message: '当前站点搜索失败（站点可能已失联），可在左侧切换站点，或改用「全源」搜索',
        type: 'error',
      })
    }
  } finally {
    searchLoading.value = false
  }
}

async function doAggregateSearch() {
  if (!props.searchQuery) return

  if (searchAbort) searchAbort.abort()
  searchAbort = new AbortController()
  const signal = searchAbort.signal

  searchLoading.value = true
  aggResults.value = []
  aggDone.value = 0
  aggTotal.value = 0
  try {
    // 流式接口：每个源一完成就推给前端，先到先上屏
    await searchStream(
      { wd: props.searchQuery, page: aggPage.value },
      {
        signal,
        onEvent(ev) {
          if (signal.aborted) return
          if (ev.type === 'meta') {
            aggTotal.value = ev.total
          } else if (ev.type === 'source') {
            aggResults.value.push(ev.result)
            aggDone.value = aggResults.value.length
          }
        },
      },
    )
  } catch (e) {
    if (e.name !== 'AbortError' && !signal.aborted) {
      console.error('Aggregate search failed:', e)
      emit('toast', { message: `聚合搜索失败: ${e.message}`, type: 'error' })
    }
  } finally {
    if (!signal.aborted) searchLoading.value = false
  }
}

// Where the current detail modal content came from (origin site/source)
const detailOrigin = ref(null)

async function viewDetail(video, origin = null) {
  detailOrigin.value = {
    api_url: origin?.api_url || props.currentSite?.api || '',
    site_key: origin?.site_key || props.currentSite?.key || '',
    site_name: origin?.site_name || props.currentSite?.name || '',
    source_id: origin?.source_id || props.currentSource?.id || '',
  }
  const apiUrl = detailOrigin.value.api_url
  if (!apiUrl) return

  detailData.value = { ...video }
  playSources.value = []
  activePlaySource.value = 0
  detailError.value = ''
  introExpanded.value = false
  showAlt.value = false
  altResults.value = []
  showDetail.value = true
  detailLoading.value = true

  // Favorite state (per site_key + vod_id)
  const favKey = detailOrigin.value.site_key
  isFavorite.value = false
  if (favKey) {
    checkFavorite({ site_key: favKey, vod_id: String(video.vod_id) })
      .then(({ data }) => { isFavorite.value = !!data.favorited })
      .catch(() => {})
  }

  const abort = new AbortController()
  const timer = setTimeout(() => abort.abort(), 12000)

  try {
    const { data } = await getDetail(
      { api_url: apiUrl, ids: String(video.vod_id) },
      abort.signal,
    )
    if (data?.list?.length) {
      detailData.value = data.list[0]
      // Parse episodes from multiple play sources
      const sources = []
      const fromParts = (detailData.value.vod_play_from || '').split('$$$')
      const urlParts = (detailData.value.vod_play_url || '').split('$$$')

      for (let i = 0; i < urlParts.length; i++) {
        const eps = urlParts[i].split('#').filter(Boolean).map(ep => {
          const [name, url] = ep.split('$')
          return { name: name || url, url: url || name }
        })
        sources.push({
          source_name: fromParts[i] || '',
          episodes: eps,
        })
      }
      playSources.value = sources
    }
  } catch (e) {
    if (e.name === 'CanceledError' || e.name === 'AbortError') {
      detailError.value = '加载超时，请重试'
    } else {
      console.error('Detail load failed:', e)
      detailError.value = e.message || '详情加载失败'
    }
  } finally {
    clearTimeout(timer)
    detailLoading.value = false
  }
}

function retryDetail() {
  viewDetail(detailData.value, detailOrigin.value)
}

async function toggleFavorite() {
  const favKey = detailOrigin.value?.site_key || props.currentSite?.key || ''
  const vodId = String(detailData.value.vod_id || '')
  if (!favKey || !vodId) return
  try {
    if (isFavorite.value) {
      await deleteFavorite(favKey, vodId)
      isFavorite.value = false
      emit('toast', { message: '已取消收藏', type: 'info' })
    } else {
      await addFavorite({
        site_key: favKey,
        site_name: detailOrigin.value?.site_name || props.currentSite?.name || '',
        vod_id: vodId,
        vod_name: detailData.value.vod_name || '',
        vod_pic: detailData.value.vod_pic || '',
        api_url: detailOrigin.value?.api_url || props.currentSite?.api || '',
      })
      isFavorite.value = true
      emit('toast', { message: '已加入收藏', type: 'success' })
    }
  } catch (e) {
    emit('toast', { message: `收藏操作失败: ${e.message}`, type: 'error' })
  }
}

// Deep link: /favorites or /history push query so HomeView opens the detail
watch(() => route.query, (q) => {
  if (q.vod_id && q.api_url) {
    const video = { vod_id: q.vod_id, vod_name: q.vod_name || '', vod_pic: q.vod_pic || '' }
    viewDetail(video, {
      api_url: q.api_url,
      site_key: q.site_key || '',
      site_name: q.site_name || '',
    })
    router.replace({ path: '/' })
  }
}, { immediate: true })

// ── Alt-source search ─────────────────────────────────
function toggleAltSearch() {
  showAlt.value = !showAlt.value
  if (showAlt.value && !altResults.value.length && !altLoading.value) {
    findAltSources()
  }
}

async function findAltSources() {
  const name = (detailData.value.vod_name || '').trim()
  if (!name) return
  altLoading.value = true
  altResults.value = []
  try {
    const { data } = await searchAll({ wd: name })
    const curSourceId = detailOrigin.value?.source_id || ''
    const matches = []
    for (const g of (data?.results || [])) {
      if (!g.ok || !g.list?.length) continue
      if (curSourceId && g.source_id === curSourceId) continue
      const hit =
        g.list.find(v => v.vod_name === name) ||
        g.list.find(v => v.vod_name && v.vod_name.includes(name))
      if (hit) matches.push({ ...g, hit })
    }
    // exact-name matches first
    matches.sort((a, b) => (b.hit.vod_name === name ? 1 : 0) - (a.hit.vod_name === name ? 1 : 0))
    altResults.value = matches.slice(0, 8)
  } catch (e) {
    emit('toast', { message: `聚合搜索失败: ${e.message}`, type: 'error' })
  } finally {
    altLoading.value = false
  }
}

function switchToAlt(m) {
  viewDetail(m.hit, {
    api_url: m.api_url,
    site_key: m.site_key,
    site_name: m.site_name,
    source_id: m.source_id,
  })
}

// Aggregate pagination
function aggPrev() {
  if (aggPage.value > 1) {
    aggPage.value--
    doAggregateSearch()
  }
}

function aggNext() {
  aggPage.value++
  doAggregateSearch()
}

// Group collapse (collapsed groups render header only)
function toggleGroup(id) {
  collapsedGroups.value = collapsedGroups.value.includes(id)
    ? collapsedGroups.value.filter(x => x !== id)
    : [...collapsedGroups.value, id]
}

function isCollapsed(id) {
  return collapsedGroups.value.includes(id)
}

function playEpisode(ep, idx) {
  const origin = detailOrigin.value
  emit('play', {
    api_url: origin?.api_url || props.currentSite.api,
    site_key: origin?.site_key || props.currentSite.key,
    site_name: origin?.site_name || props.currentSite.name,
    vod_id: String(detailData.value.vod_id),
    vod_name: detailData.value.vod_name,
    vod_pic: detailData.value.vod_pic || '',
    episode_index: idx,
    episode_name: ep.name,
    play_url: ep.url,
  })
  showDetail.value = false
}
</script>

<style scoped>
.modal-enter-active { transition: all 0.2s ease; }
.modal-leave-active { transition: all 0.15s ease; }
.modal-enter-from { opacity: 0; }
.modal-enter-from > div { transform: scale(0.95); }
.modal-leave-to { opacity: 0; }
</style>
