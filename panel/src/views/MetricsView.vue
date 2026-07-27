<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { api } from '../lib/api.js';
import { t } from '../lib/i18n.js';
import { num } from '../lib/format.js';
import { COLOR, toPoints, toBand } from '../lib/charts.js';
import LineChart from '../components/LineChart.vue';
import PanelCard from '../components/PanelCard.vue';
import EmptyState from '../components/EmptyState.vue';

/* Every in-memory series on one page, over a window you choose.
 *
 * The Live view answers "is it healthy right now". This answers the question
 * that comes next -- "since when, and what else moved at the same time" --
 * which needs the same time axis under every chart. */

const RANGES = [
  { id: '5m', window: 300000, key: 'metrics.range.5m' },
  { id: '1h', window: 3600000, key: 'metrics.range.1h' },
  { id: '6h', window: 21600000, key: 'metrics.range.6h' },
  { id: '24h', window: 86400000, key: 'metrics.range.24h' },
];

const CHARTS = [
  { name: 'tps', key: 'metrics.tps', color: COLOR.lichen, min: 0, max: 20, decimals: 2 },
  { name: 'mspt', key: 'metrics.mspt', color: COLOR.amber, min: 0, decimals: 1, unit: ' ms' },
  { name: 'heap', key: 'metrics.heap', color: COLOR.lapis, min: 0, max: 100, unit: '%' },
  { name: 'cpu', key: 'metrics.cpu', color: COLOR.redstone, min: 0, max: 100, unit: '%' },
  { name: 'players', key: 'metrics.players', color: COLOR.violet, min: 0 },
  { name: 'chunks', key: 'metrics.chunks', color: COLOR.teal, min: 0 },
  { name: 'entities', key: 'metrics.entities', color: COLOR.ash, min: 0 },
];

const range = ref('1h');
const band = ref(true);
const failure = ref('');
const loaded = ref(false);
const data = reactive({ series: {} });

const window_ = computed(() => RANGES.find((r) => r.id === range.value)?.window ?? 3600000);

/** One chart's series, in the shape LineChart wants. */
function seriesFor(chart) {
  const raw = data.series[chart.name];
  return [{
    points: toPoints(raw),
    band: toBand(raw),
    color: chart.color,
    label: t(chart.key),
  }];
}

function bucketSeconds(chart) {
  const raw = data.series[chart.name];
  return raw?.bucketMillis ? Math.round(raw.bucketMillis / 1000) : 0;
}

/** The latest value, which is what the card's heading is actually reporting. */
function latest(chart) {
  const raw = data.series[chart.name];
  if (!raw?.avg?.length) return null;
  return raw.avg[raw.avg.length - 1];
}

const anyData = computed(() => CHARTS.some((chart) => data.series[chart.name]?.t?.length));

async function load() {
  try {
    const names = CHARTS.map((chart) => chart.name).join(',');
    const payload = await api.get(`/api/series?names=${names}&window=${window_.value}`);
    data.series = payload.series ?? {};
    failure.value = '';
  } catch (error) {
    failure.value = error.message;
  } finally {
    loaded.value = true;
  }
}

// A 24-hour window does not need refreshing every ten seconds, and a five-minute
// one is useless if it does not.
const refreshMs = computed(() => (window_.value <= 300000 ? 5000 : 30000));

let timer = null;

function restart() {
  clearInterval(timer);
  load();
  timer = setInterval(load, refreshMs.value);
}

watch(range, restart);
onMounted(restart);
onBeforeUnmount(() => clearInterval(timer));
</script>

<template>
  <div class="view">
    <div class="view__head">
      <h1 class="view__title">{{ t('metrics.title') }}</h1>
      <p class="view__note">{{ t('metrics.note') }}</p>
    </div>

    <div class="controls">
      <select v-model="range" class="input" :aria-label="t('metrics.range')">
        <option v-for="option in RANGES" :key="option.id" :value="option.id">
          {{ t(option.key) }}
        </option>
      </select>
      <label class="link-state">
        <input v-model="band" type="checkbox"> {{ t('metrics.band') }}
      </label>
    </div>

    <EmptyState v-if="failure" :title="t('metrics.failure')" :detail="failure" />

    <EmptyState v-else-if="loaded && !anyData" :title="t('metrics.empty')"
                :detail="t('metrics.emptyDetail')" />

    <div v-else class="grid grid--wide">
      <PanelCard v-for="chart in CHARTS" :key="chart.name" :title="t(chart.key)"
                 :aside="latest(chart) === null
                   ? t('metrics.bucket', { seconds: bucketSeconds(chart) })
                   : `${num(latest(chart), chart.decimals ?? 0)}${chart.unit ?? ''}`">
        <LineChart :series="seriesFor(chart)" :min="chart.min" :max="chart.max"
                   :decimals="chart.decimals ?? 0" :unit="chart.unit ?? ''" :band="band" />
      </PanelCard>
    </div>
  </div>
</template>
