<template>
  <div class="p-3 sm:p-4 min-h-full max-w-2xl mx-auto">
    <h2 class="text-lg font-semibold mb-4">设置</h2>

    <!-- Playback -->
    <section class="bg-dark-700 border border-dark-500 rounded-xl p-4 mb-4">
      <h3 class="text-sm font-semibold text-gray-300 mb-3">播放</h3>

      <div class="flex items-center justify-between py-2">
        <span class="text-sm text-gray-300">默认倍速</span>
        <select v-model.number="local.default_playback_rate" @change="save"
          class="bg-dark-600 border border-dark-500 rounded-lg px-2 py-1.5 text-sm text-gray-200 focus:outline-none focus:border-accent">
          <option v-for="r in [0.5, 0.75, 1, 1.25, 1.5, 2]" :key="r" :value="r">{{ r }}x</option>
        </select>
      </div>

      <div class="flex items-center justify-between py-2">
        <span class="text-sm text-gray-300">自动连播下一集</span>
        <ToggleSwitch v-model="local.autoplay_next" @change="save" />
      </div>

      <div class="flex items-center justify-between py-2">
        <span class="text-sm text-gray-300">记录观看历史</span>
        <ToggleSwitch v-model="local.record_history" @change="save" />
      </div>
    </section>

    <!-- Backup -->
    <section class="bg-dark-700 border border-dark-500 rounded-xl p-4 mb-4">
      <h3 class="text-sm font-semibold text-gray-300 mb-3">备份与恢复</h3>
      <div class="flex flex-wrap gap-2">
        <button @click="exportData"
          class="px-3 py-1.5 bg-dark-600 hover:bg-dark-500 rounded-lg text-xs text-gray-300 transition">
          导出配置
        </button>
        <label class="px-3 py-1.5 bg-dark-600 hover:bg-dark-500 rounded-lg text-xs text-gray-300 transition cursor-pointer">
          导入配置
          <input type="file" accept=".json" class="hidden" @change="importData" />
        </label>
      </div>
      <p class="text-[10px] text-gray-600 mt-2 leading-relaxed">
        导出内容包含影视源、收藏与设置；导入时按地址去重合并，不会覆盖删除现有数据。
      </p>
    </section>

    <!-- Data cleanup -->
    <section class="bg-dark-700 border border-dark-500 rounded-xl p-4">
      <h3 class="text-sm font-semibold text-gray-300 mb-3">数据清理</h3>
      <div class="flex flex-wrap gap-2">
        <button @click="clear('history')"
          class="px-3 py-1.5 bg-dark-600 hover:bg-dark-500 rounded-lg text-xs text-gray-300 transition">
          清空观看历史
        </button>
        <button @click="clear('favorites')"
          class="px-3 py-1.5 bg-dark-600 hover:bg-dark-500 rounded-lg text-xs text-gray-300 transition">
          清空收藏
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, onMounted } from 'vue'
import { getSettings, updateSettings, restoreBackup, clearHistory, getFavorites, deleteFavorite } from '../api'
import ToggleSwitch from '../components/ToggleSwitch.vue'

const emit = defineEmits(['toast'])

const local = reactive({
  default_playback_rate: 1.0,
  autoplay_next: true,
  record_history: true,
})

onMounted(async () => {
  try {
    const { data } = await getSettings()
    local.default_playback_rate = data.default_playback_rate ?? 1.0
    local.autoplay_next = data.autoplay_next !== false
    local.record_history = data.record_history !== false
  } catch {}
})

async function save() {
  try {
    await updateSettings({ ...local })
    emit('toast', { message: '设置已保存', type: 'success' })
  } catch (e) {
    emit('toast', { message: `保存失败: ${e.message}`, type: 'error' })
  }
}

async function exportData() {
  try {
    const res = await fetch('/api/backup')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const blob = await res.blob()
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'videohub-backup.json'
    a.click()
    URL.revokeObjectURL(a.href)
  } catch (e) {
    emit('toast', { message: `导出失败: ${e.message}`, type: 'error' })
  }
}

async function importData(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  if (!confirm('确认导入？将按地址去重合并，不会删除现有数据。')) return
  try {
    const text = await file.text()
    const payload = JSON.parse(text)
    const { data } = await restoreBackup(payload)
    emit('toast', { message: `导入完成：新增 ${data.sources} 个源、${data.favorites} 条收藏`, type: 'success' })
  } catch (err) {
    emit('toast', { message: `导入失败: ${err.message}`, type: 'error' })
  }
}

async function clear(kind) {
  const label = kind === 'history' ? '观看历史' : '收藏'
  if (!confirm(`确定清空全部${label}？此操作不可恢复。`)) return
  try {
    if (kind === 'history') {
      await clearHistory()
    } else {
      const { data } = await getFavorites()
      for (const f of data || []) {
        await deleteFavorite(f.site_key, String(f.vod_id))
      }
    }
    emit('toast', { message: `${label}已清空`, type: 'success' })
  } catch (e) {
    emit('toast', { message: `清空失败: ${e.message}`, type: 'error' })
  }
}
</script>
