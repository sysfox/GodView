<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { api } from '../lib/api.js';
import { num, bytes, duration } from '../lib/format.js';
import PanelCard from '../components/PanelCard.vue';
import StatusTag from '../components/StatusTag.vue';
import EmptyState from '../components/EmptyState.vue';

const system = ref(null);
const integrations = ref([]);
const gc = ref(null);
const failure = ref('');

async function load() {
  try {
    const [a, b, c] = await Promise.all([
      api.get('/api/system'),
      api.get('/api/integrations'),
      api.get('/api/gc'),
    ]);
    system.value = a;
    integrations.value = b.integrations ?? [];
    gc.value = c;
    failure.value = '';
  } catch (error) {
    failure.value = error.message;
  }
}

let timer = null;
onMounted(() => {
  load();
  timer = setInterval(load, 15000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>

<template>
  <div class="view">
    <div class="view__head">
      <h1 class="view__title">Diagnostics</h1>
      <p class="view__note">What GodView itself is doing.</p>
    </div>

    <EmptyState v-if="failure" title="Could not load diagnostics" :detail="failure" />

    <div v-else-if="system" class="grid grid--wide">
      <PanelCard title="Server">
        <div class="table-wrap">
          <table>
            <tbody>
              <tr>
                <td>Software</td>
                <td class="right num">{{ system.server.brand }} {{ system.server.version }}</td>
              </tr>
              <tr>
                <td>Java</td>
                <td class="right num wrap">{{ system.jvm.java }} · {{ system.jvm.vm }}</td>
              </tr>
              <tr>
                <td>Host</td>
                <td class="right num wrap">
                  {{ system.jvm.os }}, {{ system.jvm.processors }} cores
                </td>
              </tr>
              <tr><td>Uptime</td><td class="right num">{{ duration(system.jvm.uptimeMs) }}</td></tr>
              <tr>
                <td>Load average</td>
                <td class="right num">
                  {{ system.jvm.loadAverage >= 0 ? num(system.jvm.loadAverage, 2) : 'unavailable' }}
                </td>
              </tr>
              <tr>
                <td>Heap</td>
                <td class="right num">
                  {{ bytes(system.jvm.heapUsed) }} of {{ bytes(system.jvm.heapMax) }}
                </td>
              </tr>
              <tr>
                <td>Non-heap</td>
                <td class="right num">{{ bytes(system.jvm.nonHeapUsed) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard title="GodView" :aside="`v${system.godview.version}`">
        <div class="table-wrap">
          <table>
            <tbody>
              <tr><td>Profile</td><td class="right num">{{ system.godview.profile }}</td></tr>
              <tr><td>Live viewers</td><td class="right num">{{ system.godview.viewers }}</td></tr>
              <tr>
                <td>Stream frames</td>
                <td class="right num">
                  {{ system.godview.streamFramesPublished }} sent,
                  {{ system.godview.streamFramesDropped }} dropped
                </td>
              </tr>
              <tr>
                <td>HTTP requests</td>
                <td class="right num">
                  {{ system.godview.httpRequests }} ({{ system.godview.httpErrors }} failed)
                </td>
              </tr>
              <tr><td>Sessions</td><td class="right num">{{ system.godview.sessions }}</td></tr>
              <tr>
                <td>Sign-in failures</td>
                <td class="right num">
                  {{ system.godview.loginFailures }} ({{ system.godview.loginLockouts }} lockouts)
                </td>
              </tr>
              <tr>
                <td>Console capture</td>
                <td class="right num">
                  {{ system.godview.consoleAttached
                    ? `${system.godview.consoleLines} lines` : 'off' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard v-if="system.storage" title="Storage" :aside="system.storage.kind">
        <div class="table-wrap">
          <table>
            <tbody>
              <tr><td>Events</td><td class="right num">{{ system.storage.events }}</td></tr>
              <tr><td>Metrics</td><td class="right num">{{ system.storage.metrics }}</td></tr>
              <tr><td>Spikes</td><td class="right num">{{ system.storage.spikes }}</td></tr>
              <tr><td>Audit</td><td class="right num">{{ system.storage.audit }}</td></tr>
              <tr><td>Alerts</td><td class="right num">{{ system.storage.alerts }}</td></tr>
              <tr>
                <td>On disk</td>
                <td class="right num">{{ bytes(system.storage.fileSizeBytes) }}</td>
              </tr>
              <tr>
                <td>Queued / dropped</td>
                <td class="right num">
                  {{ system.storage.queued }} / {{ system.storage.dropped }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard v-if="gc" title="Garbage collection"
                 :aside="gc.usingNotifications ? 'JMX notifications' : 'polled'">
        <div class="table-wrap">
          <table>
            <tbody>
              <tr><td>Collections</td><td class="right num">{{ gc.totalCount }}</td></tr>
              <tr><td>Total pause</td><td class="right num">{{ gc.totalPauseMs }} ms</td></tr>
              <tr>
                <td>Worst recent</td>
                <td class="right num">{{ Math.round(gc.worstRecentPauseMs) }} ms</td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard title="Integrations">
        <div class="table-wrap">
          <table>
            <thead><tr><th>Plugin</th><th>State</th><th>Note</th></tr></thead>
            <tbody>
              <tr v-for="entry in integrations" :key="entry.id">
                <td>{{ entry.name }}</td>
                <td>
                  <StatusTag :text="entry.available ? 'connected' : 'absent'"
                             :tone="entry.available ? 'good' : ''" />
                </td>
                <td class="wrap">{{ entry.problem || entry.version }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard title="Plugins" :aside="`${system.plugins.length} installed`">
        <div class="table-wrap">
          <table>
            <thead><tr><th>Plugin</th><th>Version</th><th>State</th></tr></thead>
            <tbody>
              <tr v-for="plugin in system.plugins" :key="plugin.name">
                <td>{{ plugin.name }}</td>
                <td class="num">{{ plugin.version }}</td>
                <td>
                  <StatusTag :text="plugin.enabled ? 'enabled' : 'disabled'"
                             :tone="plugin.enabled ? 'good' : 'warn'" />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>
    </div>
  </div>
</template>
