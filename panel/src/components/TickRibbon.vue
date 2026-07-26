<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { drawRibbon } from '../lib/charts.js';

const props = defineProps({
  ticks: { type: Array, required: true },
  barWidth: { type: Number, default: 3 },
  gap: { type: Number, default: 1 },
  variant: { type: String, default: 'brand' },
});

const canvas = ref(null);

function paint() {
  drawRibbon(canvas.value, props.ticks, { barWidth: props.barWidth, gap: props.gap });
}

// Deep watching an array of 200 numbers that changes every second would be
// wasteful; the length plus the last value is enough to know a repaint is due.
watch(() => [props.ticks.length, props.ticks.at(-1)], paint);
onMounted(() => {
  paint();
  window.addEventListener('resize', paint);
});
onBeforeUnmount(() => window.removeEventListener('resize', paint));
</script>

<template>
  <canvas ref="canvas" class="ribbon" :class="`ribbon--${variant}`" aria-hidden="true" />
</template>
