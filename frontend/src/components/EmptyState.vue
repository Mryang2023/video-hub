<template>
  <div class="flex flex-col items-center justify-center py-12 text-gray-500">
    <svg v-if="icon === 'star'" class="w-12 h-12 mb-3 text-dark-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
        d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.196-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118L2.01 9.411c-.783-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"/>
    </svg>
    <svg v-else-if="icon === 'clock'" class="w-12 h-12 mb-3 text-dark-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
        d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"/>
    </svg>
    <svg v-else class="w-12 h-12 mb-3 text-dark-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
        d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"/>
    </svg>
    <p class="text-sm">{{ text }}</p>
    <button v-if="actionText" @click="$emit('action')"
      class="mt-3 px-3 py-1.5 bg-dark-600 hover:bg-dark-500 rounded-lg text-xs text-gray-300 transition">
      {{ actionText }}
    </button>
  </div>
</template>

<script setup>
defineProps({
  text: { type: String, required: true },
  icon: { type: String, default: 'video' }, // 'video' | 'star' | 'clock'
  actionText: String,
})
defineEmits(['action'])
</script>
