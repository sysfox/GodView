<script setup>
defineProps({
  text: { type: [String, Number], required: true },
  tone: { type: String, default: '' },
});
</script>

<template>
  <span class="tag" :class="tone ? `tag--${tone}` : ''">{{ text }}</span>
</template>
