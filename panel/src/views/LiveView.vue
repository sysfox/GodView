<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { state, on, noteSpike } from '../lib/store.js';
import { api } from '../lib/api.js';
import { t } from '../lib/i18n.js';
import { num, bytes, since, clock } from '../lib/format.js';
import { COLOR, toPoints, toBand, TICK_WARN, TICK_BAD } from '../lib/charts.js';
import TickRibbon from '../components/TickRibbon.vue';
import LineChart from '../components/LineChart.vue';
import BarChart from '../components/BarChart.vue';
import StatCard from '../components/StatCard.vue';
import PanelCard from '../components/PanelCard.vue';
import StatusTag from '../components/StatusTag.vue';
import EmptyState from '../components/EmptyState.vue';

const tpsSeries = ref([]);
const heapSeries = ref([]);
const spikes = ref([]);
const byPlugin = ref([]);
const worstTick = computed(() => (state.ticks.length ? Math.max(...state.ticks) : 0));

/* The hero is a sentence, not a number. At 2am "ShopGUI stalled the server for
   812 ms" is worth more than any gauge on the page, so the verdict leads and
   the stats support it. */
const verdict = computed(() => {
  const pulse = state.pulse;
  if (!pulse) {
    return { tone: 'ok', label: t('verdict.starting'), line: t('verdict.waiting') };
  }

  let tone = 'ok';
  let label = t('verdict.healthy');
  if (pulse.tps1 < 15 || worstTick.value >= 500) {
    tone = 'bad';
    label = t('verdict.struggling');
  } else if (pulse.tps1 < 19 || worstTick.value >= TICK_BAD) {
    tone = 'warn';
    label = t('verdict.slipping');
  }

  return {
    tone,
    label,
    line: t('live.verdictLine', {
      tps: num(Math.min(pulse.tps1, 20), 2),
      ms: Math.round(worstTick.value),
    }),
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
    tpsSeries.value = [{
      points: toPoints(data.series?.tps),
      band: toBand(data.series?.tps),
      color: COLOR.lichen,
      label: t('metrics.tps'),
    }];
    heapSeries.value = [{
      points: toPoints(data.series?.heap),
      band: toBand(data.series?.heap),
      color: COLOR.lapis,
      label: t('metrics.heap'),
    }];
  } catch {
    // The rest of the view is still useful without history.
  }
}

async function loadSpikes() {
  try {
    const data = await api.get('/api/spikes?live=1&limit=12');
    spikes.value = (data.spikes ?? []).slice().reverse();
    if (spikes.value.length) noteSpike(spikes.value[0]);
    // A per-plugin tally is the number an operator acts on: one 800 ms spike is
    // bad luck, the same name forty times is a decision about that plugin.
    byPlugin.value = Object.entries(data.byPlugin ?? {})
      .map(([label, value]) => ({
        label,
        value,
        color: label === 'Minecraft' ? COLOR.slate : COLOR.amber,
      }));
  } catch {
    spikes.value = [];
    byPlugin.value = [];
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
      <h1 class="view__title">{{ t('live.title') }}</h1>
      <p class="view__note">
        {{ t('live.profile', { profile: state.snapshot?.performance?.profile ?? '--' }) }}
        <template v-if="state.pulse">
          {{ t('live.updated', { time: clock(state.pulse.at) }) }}
        </template>
      </p>
    </div>

    <section class="verdict" :data-state="verdict.tone">
      <div class="verdict__state">{{ verdict.label }}</div>
      <p class="verdict__line">
        {{ verdict.line }}
        <template v-if="blame">
          {{ t('live.blame', {
            plugin: blame.blame,
            ms: Math.round(blame.durationMs),
            ago: since(blame.at),
          }) }}
        </template>
        <template v-else-if="verdict.tone === 'ok'">
          {{ t('live.noStall') }}
        </template>
      </p>

      <TickRibbon :ticks="state.ticks" variant="hero" :bar-width="3" :gap="1" />

      <div class="ribbon-legend">
        <span><i />{{ t('live.legendUnder') }}</span>
        <span class="is-warn"><i />{{ t('live.legendOver', { ms: TICK_WARN }) }}</span>
        <span class="is-bad"><i />{{ t('live.legendOver', { ms: TICK_BAD }) }}</span>
      </div>
    </section>

    <div v-if="state.pulse" class="grid">
      <StatCard :label="t('live.tps')" :value="num(Math.min(state.pulse.tps1, 20), 2)"
                :unit="t('live.tpsUnit')" :tone="tpsTone"
                :meta="t('live.tpsMeta', { percent: num(state.pulse.overrunRatio * 100, 1) })" />
      <StatCard :label="t('live.mspt')" :value="num(state.pulse.mspt, 2)" unit="ms"
                :tone="state.pulse.mspt > 45 ? 'bad' : state.pulse.mspt > 30 ? 'warn' : ''"
                :meta="t('live.msptMeta', { ms: num(state.pulse.tickPeriodP95, 0) })" />
      <StatCard :label="t('live.heap')" :value="num(state.pulse.heapPercent, 0)" unit="%"
                :tone="heapTone"
                :meta="t('live.heapMeta', {
                  used: bytes(state.pulse.heapUsed),
                  max: bytes(state.pulse.heapMax),
                })" />
      <StatCard :label="t('live.playersOnline')" :value="state.pulse.players"
                :meta="t('live.playersMeta', {
                  chunks: state.pulse.chunks,
                  entities: state.pulse.entities,
                })" />
    </div>

    <div class="grid grid--wide">
      <PanelCard :title="t('live.tpsHour')">
        <LineChart :series="tpsSeries" :min="0" :max="20" :decimals="1" band />
      </PanelCard>
      <PanelCard :title="t('live.heapHour')">
        <LineChart :series="heapSeries" :min="0" :max="100" unit="%" band />
      </PanelCard>
    </div>

    <PanelCard v-if="byPlugin.length" :title="t('live.byPlugin')"
               :aside="t('live.fromMemory')">
      <BarChart :entries="byPlugin" />
    </PanelCard>

    <div class="grid grid--wide">
      <PanelCard v-for="world in state.snapshot?.worlds ?? []" :key="world.name"
                 :title="world.name" :aside="world.environment.toLowerCase()">
        <div class="table-wrap">
          <table>
            <thead>
              <tr><th>{{ t('live.metric') }}</th><th class="right">{{ t('live.value') }}</th></tr>
            </thead>
            <tbody>
              <tr>
                <td>{{ t('live.players') }}</td>
                <td class="right num">{{ world.players }}</td>
              </tr>
              <tr>
                <td>{{ t('live.loadedChunks') }}</td>
                <td class="right num">{{ world.chunks }}</td>
              </tr>
              <tr>
                <td>{{ t('live.entities') }}</td>
                <td class="right num">{{ world.entities }}</td>
              </tr>
              <tr v-if="world.tileEntities >= 0">
                <td>{{ t('live.blockEntities') }}</td>
                <td class="right num">{{ world.tileEntities }}</td>
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

    <PanelCard :title="t('live.recentSpikes')" :aside="t('live.fromMemory')">
      <div v-if="spikes.length" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{{ t('live.when') }}</th>
              <th class="right">{{ t('live.duration') }}</th>
              <th>{{ t('live.blamedOn') }}</th>
              <th>{{ t('live.topFrame') }}</th>
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
      <EmptyState v-else :title="t('live.noSpikes')" :detail="t('live.noSpikesDetail')" />
    </PanelCard>
  </div>
</template>
