<template>
  <div class="p-3 sm:p-4 min-h-full">
    <div class="flex items-center gap-2 mb-4">
      <h2 class="text-lg font-semibold">观看历史</h2>
      <span v-if="records.length" class="text-sm text-gray-500">{{ records.length }} 条</span>
      <div class="flex-1"></div>
      <button v-if="records.length" @click="clearAll"
        class="text-xs text-gray-500 hover:text-red-400 transition">清空历史</button>
    </div>

    <!-- Loading skeleton -->
    <div v-if="loading" class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
      <div v-for="i in 6" :key="i" class="animate-pulse">
        <div class="bg-dark-600 rounded-lg aspect-[2/3] mb-2"></div>
        <div class="bg-dark-600 rounded h-4 w-3/4 mb-1"></div>
        <div class="bg-dark-600 rounded h-3 w-1/2"></div>
      </div>
    </div>

    <template v-else-if="records.length">
      <div v-for="g in groups" :key="g.label" class="mb-6">
        <h3 class="text-xs text-gray-500 font-semibold mb-2">{{ g.label }}</h3>
        <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
          <div v-for="r in g.items" :key="r.site_key + '-' + r.vod_id"
            class="relative group cursor-pointer" @click="resume(r)">
            <div class="relative aspect-[2/3] rounded-lg overflow-hidden bg-dark-600 mb-1.5">
              <img v-if="r.vod_pic" :src="`/api/proxy/image?url=${encodeURIComponent(r.vod_pic)}`"
                class="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
                loading="lazy" @error="$event.target.style.display='none'" />
              <div v-else class="w-full h-full flex items-center justify-center text-gray-600">
                <svg class="w-10 h-10" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
                    d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"/>
                </svg>
              </div>
              <!-- Hover overlay: resume hint -->
              <div class="absolute inset-0 bg-black/0 group-hover:bg-black/30 transition-all flex items-center justify-center">
                <svg class="w-10 h-10 text-white opacity-0 group-hover:opacity-100 transition drop-shadow-lg"
                  fill="currentColor" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
              </div>
              <!-- Delete badge -->
              <button @click.stop="removeRecord(r)" title="删除记录"
                class="absolute top-1.5 right-1.5 p-1.5 bg-black/60 rounded-full text-gray-300 hover:text-red-400
                       opacity-0 group-hover:opacity-100 focus:opacity-100 transition">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>
                </svg>
              </button>
            </div>
            <p class="text-xs text-gray-200 truncate group-hover:text-accent-light transition">{{ r.vod_name }}</p>
            <p class="text-[10px] text-gray-500 truncate">
              <template v-if="finished(r)">已看完</template>
              <template v-else>看到 第{{ (r.episode_index || 0) + 1 }}集 {{ fmt(r.current_time) }} / {{ fmt(r.duration) }}</template>
            </p>
            <div class="h-1 bg-dark-600 rounded-full mt-1 overflow-hidden">
              <div class="h-full rounded-full" :class="finished(r) ? 'bg-green-500' : 'bg-red-500'"
                :style="{ width: pct(r) + '%' }"></div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- Empty -->
    <EmptyState v-else text="还没有观看记录" action-text="去首页" @action="$router.push('/')" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getHistory, deleteHistory, clearHistory, siteLookup } from '../api'
import EmptyState from '../components/EmptyState.vue'

const emit = defineEmits(['toast'])
const router = useRouter()

const records = ref([])
const loading = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const { data } = await getHistory()
    records.value = data || []
  } catch (e) {
    emit('toast', { message: `加载历史失败: ${e.message}`, type: 'error' })
  } finally {
    loading.value = false
  }
}

const groups = computed(() => {
  const now = new Date()
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const weekStart = startToday - 6 * 86400000
  const buckets = { '今天': [], '7天内': [], '更早': [] }
  for (const r of records.value) {
    const t = new Date(r.updated_at || 0).getTime()
    if (t >= startToday) buckets['今天'].push(r)
    else if (t >= weekStart) buckets['7天内'].push(r)
    else buckets['更早'].push(r)
  }
  return Object.entries(buckets)
    .filter(([, items]) => items.length)
    .map(([label, items]) => ({ label, items }))
})

function pct(r) {
  if (!r.duration) return 0
  return Math.min(100, Math.round((r.current_time / r.duration) * 100))
}
function finished(r) {
  return pct(r) >= 95
}
function fmt(sec) {
  if (!sec || isNaN(sec)) return '00:00'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = Math.floor(sec % 60)
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

async function resolveApiUrl(r) {
  if (r.api_url) return r.api_url
  const { data } = await siteLookup({ site_key: r.site_key })
  return data.api_url
}

async function resume(r) {
  try {
    const apiUrl = await resolveApiUrl(r)
    if (!apiUrl) throw new Error('未找到站点')

    if (r.play_url) {
      // New record: one-click resume at the exact episode + position
      router.push({
        path: '/player',
        query: {
          api_url: apiUrl,
          site_key: r.site_key || '',
          site_name: r.site_name || '',
          vod_id: String(r.vod_id),
          vod_name: r.vod_name || '',
          vod_pic: r.vod_pic || '',
          episode_index: r.episode_index || 0,
          episode_name: r.episode_name || '',
          play_url: r.play_url,
        },
      })
    } else {
      // Legacy record: open the detail modal so the user picks an episode
      emit('toast', { message: '旧记录没有集数信息，请重新选集播放', type: 'info' })
      router.push({
        path: '/',
        query: {
          api_url: apiUrl,
          vod_id: String(r.vod_id),
          vod_name: r.vod_name || '',
          vod_pic: r.vod_pic || '',
          site_key: r.site_key || '',
          site_name: r.site_name || '',
        },
      })
    }
  } catch (e) {
    emit('toast', { message: '该记录所属站点已失效，无法续播', type: 'error' })
  }
}

async function removeRecord(r) {
  try {
    await deleteHistory(r.site_key, String(r.vod_id))
    records.value = records.value.filter(x => !(x.site_key === r.site_key && String(x.vod_id) === String(r.vod_id)))
    emit('toast', { message: '已删除记录', type: 'info' })
  } catch (e) {
    emit('toast', { message: `删除失败: ${e.message}`, type: 'error' })
  }
}

async function clearAll() {
  if (!confirm('确定清空全部观看历史？')) return
  try {
    await clearHistory()
    records.value = []
    emit('toast', { message: '历史已清空', type: 'success' })
  } catch (e) {
    emit('toast', { message: `清空失败: ${e.message}`, type: 'error' })
  }
}
</script>
