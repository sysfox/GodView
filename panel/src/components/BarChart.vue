<script setup>
import { ref, watch, onMounted, onBeforeUnmount, computed } from 'vue';
import { drawBars } from '../lib/charts.js';
import { i18n } from '../lib/i18n.js';

/** A tally, not a trend: "which plugin stalled us, and how often". */
const props = defineProps({
  /** [{ label, value, color }] */
  entries: { type: Array, required: true },
  rowHeight: { type: Number, default: 22 },
});

const canvas = ref(null);
const height = computed(() => Math.max(48, props.entries.length * props.rowHeight));

function paint() {
  drawBars(canvas.value, props.entries);
}

watch(() => props.entries, paint, { deep: true });
watch(() => i18n.locale, paint);

onMounted(() => {
  paint();
  window.addEventListener('resize', paint);
});
onBeforeUnmount(() => window.removeEventListener('resize', paint));
</script>

<template>
  <canvas ref="canvas" class="chart chart--bars" :style="{ height: `${height}px` }" />
</template>
