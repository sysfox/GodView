<script setup>
/* Colour is reserved for status. A stat with nothing wrong with it stays plain,
   which is what lets a red one carry meaning. */
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], required: true },
  unit: { type: String, default: '' },
  meta: { type: String, default: '' },
  tone: { type: String, default: '' },
});
</script>

<template>
  <section class="panel stat" :class="tone ? `stat--${tone}` : ''">
    <div class="panel__head"><h2 class="panel__title">{{ label }}</h2></div>
    <div class="stat__value">
      {{ value }}<span v-if="unit" class="stat__unit">{{ unit }}</span>
    </div>
    <div v-if="meta" class="stat__meta">{{ meta }}</div>
  </section>
</template>
