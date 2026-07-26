<script setup>
/* An empty screen is an invitation to act, so every one of these says what to
   do next rather than just reporting that there is nothing here. */
defineProps({
  title: { type: String, required: true },
  detail: { type: String, default: '' },
});
</script>

<template>
  <div class="empty">
    <b>{{ title }}</b>
    <template v-if="detail">{{ detail }}</template>
    <slot />
  </div>
</template>
