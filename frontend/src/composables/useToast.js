/**
 * Lightweight toast notification composable.
 * Usage: const { toasts, toast } = useToast()
 * Then in template: <ToastContainer :toasts="toasts" />
 */
import { ref } from 'vue'

const toasts = ref([])
let nextId = 0

export function useToast() {
  function toast(message, type = 'info', duration = 3000) {
    const id = nextId++
    toasts.value.push({ id, message, type })
    setTimeout(() => {
      toasts.value = toasts.value.filter((t) => t.id !== id)
    }, duration)
  }

  return {
    toasts,
    toast,
    success: (msg, dur) => toast(msg, 'success', dur),
    error: (msg, dur) => toast(msg, 'error', dur || 4000),
    info: (msg, dur) => toast(msg, 'info', dur),
  }
}
