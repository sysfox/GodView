<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { api } from '../lib/api.js';
import { t } from '../lib/i18n.js';
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
      <h1 class="view__title">{{ t('diag.title') }}</h1>
      <p class="view__note">{{ t('diag.note') }}</p>
    </div>

    <EmptyState v-if="failure" :title="t('diag.failure')" :detail="failure" />

    <div v-else-if="system" class="grid grid--wide">
      <PanelCard :title="t('diag.server')">
        <div class="table-wrap">
          <table>
            <tbody>
              <tr>
                <td>{{ t('diag.software') }}</td>
                <td class="right num">{{ system.server.brand }} {{ system.server.version }}</td>
              </tr>
              <tr>
                <td>{{ t('diag.java') }}</td>
                <td class="right num wrap">{{ system.jvm.java }} · {{ system.jvm.vm }}</td>
              </tr>
              <tr>
                <td>{{ t('diag.host') }}</td>
                <td class="right num wrap">
                  {{ system.jvm.os }}, {{ t('diag.cores', { count: system.jvm.processors }) }}
                </td>
              </tr>
              <tr>
                <td>{{ t('diag.uptime') }}</td>
                <td class="right num">{{ duration(system.jvm.uptimeMs) }}</td>
              </tr>
              <tr>
                <td>{{ t('diag.load') }}</td>
                <td class="right num">
                  {{ system.jvm.loadAverage >= 0
                    ? num(system.jvm.loadAverage, 2) : t('diag.unavailable') }}
                </td>
              </tr>
              <tr>
                <td>{{ t('diag.heap') }}</td>
                <td class="right num">
                  {{ t('diag.of', {
                    used: bytes(system.jvm.heapUsed),
                    max: bytes(system.jvm.heapMax),
                  }) }}
                </td>
              </tr>
              <tr>
                <td>{{ t('diag.nonHeap') }}</td>
                <td class="right num">{{ bytes(system.jvm.nonHeapUsed) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard :title="t('diag.godview')" :aside="`v${system.godview.version}`">
        <div class="table-wrap">
          <table>
            <tbody>
              <tr>
                <td>{{ t('diag.profile') }}</td>
                <td class="right num">{{ system.godview.profile }}</td>
              </tr>
              <tr>
                <td>{{ t('diag.viewers') }}</td>
                <td class="right num">{{ system.godview.viewers }}</td>
              </tr>
              <tr>
                <td>{{ t('diag.streamFrames') }}</td>
                <td class="right num">
                  {{ t('diag.streamFramesValue', {
                    sent: system.godview.streamFramesPublished,
                    dropped: system.godview.streamFramesDropped,
                  }) }}
                </td>
              </tr>
              <tr>
                <td>{{ t('diag.httpRequests') }}</td>
                <td class="right num">
                  {{ t('diag.httpRequestsValue', {
                    total: system.godview.httpRequests,
                    errors: system.godview.httpErrors,
                  }) }}
                </td>
              </tr>
              <tr>
                <td>{{ t('diag.sessions') }}</td>
                <td class="right num">{{ system.godview.sessions }}</td>
              </tr>
              <tr>
                <td>{{ t('diag.loginFailures') }}</td>
                <td class="right num">
                  {{ t('diag.loginFailuresValue', {
                    failures: system.godview.loginFailures,
                    lockouts: system.godview.loginLockouts,
                  }) }}
                </td>
              </tr>
              <tr>
                <td>{{ t('diag.consoleCapture') }}</td>
                <td class="right num">
                  {{ system.godview.consoleAttached
                    ? t('diag.consoleLines', { count: system.godview.consoleLines })
                    : t('diag.consoleOff') }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard v-if="system.storage" :title="t('diag.storage')" :aside="system.storage.kind">
        <div class="table-wrap">
          <table>
            <tbody>
              <tr><td>{{ t('diag.events') }}</td><td class="right num">{{ system.storage.events }}</td></tr>
              <tr><td>{{ t('diag.metrics') }}</td><td class="right num">{{ system.storage.metrics }}</td></tr>
              <tr><td>{{ t('diag.spikes') }}</td><td class="right num">{{ system.storage.spikes }}</td></tr>
              <tr><td>{{ t('diag.audit') }}</td><td class="right num">{{ system.storage.audit }}</td></tr>
              <tr><td>{{ t('diag.alerts') }}</td><td class="right num">{{ system.storage.alerts }}</td></tr>
              <tr>
                <td>{{ t('diag.onDisk') }}</td>
                <td class="right num">{{ bytes(system.storage.fileSizeBytes) }}</td>
              </tr>
              <tr>
                <td>{{ t('diag.queuedDropped') }}</td>
                <td class="right num">
                  {{ system.storage.queued }} / {{ system.storage.dropped }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard v-if="gc" :title="t('diag.gc')"
                 :aside="gc.usingNotifications ? t('diag.gcNotifications') : t('diag.gcPolled')">
        <div class="table-wrap">
          <table>
            <tbody>
              <tr><td>{{ t('diag.collections') }}</td><td class="right num">{{ gc.totalCount }}</td></tr>
              <tr>
                <td>{{ t('diag.totalPause') }}</td>
                <td class="right num">{{ gc.totalPauseMs }} ms</td>
              </tr>
              <tr>
                <td>{{ t('diag.worstPause') }}</td>
                <td class="right num">{{ Math.round(gc.worstRecentPauseMs) }} ms</td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard :title="t('diag.integrations')">
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{{ t('diag.plugin') }}</th>
                <th>{{ t('alerts.state') }}</th>
                <th>{{ t('diag.note2') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in integrations" :key="entry.id">
                <td>{{ entry.name }}</td>
                <td>
                  <StatusTag :text="entry.available ? t('diag.connected') : t('diag.absent')"
                             :tone="entry.available ? 'good' : ''" />
                </td>
                <td class="wrap">{{ entry.problem || entry.version }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </PanelCard>

      <PanelCard :title="t('diag.plugins')"
                 :aside="t('diag.installed', { count: system.plugins.length })">
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{{ t('diag.plugin') }}</th>
                <th>{{ t('diag.version') }}</th>
                <th>{{ t('alerts.state') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="plugin in system.plugins" :key="plugin.name">
                <td>{{ plugin.name }}</td>
                <td class="num">{{ plugin.version }}</td>
                <td>
                  <StatusTag :text="plugin.enabled ? t('diag.enabled') : t('diag.disabled')"
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
