<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { drawLine, pointAt } from '../lib/charts.js';
import { clock } from '../lib/format.js';
import { i18n } from '../lib/i18n.js';

const props = defineProps({
  series: { type: Array, required: true },
  min: { type: Number, default: undefined },
  max: { type: Number, default: undefined },
  decimals: { type: Number, default: 0 },
  unit: { type: String, default: '' },
  /** Shade each bucket's min/max, so an average cannot hide a dip. */
  band: { type: Boolean, default: false },
  area: { type: Boolean, default: true },
  /** Only worth drawing when more than one series shares the frame. */
  legend: { type: Boolean, default: false },
});

const canvas = ref(null);
const wrap = ref(null);
const hoverX = ref(null);
const readout = ref(null);

let layout = null;

function shaped() {
  return props.series.map((line) => ({
    ...line,
    band: props.band ? line.band : undefined,
  }));
}

function paint() {
  layout = drawLine(canvas.value, shaped(), {
    min: props.min,
    max: props.max,
    decimals: props.decimals,
    area: props.area,
    hoverX: hoverX.value,
  });
  if (hoverX.value === null) {
    readout.value = null;
    return;
  }
  const hits = pointAt(props.series, layout, hoverX.value);
  readout.value = hits.length
    ? { at: hits[0].point.t, values: hits.map((h) => ({ line: h.line, v: h.point.v })) }
    : null;
}

function onMove(event) {
  const box = canvas.value?.getBoundingClientRect();
  if (!box) return;
  hoverX.value = event.clientX - box.left;
  paint();
}

function onLeave() {
  hoverX.value = null;
  paint();
}

const format = (value) => `${Number(value).toFixed(props.decimals)}${props.unit}`;

// Deep, because the series arrays are replaced wholesale on every reload but
// their contents are what changed. And on locale, because the axis says "now".
watch(() => props.series, paint, { deep: true });
watch(() => i18n.locale, paint);

onMounted(() => {
  paint();
  window.addEventListener('resize', paint);
});
onBeforeUnmount(() => window.removeEventListener('resize', paint));
</script>

<template>
  <div ref="wrap" class="chartwrap" @pointermove="onMove" @pointerleave="onLeave">
    <canvas ref="canvas" class="chart" />

    <div v-if="readout" class="chartip"
         :style="{ left: `${Math.min(hoverX + 12, (wrap?.clientWidth ?? 0) - 130)}px` }">
      <div class="chartip__at">{{ clock(readout.at) }}</div>
      <div v-for="(entry, index) in readout.values" :key="index" class="chartip__row">
        <i :style="{ background: entry.line.color }" />
        <span v-if="entry.line.label">{{ entry.line.label }}</span>
        <b>{{ format(entry.v) }}</b>
      </div>
    </div>

    <div v-if="legend" class="chartlegend">
      <span v-for="(line, index) in series" :key="index">
        <i :style="{ background: line.color }" />{{ line.label }}
      </span>
    </div>
  </div>
</template>
