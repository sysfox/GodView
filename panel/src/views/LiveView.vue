<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { state, on, noteSpike } from '../lib/store.js';
import { api } from '../lib/api.js';
import { num, bytes, since, clock } from '../lib/format.js';
import { COLOR, toPoints, TICK_WARN, TICK_BAD } from '../lib/charts.js';
import TickRibbon from '../components/TickRibbon.vue';
import LineChart from '../components/LineChart.vue';
import StatCard from '../components/StatCard.vue';
import PanelCard from '../components/PanelCard.vue';
import StatusTag from '../components/StatusTag.vue';
import EmptyState from '../components/EmptyState.vue';

const tpsSeries = ref([]);
const heapSeries = ref([]);
const spikes = ref([]);
const worstTick = computed(() => (state.ticks.length ? Math.max(...state.ticks) : 0));

/* The hero is a sentence, not a number. At 2am "ShopGUI stalled the server for
   812 ms" is worth more than any gauge on the page, so the verdict leads and
   the stats support it. */
const verdict = computed(() => {
  const pulse = state.pulse;
  if (!pulse) return { tone: 'ok', label: 'Starting', line: 'Waiting for the first sample.' };

  let tone = 'ok';
  let label = 'Healthy';
  if (pulse.tps1 < 15 || worstTick.value >= 500) {
    tone = 'bad';
    label = 'Struggling';
  } else if (pulse.tps1 < 19 || worstTick.value >= TICK_BAD) {
    tone = 'warn';
    label = 'Slipping';
  }

  return {
    tone,
    label,
    line: `${num(Math.min(pulse.tps1, 20), 2)} TPS, worst tick `
      + `${Math.round(worstTick.value)} ms in the last ten seconds.`,
  };
});

const blame = computed(() => {
  const spike = state.lastSpike;
  if (!spike || spike.blame === 'unknown') return null;
  if (Date.now() - spike.at > 3600000) return null;
  return spike;
});

const tpsTone = computed(() => {
  if (!state.pulse) return '';
  if (state.pulse.tps1 < 15) return 'bad';
  if (state.pulse.tps1 < 19) return 'warn';
  return 'good';
});

const heapTone = computed(() => {
  const pct = state.pulse?.heapPercent ?? 0;
  if (pct > 92) return 'bad';
  if (pct > 80) return 'warn';
  return '';
});

async function loadCharts() {
  try {
    const data = await api.get('/api/series?names=tps,heap&window=3600000');
    tpsSeries.value = [{ points: toPoints(data.series?.tps), color: COLOR.lichen }];
    heapSeries.value = [{ points: toPoints(data.series?.heap), color: COLOR.lapis }];
  } catch {
    // The rest of the view is still useful without history.
  }
}

async function loadSpikes() {
  try {
    const data = await api.get('/api/spikes?live=1&limit=12');
    spikes.value = (data.spikes ?? []).slice().reverse();
    if (spikes.value.length) noteSpike(spikes.value[0]);
  } catch {
    spikes.value = [];
  }
}

let chartTimer = null;
let offSnapshot = null;

onMounted(() => {
  loadCharts();
  loadSpikes();
  chartTimer = setInterval(loadCharts, 30000);
  offSnapshot = on('snapshot', loadSpikes);
});

onBeforeUnmount(() => {
  clearInterval(chartTimer);
  offSnapshot?.();
});
</script>

<template>
  <div class="view">
    <div class="view__head">
      <h1 class="view__title">Live</h1>
      <p class="view__note">
        Profile {{ state.snapshot?.performance?.profile ?? '--' }}
        <template v-if="state.pulse"> · updated {{ clock(state.pulse.at) }}</template>
      </p>
    </div>

    <section class="verdict" :data-state="verdict.tone">
      <div class="verdict__state">{{ verdict.label }}</div>
      <p class="verdict__line">
        {{ verdict.line }}
        <template v-if="blame">
          <b>{{ blame.blame }}</b> stalled the server for
          {{ Math.round(blame.durationMs) }} ms, {{ since(blame.at) }}.
        </template>
        <template v-else-if="verdict.tone === 'ok'">
          No plugin has stalled the server recently.
        </template>
      </p>

      <TickRibbon :ticks="state.ticks" variant="hero" :bar-width="3" :gap="1" />

      <div class="ribbon-legend">
        <span><i />under 50 ms</span>
        <span class="is-warn"><i />over {{ TICK_WARN }} ms</span>
        <span class="is-bad"><i />over {{ TICK_BAD }} ms</span>
      </div>
    </section>

    <div v-if="state.pulse" class="grid">
      <StatCard label="Ticks per second" :value="num(Math.min(state.pulse.tps1, 20), 2)"
                unit="/ 20" :tone="tpsTone"
                :meta="`${num(state.pulse.overrunRatio * 100, 1)}% of ticks ran long`" />
      <StatCard label="Milliseconds per tick" :value="num(state.pulse.mspt, 2)" unit="ms"
                :tone="state.pulse.mspt > 45 ? 'bad' : state.pulse.mspt > 30 ? 'warn' : ''"
                :meta="`p95 tick period ${num(state.pulse.tickPeriodP95, 0)} ms`" />
      <StatCard label="Heap in use" :value="num(state.pulse.heapPercent, 0)" unit="%"
                :tone="heapTone"
                :meta="`${bytes(state.pulse.heapUsed)} of ${bytes(state.pulse.heapMax)}`" />
      <StatCard label="Players online" :value="state.pulse.players"
                :meta="`${state.pulse.chunks} chunks, ${state.pulse.entities} entities`" />
    </div>

    <div class="grid grid--wide">
      <PanelCard title="TPS, last hour">
        <LineChart :series="tpsSeries" :min="0" :max="20" />
      </PanelCard>
      <PanelCard title="Heap, last hour">
        <LineChart :series="heapSeries" :min="0" :max="100" />
      </PanelCard>
    </div>

    <div class="grid grid--wide">
      <PanelCard v-for="world in state.snapshot?.worlds ?? []" :key="world.name"
                 :title="world.name" :aside="world.environment.toLowerCase()">
        <div class="table-wrap">
          <table>
            <thead>
              <tr><th>Metric</th><th class="right">Value</th></tr>
            </thead>
            <tbody>
              <tr><td>Players</td><td class="right num">{{ world.players }}</td></tr>
              <tr><td>Loaded chunks</td><td class="right num">{{ world.chunks }}</td></tr>
              <tr><td>Entities</td><td class="right num">{{ world.entities }}</td></tr>
              <tr v-if="world.tileEntities >= 0">
                <td>Block entities</td><td class="right num">{{ world.tileEntities }}</td>
              </tr>
              <tr v-for="type in (world.topTypes ?? []).slice(0, 3)" :key="type.type">
                <td>{{ type.type.toLowerCase().replace(/_/g, ' ') }}</td>
                <td class="right num">{{ type.count }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>
    </div>

    <PanelCard title="Recent spikes" aside="from memory">
      <div v-if="spikes.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>When</th><th class="right">Duration</th><th>Blamed on</th><th>Top frame</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="spike in spikes" :key="`${spike.at}-${spike.tick}`">
              <td class="num">{{ clock(spike.at) }}</td>
              <td class="right num">{{ Math.round(spike.durationMs) }} ms</td>
              <td>
                <StatusTag :text="spike.blame"
                           :tone="spike.blame === 'Minecraft' ? '' : 'warn'" />
              </td>
              <td class="wrap num">{{ spike.blameFrame || '--' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState v-else title="No spikes recorded"
                  detail="Every tick has finished inside the threshold since the plugin started." />
    </PanelCard>
  </div>
</template>
