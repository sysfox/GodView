<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { drawLine } from '../lib/charts.js';

const props = defineProps({
  series: { type: Array, required: true },
  min: { type: Number, default: undefined },
  max: { type: Number, default: undefined },
  decimals: { type: Number, default: 0 },
});

const canvas = ref(null);

function paint() {
  drawLine(canvas.value, props.series, {
    min: props.min,
    max: props.max,
    decimals: props.decimals,
  });
}

watch(() => props.series, paint, { deep: true });
onMounted(() => {
  paint();
  window.addEventListener('resize', paint);
});
onBeforeUnmount(() => window.removeEventListener('resize', paint));
</script>

<template>
  <canvas ref="canvas" class="chart" />
</template>
