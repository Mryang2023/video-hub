<template>
  <div class="p-3 sm:p-4 min-h-full">
    <div class="flex items-center gap-2 mb-4">
      <h2 class="text-lg font-semibold">我的收藏</h2>
      <span v-if="favs.length" class="text-sm text-gray-500">{{ favs.length }} 部</span>
    </div>

    <!-- Loading skeleton -->
    <div v-if="loading" class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
      <div v-for="i in 6" :key="i" class="animate-pulse">
        <div class="bg-dark-600 rounded-lg aspect-[2/3] mb-2"></div>
        <div class="bg-dark-600 rounded h-4 w-3/4"></div>
      </div>
    </div>

    <!-- Grid -->
    <div v-else-if="favs.length" class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
      <div v-for="f in favs" :key="f.site_key + '-' + f.vod_id" class="relative group">
        <VideoCard :video="f" @click="openDetail(f)" />
        <button @click.stop="removeFav(f)" title="取消收藏"
          class="absolute top-1.5 left-1.5 p-1.5 bg-black/60 rounded-full text-amber-400 hover:text-red-400
                 opacity-0 group-hover:opacity-100 focus:opacity-100 transition">
          <svg class="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 24 24">
            <path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"/>
          </svg>
        </button>
      </div>
    </div>

    <!-- Empty -->
    <EmptyState v-else icon="star" text="还没有收藏，去首页发现好片"
      action-text="去首页" @action="$router.push('/')" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getFavorites, deleteFavorite, siteLookup } from '../api'
import VideoCard from '../components/VideoCard.vue'
import EmptyState from '../components/EmptyState.vue'

const emit = defineEmits(['toast'])
const router = useRouter()

const favs = ref([])
const loading = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const { data } = await getFavorites()
    favs.value = data || []
  } catch (e) {
    emit('toast', { message: `加载收藏失败: ${e.message}`, type: 'error' })
  } finally {
    loading.value = false
  }
}

async function resolveApiUrl(f) {
  if (f.api_url) return f.api_url
  const { data } = await siteLookup({ site_key: f.site_key })
  return data.api_url
}

async function openDetail(f) {
  try {
    const apiUrl = await resolveApiUrl(f)
    if (!apiUrl) throw new Error('未找到站点')
    // HomeView watches route.query and opens the detail modal
    router.push({
      path: '/',
      query: {
        api_url: apiUrl,
        vod_id: String(f.vod_id),
        vod_name: f.vod_name || '',
        vod_pic: f.vod_pic || '',
        site_key: f.site_key || '',
        site_name: f.site_name || '',
      },
    })
  } catch (e) {
    emit('toast', { message: '该站点已失效，请取消收藏后重新收藏', type: 'error' })
  }
}

async function removeFav(f) {
  try {
    await deleteFavorite(f.site_key, String(f.vod_id))
    favs.value = favs.value.filter(x => !(x.site_key === f.site_key && String(x.vod_id) === String(f.vod_id)))
    emit('toast', { message: '已取消收藏', type: 'info' })
  } catch (e) {
    emit('toast', { message: `取消收藏失败: ${e.message}`, type: 'error' })
  }
}
</script>
