<template>
  <div class="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[100]"
    @click.self="$emit('close')">
    <div class="bg-dark-700 border border-dark-500 rounded-xl w-[480px] max-w-[95vw] max-h-[80vh] overflow-y-auto shadow-2xl">
      <div class="p-5">
        <h2 class="text-lg font-semibold mb-4 flex items-center gap-2">
          <svg class="w-5 h-5 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/>
          </svg>
          添加影视源
        </h2>

        <div class="space-y-3">
          <div>
            <label class="block text-sm text-gray-400 mb-1">源名称</label>
            <input v-model="name" type="text" placeholder="例：饭太硬"
              @keyup.enter="addSource"
              class="w-full bg-dark-600 border border-dark-500 rounded-lg px-3 py-2 text-sm
                     focus:outline-none focus:border-accent transition" />
          </div>
          <div>
            <label class="block text-sm text-gray-400 mb-1">源地址</label>
            <input v-model="url" type="text" placeholder="http://xxx.tv/tvbox.json"
              @keyup.enter="addSource"
              class="w-full bg-dark-600 border border-dark-500 rounded-lg px-3 py-2 text-sm
                     focus:outline-none focus:border-accent transition" />
          </div>
        </div>

        <!-- Quick presets -->
        <div class="mt-4">
          <div class="text-xs text-gray-500 mb-2">快速添加</div>
          <div class="flex flex-wrap gap-1.5">
            <button v-for="p in presets" :key="p.url"
              @click="name = p.name; url = p.url"
              class="px-2.5 py-1 bg-dark-600 rounded-md text-xs text-gray-300
                     hover:bg-accent/20 hover:text-accent-light transition">
              {{ p.name }}
            </button>
          </div>
        </div>

        <div v-if="error" class="mt-3 text-sm text-red-400">{{ error }}</div>

        <div class="flex justify-end gap-2 mt-5">
          <button @click="$emit('close')"
            class="px-4 py-2 text-sm text-gray-400 hover:text-white transition">
            取消
          </button>
          <button @click="addSource" :disabled="loading"
            class="px-4 py-2 bg-accent hover:bg-accent-dark text-white rounded-lg text-sm
                   disabled:opacity-50 transition">
            {{ loading ? '添加中...' : '添加' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { addSource as apiAddSource } from '../api'

const emit = defineEmits(['close', 'added'])

const name = ref('')
const url = ref('')
const loading = ref(false)
const error = ref('')

const presets = [
  { name: '讴歌多仓', url: 'http://tv.nxog.top/m/' },
  { name: '饭太硬', url: 'http://www.饭太硬.com/tv/' },
  { name: '肥猫', url: 'http://肥猫.com' },
  { name: '饭太硬(stable)', url: 'https://饭太硬.top/tvbox.json' },
]

async function addSource() {
  if (!name.value.trim() || !url.value.trim()) {
    error.value = '请填写名称和地址'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await apiAddSource({ name: name.value.trim(), url: url.value.trim() })
    emit('added')
  } catch (e) {
    error.value = e.message || '添加失败'
  } finally {
    loading.value = false
  }
}
</script>
