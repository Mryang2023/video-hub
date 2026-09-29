<template>
  <div class="fixed top-4 right-4 z-[200] flex flex-col gap-2 pointer-events-none">
    <transition-group name="toast">
      <div v-for="t in toasts" :key="t.id"
        class="pointer-events-auto px-4 py-2.5 rounded-lg shadow-lg text-sm max-w-sm
               backdrop-blur-md border transition-all"
        :class="{
          'bg-green-900/80 border-green-700/50 text-green-200': t.type === 'success',
          'bg-red-900/80 border-red-700/50 text-red-200': t.type === 'error',
          'bg-dark-700/90 border-dark-500/50 text-gray-200': t.type === 'info',
        }">
        {{ t.message }}
      </div>
    </transition-group>
  </div>
</template>

<script setup>
defineProps({ toasts: { type: Array, default: () => [] } })
</script>

<style scoped>
.toast-enter-active { transition: all 0.3s ease; }
.toast-leave-active { transition: all 0.2s ease; }
.toast-enter-from { opacity: 0; transform: translateX(30px); }
.toast-leave-to { opacity: 0; transform: translateX(30px); }
</style>
