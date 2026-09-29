<template>
  <div class="cursor-pointer group" @click="$emit('click')">
    <div class="relative aspect-[2/3] rounded-lg overflow-hidden bg-dark-600 mb-1.5">
      <img v-if="proxiedPic" :src="proxiedPic" :alt="video.vod_name"
        class="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
        loading="lazy"
        @error="$event.target.style.display='none'" />
      <div v-if="!proxiedPic"
        class="w-full h-full flex items-center justify-center text-gray-600">
        <svg class="w-10 h-10" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
            d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"/>
        </svg>
      </div>
      <!-- Hover overlay -->
      <div class="absolute inset-0 bg-black/0 group-hover:bg-black/30 transition-all flex items-center justify-center">
        <svg class="w-10 h-10 text-white opacity-0 group-hover:opacity-100 transition drop-shadow-lg"
          fill="currentColor" viewBox="0 0 24 24">
          <path d="M8 5v14l11-7z"/>
        </svg>
      </div>
      <!-- Score/remark badge -->
      <div v-if="video.vod_score || video.vod_remarks"
        class="absolute top-1.5 right-1.5 px-1.5 py-0.5 bg-black/60 rounded text-[10px] text-amber-400 font-medium">
        {{ video.vod_score || video.vod_remarks }}
      </div>
    </div>
    <p class="text-xs text-gray-200 truncate group-hover:text-accent-light transition">
      {{ video.vod_name }}
    </p>
    <p class="text-[10px] text-gray-500 truncate">
      {{ video.vod_year }} {{ video.vod_area }}
    </p>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  video: { type: Object, required: true },
  site: Object,
})
defineEmits(['click'])

const proxiedPic = computed(() => {
  const pic = props.video.vod_pic
  if (!pic) return ''
  // Route through backend image proxy to bypass hotlink protection
  return `/api/proxy/image?url=${encodeURIComponent(pic)}`
})
</script>
