<template>
  <div class="h-full flex flex-col bg-dark-900">
    <!-- Player Area -->
    <div class="relative bg-black flex-1 min-h-0" :class="cssFullscreen ? '!fixed inset-0 z-[9999]' : ''" ref="playerContainer"
      @mousemove="onMouseMove" @click="onClickPlayer" @dblclick="onDblClick">

      <video ref="videoEl" class="w-full h-full object-contain touch-none select-none"
        @timeupdate="onTimeUpdate" @loadedmetadata="onLoaded" @ended="onEnded"
        @play="playing = true" @pause="playing = false"
        @volumechange="onVolumeChange"
        @waiting="buffering = true" @canplay="buffering = false"
        @touchstart="onTouchStart" @touchmove="onTouchMove" @touchend="onTouchEnd" @touchcancel="onTouchEnd">
      </video>

      <!-- Brightness overlay (左滑手势) -->
      <div v-if="brightness > 0" class="absolute inset-0 bg-black pointer-events-none"
        :style="{ opacity: brightness }"></div>

      <!-- Gesture indicator (亮度/音量/快进预览) -->
      <div v-if="gestureInfo" class="absolute inset-0 flex items-center justify-center pointer-events-none z-10">
        <div class="bg-black/70 rounded-xl px-5 py-3 text-center backdrop-blur-sm">
          <div class="text-white text-lg font-semibold tabular-nums">{{ gestureInfo.main }}</div>
          <div v-if="gestureInfo.sub" class="text-gray-300 text-xs mt-0.5">{{ gestureInfo.sub }}</div>
        </div>
      </div>

      <!-- Locked: floating unlock button -->
      <button v-if="locked" @click="locked = false" title="解锁"
        class="absolute top-3 right-3 z-30 p-2 bg-black/60 rounded-full text-white/90 backdrop-blur-sm">
        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
            d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"/>
        </svg>
      </button>

      <!-- Initial loading overlay -->
      <div v-if="playerLoading" class="absolute inset-0 flex items-center justify-center bg-black/60 pointer-events-none">
        <div class="flex flex-col items-center gap-2">
          <div class="w-10 h-10 border-3 border-accent border-t-transparent rounded-full animate-spin"></div>
          <span class="text-xs text-gray-400">加载中...</span>
        </div>
      </div>

      <!-- Buffering indicator (during playback) -->
      <transition name="buffer-fade">
        <div v-if="buffering && !playerLoading && playing"
          class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 pointer-events-none
                 flex items-center gap-2 bg-black/40 px-4 py-2 rounded-xl backdrop-blur-sm">
          <div class="w-5 h-5 border-2 border-white/80 border-t-transparent rounded-full animate-spin"></div>
          <span class="text-xs text-white/80">缓冲中</span>
        </div>
      </transition>

      <!-- Center play button (when paused) -->
      <div v-if="!locked && !playing && !playerLoading && !playerError"
        class="absolute inset-0 flex items-center justify-center pointer-events-none">
        <div class="w-16 h-16 bg-black/40 rounded-full flex items-center justify-center backdrop-blur-sm">
          <svg class="w-8 h-8 text-white ml-1" fill="currentColor" viewBox="0 0 24 24">
            <path d="M8 5v14l11-7z"/>
          </svg>
        </div>
      </div>

      <!-- Error overlay -->
      <div v-if="playerError" class="absolute inset-0 flex items-center justify-center bg-black/80">
        <div class="text-center">
          <svg class="w-12 h-12 text-red-400 mx-auto mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
              d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"/>
          </svg>
          <p class="text-red-400 text-sm">{{ playerError }}</p>
          <button @click="loadVideo(currentPlayUrl)" class="mt-2 px-3 py-1 bg-dark-600 rounded text-xs hover:bg-dark-500 transition">
            重试
          </button>
        </div>
      </div>

      <!-- Action toast (e.g. "快进10s") -->
      <transition name="action-toast">
        <div v-if="actionText"
          class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2
                 bg-black/60 text-white px-4 py-2 rounded-lg text-sm pointer-events-none backdrop-blur-sm">
          {{ actionText }}
        </div>
      </transition>

      <!-- Custom Controls (locked 时整体隐藏) -->
      <div v-if="!locked" class="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/80 to-transparent p-3 pt-8"
        @mouseenter="showControls = true" @mouseleave="showControls = playing ? false : true"
        :class="{ 'opacity-0': !showControls && playing }"
        style="transition: opacity 0.3s"
        @click.stop @dblclick.stop>
        <!-- Progress bar -->
        <div class="group cursor-pointer mb-2" @click="seekVideo($event)">
          <div class="h-1 group-hover:h-1.5 bg-white/20 rounded-full transition-all relative">
            <!-- Buffer indicator -->
            <div class="h-full bg-white/10 rounded-full absolute top-0"
              :style="{ width: bufferProgress + '%' }"></div>
            <!-- Play progress -->
            <div class="h-full bg-accent rounded-full transition-all relative"
              :style="{ width: progress + '%' }"></div>
            <div class="absolute top-1/2 -translate-y-1/2 w-3 h-3 bg-accent rounded-full opacity-0 group-hover:opacity-100 transition"
              :style="{ left: progress + '%', transform: 'translate(-50%, -50%)' }"></div>
          </div>
        </div>

        <div class="flex items-center gap-2 sm:gap-3">
          <!-- Play/Pause -->
          <button @click="togglePlay" class="text-white hover:text-accent-light transition">
            <svg v-if="!playing" class="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M8 5v14l11-7z"/>
            </svg>
            <svg v-else class="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
              <path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>
            </svg>
          </button>

          <!-- Volume -->
          <div class="flex items-center gap-1 group/vol">
            <button @click="toggleMute" class="text-gray-300 hover:text-white transition">
              <svg v-if="volume === 0" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5.586 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h1.586l4.707-4.707C10.923 3.663 12 4.109 12 5v14c0 .891-1.077 1.337-1.707.707L5.586 15z"/>
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2"/>
              </svg>
              <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.536 8.464a5 5 0 010 7.072M5.586 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h1.586l4.707-4.707C10.923 3.663 12 4.109 12 5v14c0 .891-1.077 1.337-1.707.707L5.586 15z"/>
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19.071 4.929a10 10 0 010 14.142"/>
              </svg>
            </button>
            <input type="range" min="0" max="1" step="0.05" :value="volume"
              @input="setVolume($event.target.value)"
              class="w-0 group-hover/vol:w-16 transition-all h-1 accent-accent cursor-pointer opacity-0 group-hover/vol:opacity-100" />
          </div>

          <!-- Time -->
          <span class="text-xs text-gray-300 tabular-nums">
            {{ formatTime(currentTime) }} / {{ formatTime(duration) }}
          </span>

          <div class="flex-1"></div>

          <!-- Keyboard shortcut hint (desktop only) -->
          <span v-if="showShortcuts" class="hidden sm:inline text-[10px] text-gray-600">
            空格:播放 ←→:快进退 F:全屏
          </span>

          <!-- Episode drawer toggle -->
          <button v-if="episodes.length" @click="showEpDrawer = !showEpDrawer"
            class="text-xs text-gray-300 hover:text-white px-2 py-1 rounded bg-dark-600/50 transition">
            集数
          </button>

          <!-- Next episode -->
          <button v-if="currentEpisodeIndex < episodes.length - 1" @click="playNext" title="下一集"
            class="text-gray-300 hover:text-white transition">
            <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
              <path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/>
            </svg>
          </button>

          <!-- Speed -->
          <div class="relative" ref="speedMenu">
            <button @click="showSpeedMenu = !showSpeedMenu"
              class="text-sm text-gray-300 hover:text-white px-2 py-1 rounded bg-dark-600/50 transition">
              {{ playbackRate }}x
            </button>
            <div v-if="showSpeedMenu"
              class="absolute bottom-full right-0 mb-1 bg-dark-700 border border-dark-500 rounded-lg py-1 shadow-xl min-w-[60px]">
              <button v-for="s in speeds" :key="s" @click="setSpeed(s); showSpeedMenu = false"
                class="block w-full px-4 py-1 text-sm text-left hover:bg-dark-600 transition"
                :class="playbackRate === s ? 'text-accent-light' : 'text-gray-300'">
                {{ s }}x
              </button>
            </div>
          </div>

          <!-- Quality (manual level select) -->
          <div v-if="levels.length > 1" class="relative" ref="qualityMenu">
            <button @click="showQualityMenu = !showQualityMenu"
              class="text-sm text-gray-300 hover:text-white px-2 py-1 rounded bg-dark-600/50 transition">
              {{ qualityLabel }}
            </button>
            <div v-if="showQualityMenu"
              class="absolute bottom-full right-0 mb-1 bg-dark-700 border border-dark-500 rounded-lg py-1 shadow-xl min-w-[72px] max-h-56 overflow-y-auto">
              <button @click="setQuality(-1)"
                class="block w-full px-4 py-1 text-sm text-left hover:bg-dark-600 transition"
                :class="currentLevel === -1 ? 'text-accent-light' : 'text-gray-300'">
                自动
              </button>
              <button v-for="l in levelOptions" :key="l.index" @click="setQuality(l.index)"
                class="block w-full px-4 py-1 text-sm text-left hover:bg-dark-600 transition"
                :class="currentLevel === l.index ? 'text-accent-light' : 'text-gray-300'">
                {{ l.label }}
              </button>
            </div>
          </div>

          <!-- Picture-in-Picture -->
          <button @click="togglePiP" class="text-gray-300 hover:text-white transition" title="画中画">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                d="M4 5a1 1 0 011-1h14a1 1 0 011 1v14a1 1 0 01-1 1H5a1 1 0 01-1-1V5z"/>
              <rect x="12" y="12" width="6" height="5" rx="0.5" fill="currentColor" stroke="none"/>
            </svg>
          </button>

          <!-- External player (APP 内调用系统播放器) -->
          <button v-if="currentPlayUrl" @click="playExternal" class="text-gray-300 hover:text-white transition" title="外部播放器">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"/>
            </svg>
          </button>

          <!-- Lock (防误触) -->
          <button @click="locked = true" class="text-gray-300 hover:text-white transition" title="锁定屏幕">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"/>
            </svg>
          </button>

          <!-- Fullscreen -->
          <button @click="toggleFullscreen" class="text-gray-300 hover:text-white transition">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4"/>
            </svg>
          </button>
        </div>
      </div>
    </div>

    <!-- Episode drawer (mounted inside the player container so it works in fullscreen) -->
    <div v-if="showEpDrawer"
      class="absolute top-0 right-0 bottom-0 w-60 sm:w-72 bg-dark-900/95 border-l border-dark-600 z-20 flex flex-col"
      @click.stop @dblclick.stop>
      <div class="flex items-center justify-between px-3 py-2 border-b border-dark-600 shrink-0">
        <span class="text-xs text-gray-300 truncate">{{ vodName }} · 共{{ episodes.length }}集</span>
        <button @click="showEpDrawer = false" class="text-gray-500 hover:text-white p-1">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
          </svg>
        </button>
      </div>
      <div class="flex-1 overflow-y-auto p-2 grid grid-cols-3 gap-1.5 content-start">
        <button v-for="(ep, idx) in episodes" :key="idx"
          @click="switchEpisode(ep, idx); showEpDrawer = false"
          class="px-1 py-1.5 rounded text-[11px] text-center truncate transition"
          :class="idx === currentEpisodeIndex
            ? 'bg-accent text-white'
            : 'bg-dark-700 text-gray-400 hover:bg-dark-600 hover:text-white'">
          {{ ep.name || `第${idx + 1}集` }}
        </button>
      </div>
    </div>

    <!-- Episode List -->
    <div class="h-40 sm:h-48 bg-dark-800 border-t border-dark-600 overflow-y-auto shrink-0 px-3 sm:px-4 py-3">
      <div class="flex items-center gap-3 mb-2">
        <h3 class="text-sm font-semibold text-gray-300 truncate">{{ vodName }}</h3>
        <span class="text-xs text-gray-500 shrink-0">正在播放: {{ episodeName }}</span>
        <button @click="$router.push('/')"
          class="ml-auto text-xs text-gray-500 hover:text-gray-300 transition flex items-center gap-1 shrink-0">
          <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18"/>
          </svg>
          返回
        </button>
      </div>
      <div class="flex flex-wrap gap-1.5">
        <button v-for="(ep, idx) in episodes" :key="idx"
          @click="switchEpisode(ep, idx)"
          class="px-3 py-1.5 rounded text-xs transition truncate max-w-[120px]"
          :class="idx === currentEpisodeIndex
            ? 'bg-accent text-white'
            : 'bg-dark-600 text-gray-400 hover:bg-dark-500 hover:text-white'">
          {{ ep.name || `第${idx + 1}集` }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
// hls.js 按需加载：首屏不拉取 524KB(162KB gzip) 的播放器库，进入 HLS 播放时才加载
let HlsLib = null
async function loadHls() {
  if (!HlsLib) HlsLib = (await import('hls.js')).default
  return HlsLib
}
import { getHistory, saveHistory, getDetail, resolvePlayUrl, getSettings } from '../api'

const route = useRoute()

// Refs
const videoEl = ref(null)
const playerContainer = ref(null)
const speedMenu = ref(null)
const qualityMenu = ref(null)

// State
const hls = ref(null)
const playing = ref(false)

// Android APP：通过原生 AppNative 接口（MainActivity 注入的 JavascriptInterface）保持屏幕常亮；浏览器端为 no-op。
function setKeepAwake(on) {
  try { window.AppNative?.setKeepAwake(!!on) } catch { /* noop */ }
}
watch(playing, (p) => {
  setKeepAwake(p)
  // 通知原生播放状态：用于"退到桌面自动进入画中画"
  try { window.AppNative?.setPlaying?.(!!p) } catch { /* noop */ }
})
const buffering = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const progress = ref(0)
const bufferProgress = ref(0)
const playbackRate = ref(1)
const volume = ref(1)
const speeds = [0.5, 0.75, 1, 1.25, 1.5, 2, 3]
const showControls = ref(true)
const showSpeedMenu = ref(false)
const showQualityMenu = ref(false)
const showEpDrawer = ref(false)
const levels = ref([])
const currentLevel = ref(-1)
const showShortcuts = ref(true)
const playerLoading = ref(false)
const playerError = ref('')
const actionText = ref('')

const levelOptions = computed(() => [...levels.value].sort((a, b) => b.index - a.index))
const qualityLabel = computed(() => {
  if (currentLevel.value === -1) return '自动'
  const opt = levels.value.find(l => l.index === currentLevel.value)
  return opt?.label || '自动'
})

// Video info
const vodName = ref('')
const episodeName = ref('')
const currentEpisodeIndex = ref(0)
const currentPlayUrl = ref('')
const episodes = ref([])

// Query params
const siteKey = ref('')
const siteName = ref('')
const vodId = ref('')
const vodPic = ref('')
const apiUrl = ref('')

// Timers
let saveInterval = null
let controlsTimer = null
let actionTimer = null
let loadingTimeout = null
let bufferCountTimer = null
let bufferCount = 0

// Volume persistence
const VOLUME_KEY = 'videohub_volume'

// App settings (default rate / autoplay / history recording)
const appSettings = ref({})
let defaultRateApplied = false

// Auto quality downgrade on frequent buffering
watch(buffering, (isBuffering) => {
  if (!isBuffering || !hls.value) return
  bufferCount++
  clearTimeout(bufferCountTimer)
  bufferCountTimer = setTimeout(() => { bufferCount = 0 }, 30000) // reset after 30s of no buffering

  if (bufferCount >= 4 && hls.value) {
    // Force lower quality: find a level below current
    const current = hls.value.currentLevel
    const levels = hls.value.levels
    if (levels.length > 1 && current > 0) {
      const newLevel = Math.max(0, current - 1)
      hls.value.currentLevel = newLevel
      bufferCount = 0
      console.warn(`Buffering too frequent, switching to level ${newLevel}`)
      showAction(`画质已降低`)
    }
  }
})

onMounted(() => {
  parseQuery()
  restoreVolume()
  loadAppSettings()
  document.addEventListener('keydown', handleKeydown)
  document.addEventListener('click', closeMenus)
  window.addEventListener('popstate', onFsPopState)
  saveInterval = setInterval(saveProgress, 10000)
})

async function loadAppSettings() {
  try {
    const { data } = await getSettings()
    appSettings.value = data || {}
  } catch {}
}

onUnmounted(() => {
  destroyHls()
  setKeepAwake(false)
  try { window.AppNative?.setPlaying?.(false) } catch { /* noop */ }
  if (cssFullscreen.value) exitAppFullscreen(false)
  clearTimeout(gesture.lpTimer)
  clearTimeout(gesture.hideTimer)
  window.removeEventListener('popstate', onFsPopState)
  clearTimeout(loadingTimeout)
  clearTimeout(bufferCountTimer)
  document.removeEventListener('keydown', handleKeydown)
  document.removeEventListener('click', closeMenus)
  clearInterval(saveInterval)
  clearTimeout(controlsTimer)
  clearTimeout(actionTimer)
  saveVolume()
  saveProgress()
})

function closeMenus(e) {
  if (speedMenu.value && !speedMenu.value.contains(e.target)) {
    showSpeedMenu.value = false
  }
  if (qualityMenu.value && !qualityMenu.value.contains(e.target)) {
    showQualityMenu.value = false
  }
}

function parseQuery() {
  const q = route.query
  apiUrl.value = q.api_url || ''
  siteKey.value = q.site_key || ''
  siteName.value = q.site_name || ''
  vodId.value = q.vod_id || ''
  vodName.value = q.vod_name || '未知'
  vodPic.value = q.vod_pic || ''
  episodeName.value = q.episode_name || ''
  currentEpisodeIndex.value = parseInt(q.episode_index || '0')

  if (q.play_url) {
    currentPlayUrl.value = q.play_url
    loadVideo(q.play_url)
  }

  if (apiUrl.value && vodId.value) {
    loadEpisodes()
  }
}

async function loadEpisodes() {
  try {
    const { data } = await getDetail({ api_url: apiUrl.value, ids: vodId.value })
    if (data?.list?.length) {
      const detail = data.list[0]
      vodName.value = detail.vod_name || vodName.value

      const fromParts = (detail.vod_play_from || '').split('$$$')
      const urlParts = (detail.vod_play_url || '').split('$$$')

      if (urlParts.length > 0) {
        episodes.value = urlParts[0].split('#').filter(Boolean).map(ep => {
          const [name, url] = ep.split('$')
          return { name: name || url, url: url || name }
        })
        // 无 play_url 进入时（如旧历史记录恢复），自动定位到当前集并开播
        if (!currentPlayUrl.value && episodes.value[currentEpisodeIndex.value]) {
          const ep = episodes.value[currentEpisodeIndex.value]
          episodeName.value = ep.name || `第${currentEpisodeIndex.value + 1}集`
          currentPlayUrl.value = ep.url
          loadVideo(ep.url)
        }
      }
    }
  } catch (e) {
    console.error('Load episodes failed:', e)
  }
}

async function loadVideo(url) {
  if (!url) return
  playerLoading.value = true
  playerError.value = ''
  destroyHls()
  clearTimeout(loadingTimeout)

  // Global timeout: if video doesn't start within 20s, show error
  loadingTimeout = setTimeout(() => {
    if (playerLoading.value && !playerError.value) {
      playerLoading.value = false
      playerError.value = '加载超时，请重试或换源'
      destroyHls()
    }
  }, 20000)

  let resolvedUrl = url
  let streamType = 'hls'

  try {
    const { data } = await resolvePlayUrl({ url })
    if (data.url) {
      resolvedUrl = data.url
      streamType = data.type || 'hls'
    }
  } catch (e) {
    console.warn('Resolve failed, trying direct play:', e)
  }

  const isHls = streamType === 'hls' || resolvedUrl.includes('m3u8')

  if (isHls) {
    const proxiedUrl = `/api/proxy/m3u8?url=${encodeURIComponent(resolvedUrl)}`
    const Hls = await loadHls()

    if (Hls.isSupported()) {
      const h = new Hls({
        enableWorker: true,
        lowLatencyMode: false,
        // Buffer sizes — larger buffer = less stuttering
        maxBufferLength: 60,         // target 60s ahead (default 30)
        maxMaxBufferLength: 120,     // cap at 2min (default 600)
        maxBufferSize: 80 * 1000 * 1000,  // 80MB cap
        maxBufferHole: 0.5,          // max gap to tolerate
        backBufferLength: 90,        // 释放已播放的缓冲，长片不涨内存
        // ABR — conservative start, fast adaptation
        abrEwmaDefaultEstimate: 800000,   // start at ~800kbps estimate
        abrEwmaFastLive: 3.0,        // fast bandwidth estimation
        abrEwmaSlowLive: 9.0,
        abrBandWidthFactor: 0.8,     // use 80% of estimated bandwidth
        abrBandWidthUpFactor: 0.7,   // conservative upgrade
        capLevelToPlayerSize: false,
        // Loading
        startFragPrefetch: true,     // prefetch next fragment
        testBandwidth: true,
        // XHR
        xhrSetup: (xhr) => {
          xhr.timeout = 15000
        },
      })
      h.loadSource(proxiedUrl)
      h.attachMedia(videoEl.value)
      h.on(Hls.Events.MANIFEST_PARSED, (_, data) => {
        clearTimeout(loadingTimeout)
        playerLoading.value = false
        // Capture available quality levels for the manual quality menu
        try {
          levels.value = (data.levels || []).map((l, i) => ({
            index: i,
            label: l.height ? `${l.height}p` : `${Math.round((l.bitrate || 0) / 1000)}kbps`,
          }))
        } catch { levels.value = [] }
        // Re-apply the user's manual quality choice across episodes
        if (currentLevel.value >= 0) {
          try { h.currentLevel = currentLevel.value } catch {}
        }
        videoEl.value?.play().catch(() => {})
        restoreProgress()
      })
      let hlsRecoveryAttempts = 0
      h.on(Hls.Events.ERROR, (_, data) => {
        if (data.fatal) {
          switch (data.type) {
            case Hls.ErrorTypes.NETWORK_ERROR:
              if (hlsRecoveryAttempts < 3) {
                console.warn('HLS network error, trying to recover...')
                hlsRecoveryAttempts++
                h.startLoad()
              } else {
                clearTimeout(loadingTimeout)
                playerLoading.value = false
                destroyHls()
                videoEl.value.src = resolvedUrl
                videoEl.value.play().catch(() => {
                  playerError.value = '播放失败，请重试'
                })
              }
              break
            case Hls.ErrorTypes.MEDIA_ERROR:
              if (hlsRecoveryAttempts < 3) {
                console.warn('HLS media error, trying to recover...')
                hlsRecoveryAttempts++
                h.recoverMediaError()
              } else {
                clearTimeout(loadingTimeout)
                playerLoading.value = false
                destroyHls()
                videoEl.value.src = resolvedUrl
                videoEl.value.play().catch(() => {
                  playerError.value = '播放失败，请重试'
                })
              }
              break
            default:
              clearTimeout(loadingTimeout)
              playerLoading.value = false
              destroyHls()
              videoEl.value.src = resolvedUrl
              videoEl.value.play().catch(() => {
                playerError.value = '播放失败，请重试'
              })
              break
          }
        }
      })
      hls.value = h
    } else if (videoEl.value.canPlayType('application/vnd.apple.mpegurl')) {
      // Safari native HLS
      videoEl.value.src = proxiedUrl
      videoEl.value.play().catch(() => {})
      clearTimeout(loadingTimeout)
      playerLoading.value = false
    } else {
      clearTimeout(loadingTimeout)
      playerLoading.value = false
      playerError.value = '浏览器不支持 HLS 播放'
    }
  } else {
    // Direct play (mp4, etc.)
    videoEl.value.src = resolvedUrl
    videoEl.value.play().catch(() => {
      playerError.value = '播放失败'
    })
    clearTimeout(loadingTimeout)
    playerLoading.value = false
    restoreProgress()
  }
}

function destroyHls() {
  if (hls.value) {
    hls.value.destroy()
    hls.value = null
  }
}

function switchEpisode(ep, idx) {
  currentEpisodeIndex.value = idx
  episodeName.value = ep.name
  currentPlayUrl.value = ep.url
  loadVideo(ep.url)
}

function playNext() {
  if (currentEpisodeIndex.value < episodes.value.length - 1) {
    switchEpisode(episodes.value[currentEpisodeIndex.value + 1], currentEpisodeIndex.value + 1)
  }
}

function setQuality(idx) {
  currentLevel.value = idx
  if (hls.value) {
    try { hls.value.currentLevel = idx } catch {}
  }
  showQualityMenu.value = false
  const opt = levels.value.find(l => l.index === idx)
  showAction(idx === -1 ? '画质: 自动' : `画质: ${opt?.label || idx}`)
}

// ── Controls ──────────────────────────────────────────
function togglePlay() {
  if (!videoEl.value) return
  if (videoEl.value.paused) videoEl.value.play()
  else videoEl.value.pause()
}

function seekVideo(e) {
  const rect = e.currentTarget.getBoundingClientRect()
  const pct = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width))
  videoEl.value.currentTime = pct * duration.value
}

function setSpeed(rate) {
  playbackRate.value = rate
  videoEl.value.playbackRate = rate
  showAction(`${rate}x 倍速`)
}

// ── 全屏：APP 内为 CSS 全屏 + 原生沉浸（AppNative 隐藏系统栏/锁定方向，Capacitor 会拦截
//    HTML5 requestFullscreen 且桥 JS 不注入远程页面）；浏览器端仍走标准 Fullscreen API ──
const cssFullscreen = ref(false)

async function enterAppFullscreen() {
  const native = window.AppNative
  if (!native) return
  const v = videoEl.value
  const landscape = (v?.videoWidth || 16) >= (v?.videoHeight || 9)
  try { native.enterImmersive(landscape ? 'landscape' : 'portrait') } catch {}
  cssFullscreen.value = true
  // 压入一条全屏历史，让系统返回键先退出全屏而不是退出播放页
  history.pushState({ vhfs: 1 }, '')
}

async function exitAppFullscreen(withHistory = true) {
  try { window.AppNative?.exitImmersive() } catch {}
  cssFullscreen.value = false
  if (withHistory && history.state?.vhfs) history.back()
}

function onFsPopState() {
  if (cssFullscreen.value && !history.state?.vhfs) exitAppFullscreen(false)
}

async function toggleFullscreen() {
  if (window.AppNative) {
    if (cssFullscreen.value) exitAppFullscreen()
    else enterAppFullscreen()
    return
  }
  if (document.fullscreenElement) {
    document.exitFullscreen()
  } else {
    playerContainer.value?.requestFullscreen()
  }
}

async function togglePiP() {
  // APP 内走原生画中画（WebView 的 requestPictureInPicture 不可靠）
  if (window.AppNative?.enterPip) {
    try { window.AppNative.enterPip(); showAction('画中画') } catch {}
    return
  }
  try {
    if (document.pictureInPictureElement) {
      await document.exitPictureInPicture()
    } else {
      await videoEl.value.requestPictureInPicture()
      showAction('画中画')
    }
  } catch (e) {
    console.warn('PiP not supported:', e)
  }
}

// 用系统外部播放器打开当前直链（APP 内弹选择器，浏览器新窗口）
function playExternal() {
  const url = currentPlayUrl.value
  if (!url) return
  if (window.AppNative?.playExternal) {
    try { window.AppNative.playExternal(url); showAction('已调用外部播放器') } catch {}
  } else {
    window.open(url, '_blank')
  }
}

// ── 移动手势（在 video 元素上）：横滑快进退 / 左侧上下滑亮度 / 右侧上下滑音量 / 长按 3x ──
const locked = ref(false)
const brightness = ref(0) // 黑色遮罩透明度 0~0.85
const gestureInfo = ref(null)
const gesture = {
  active: false, mode: null, startX: 0, startY: 0, startTime: 0,
  lpTimer: null, seekBase: 0, volBase: 0, brightBase: 0, seekTarget: null,
  hideTimer: null,
}

function showGesture(main, sub = '') {
  gestureInfo.value = { main, sub }
  clearTimeout(gesture.hideTimer)
}

function hideGestureSoon() {
  clearTimeout(gesture.hideTimer)
  gesture.hideTimer = setTimeout(() => { gestureInfo.value = null }, 350)
}

function onTouchStart(e) {
  if (locked.value) return
  const t = e.touches[0]
  gesture.active = true
  gesture.mode = null
  gesture.seekTarget = null
  gesture.startX = t.clientX
  gesture.startY = t.clientY
  gesture.startTime = Date.now()
  gesture.seekBase = currentTime.value
  gesture.volBase = volume.value
  gesture.brightBase = 1 - brightness.value / 0.85  // 亮度档位 0~1
  clearTimeout(gesture.lpTimer)
  gesture.lpTimer = setTimeout(() => {
    if (gesture.active && !gesture.mode && playing.value && duration.value) {
      gesture.mode = 'speed'
      videoEl.value.playbackRate = 3
      showGesture('3x 倍速', '松开恢复')
    }
  }, 500)
}

function onTouchMove(e) {
  if (!gesture.active || locked.value) return
  const t = e.touches[0]
  const dx = t.clientX - gesture.startX
  const dy = t.clientY - gesture.startY
  if (!gesture.mode) {
    if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) && duration.value) {
      gesture.mode = 'seek'
      clearTimeout(gesture.lpTimer)
    } else if (Math.abs(dy) > 12) {
      gesture.mode = t.clientX < window.innerWidth / 2 ? 'brightness' : 'volume'
      clearTimeout(gesture.lpTimer)
    }
  }
  if (!gesture.mode) return
  e.preventDefault()
  if (gesture.mode === 'seek') {
    const target = Math.max(0, Math.min(duration.value || 0, gesture.seekBase + dx * 0.4))
    gesture.seekTarget = target
    showGesture(`${formatTime(target)} / ${formatTime(duration.value)}`, dx >= 0 ? '快进' : '快退')
  } else if (gesture.mode === 'volume') {
    const v = Math.max(0, Math.min(1, gesture.volBase - dy / 300))
    videoEl.value.volume = v
    volume.value = v
    showGesture(`音量 ${Math.round(v * 100)}%`)
  } else if (gesture.mode === 'brightness') {
    const level = Math.max(0, Math.min(1, gesture.brightBase - dy / 400))
    brightness.value = (1 - level) * 0.85
    showGesture(`亮度 ${Math.round(level * 100)}%`)
  }
}

function onTouchEnd() {
  if (!gesture.active) return
  clearTimeout(gesture.lpTimer)
  if (gesture.mode === 'seek' && gesture.seekTarget != null) {
    videoEl.value.currentTime = gesture.seekTarget
  } else if (gesture.mode === 'speed') {
    videoEl.value.playbackRate = playbackRate.value
  }
  gesture.active = false
  gesture.mode = null
  gesture.seekTarget = null
  if (gestureInfo.value) hideGestureSoon()
}

// Volume
function setVolume(val) {
  const v = parseFloat(val)
  volume.value = v
  videoEl.value.volume = v
  videoEl.value.muted = v === 0
}

function toggleMute() {
  if (videoEl.value.muted || volume.value === 0) {
    videoEl.value.muted = false
    volume.value = videoEl.value.volume || 0.5
    videoEl.value.volume = volume.value
  } else {
    videoEl.value.muted = true
    volume.value = 0
  }
}

function onVolumeChange() {
  if (!videoEl.value.muted) {
    volume.value = videoEl.value.volume
  }
}

function saveVolume() {
  try { localStorage.setItem(VOLUME_KEY, String(volume.value)) } catch {}
}

function restoreVolume() {
  try {
    const saved = localStorage.getItem(VOLUME_KEY)
    if (saved !== null) {
      const v = parseFloat(saved)
      if (!isNaN(v) && v >= 0 && v <= 1) {
        volume.value = v
        if (videoEl.value) videoEl.value.volume = v
      }
    }
  } catch {}
}

// Mouse move: show controls
function onMouseMove() {
  showControls.value = true
  clearTimeout(controlsTimer)
  if (playing.value) {
    controlsTimer = setTimeout(() => {
      showControls.value = false
    }, 3000)
  }
}

function onClickPlayer(e) {
  if (locked.value) return
  // Only toggle play on click the video area, not controls
  if (e.target === videoEl.value || e.target === playerContainer.value) {
    togglePlay()
  }
}

function onDblClick(e) {
  if (locked.value) return
  const rect = playerContainer.value?.getBoundingClientRect()
  if (!rect) return
  const rel = (e.clientX - rect.left) / rect.width
  if (rel < 1 / 3) {
    videoEl.value.currentTime = Math.max(0, videoEl.value.currentTime - 10)
    showAction('快退 10s')
  } else if (rel > 2 / 3) {
    videoEl.value.currentTime = Math.min(duration.value || 0, videoEl.value.currentTime + 10)
    showAction('快进 10s')
  } else {
    toggleFullscreen()
  }
}

// Action toast
function showAction(text) {
  actionText.value = text
  clearTimeout(actionTimer)
  actionTimer = setTimeout(() => { actionText.value = '' }, 800)
}

// Keyboard shortcuts
function handleKeydown(e) {
  if (e.target.tagName === 'INPUT') return
  switch (e.key) {
    case ' ':
      e.preventDefault()
      togglePlay()
      showAction(playing.value ? '播放' : '暂停')
      break
    case 'ArrowLeft':
      e.preventDefault()
      videoEl.value.currentTime = Math.max(0, videoEl.value.currentTime - 10)
      showAction('快退 10s')
      break
    case 'ArrowRight':
      e.preventDefault()
      videoEl.value.currentTime = Math.min(duration.value, videoEl.value.currentTime + 10)
      showAction('快进 10s')
      break
    case 'ArrowUp':
      e.preventDefault()
      setVolume(Math.min(1, volume.value + 0.1))
      showAction(`音量 ${Math.round(volume.value * 100)}%`)
      saveVolume()
      break
    case 'ArrowDown':
      e.preventDefault()
      setVolume(Math.max(0, volume.value - 0.1))
      showAction(`音量 ${Math.round(volume.value * 100)}%`)
      saveVolume()
      break
    case 'f':
    case 'F':
      toggleFullscreen()
      break
    case 'm':
    case 'M':
      toggleMute()
      showAction(volume.value === 0 ? '静音' : '取消静音')
      break
  }
}

// ── Video Events ──────────────────────────────────────
function onTimeUpdate() {
  currentTime.value = videoEl.value.currentTime
  progress.value = duration.value ? (currentTime.value / duration.value) * 100 : 0

  // Buffer progress
  if (videoEl.value.buffered.length > 0) {
    const bufEnd = videoEl.value.buffered.end(videoEl.value.buffered.length - 1)
    bufferProgress.value = duration.value ? (bufEnd / duration.value) * 100 : 0
  }
}

function onLoaded() {
  duration.value = videoEl.value.duration
  playerLoading.value = false
  // Restore volume on load
  if (videoEl.value) videoEl.value.volume = volume.value
  // Apply default playback rate once per video
  const rate = Number(appSettings.value.default_playback_rate)
  if (!defaultRateApplied && rate && rate !== 1) {
    defaultRateApplied = true
    playbackRate.value = rate
    videoEl.value.playbackRate = rate
    showAction(`${rate}x 倍速`)
  }
}

function onEnded() {
  if (appSettings.value.autoplay_next === false) {
    showAction('本集播放完毕')
    return
  }
  if (currentEpisodeIndex.value < episodes.value.length - 1) {
    const next = currentEpisodeIndex.value + 1
    switchEpisode(episodes.value[next], next)
  }
}

// ── Progress persistence ──────────────────────────────
async function saveProgress() {
  if (appSettings.value.record_history === false) return
  if (!siteKey.value || !vodId.value) return
  try {
    await saveHistory({
      site_key: siteKey.value,
      site_name: siteName.value,
      vod_id: vodId.value,
      vod_name: vodName.value,
      vod_pic: vodPic.value,
      episode_index: currentEpisodeIndex.value,
      episode_name: episodeName.value,
      current_time: Math.floor(currentTime.value),
      duration: Math.floor(duration.value),
      play_url: currentPlayUrl.value,
      api_url: apiUrl.value,
    })
  } catch {}
}

async function restoreProgress() {
  try {
    const { data } = await getHistory()
    const record = data.find(r =>
      r.site_key === siteKey.value && String(r.vod_id) === String(vodId.value)
    )
    if (record && record.current_time > 10) {
      videoEl.value.currentTime = record.current_time
      showAction(`已从 ${formatTime(record.current_time)} 继续播放`)
    }
  } catch {}
}

function formatTime(sec) {
  if (!sec || isNaN(sec)) return '00:00'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = Math.floor(sec % 60)
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}
</script>

<style scoped>
.action-toast-enter-active { transition: all 0.2s ease; }
.action-toast-leave-active { transition: all 0.3s ease; }
.action-toast-enter-from { opacity: 0; transform: translate(-50%, -50%) scale(0.8); }
.action-toast-leave-to { opacity: 0; transform: translate(-50%, -50%) scale(1.1); }
.buffer-fade-enter-active { transition: opacity 0.2s ease; }
.buffer-fade-leave-active { transition: opacity 0.5s ease; }
.buffer-fade-enter-from, .buffer-fade-leave-to { opacity: 0; }
</style>
