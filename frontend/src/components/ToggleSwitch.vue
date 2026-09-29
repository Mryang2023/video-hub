<template>
  <button type="button" role="switch" :aria-checked="modelValue" @click="toggle"
    class="relative w-10 h-5.5 rounded-full transition-colors shrink-0"
    :class="modelValue ? 'bg-accent' : 'bg-dark-500'"
    style="height: 22px">
    <span class="absolute top-0.5 left-0.5 w-4.5 h-4.5 rounded-full bg-white transition-transform shadow"
      :style="{ transform: modelValue ? 'translateX(18px)' : 'translateX(0)', width: '18px', height: '18px' }"></span>
  </button>
</template>

<script setup>
const props = defineProps({
  modelValue: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'change'])

function toggle() {
  emit('update:modelValue', !props.modelValue)
  emit('change', !props.modelValue)
}
</script>
