<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { api } from '../lib/api.js';
import { can, on, toast } from '../lib/store.js';
import { stamp, since, severityTone } from '../lib/format.js';
import PanelCard from '../components/PanelCard.vue';
import StatusTag from '../components/StatusTag.vue';
import EmptyState from '../components/EmptyState.vue';

const status = ref(null);
const history = ref([]);
const failure = ref('');

async function load() {
  try {
    const [state, recent] = await Promise.all([
      api.get('/api/alerts/status'),
      api.get('/api/alerts?limit=100'),
    ]);
    status.value = state;
    history.value = recent.alerts ?? [];
    failure.value = '';
  } catch (error) {
    failure.value = error.message;
  }
}

async function test(rule) {
  try {
    const result = await api.post(`/api/alerts/test/${encodeURIComponent(rule)}`);
    toast(result.message, 'good');
  } catch (error) {
    toast(error.message, 'bad');
  }
}

let timer = null;
let off = null;
onMounted(() => {
  load();
  timer = setInterval(load, 30000);
  off = on('alert', load);
});
onBeforeUnmount(() => {
  clearInterval(timer);
  off?.();
});
</script>

<template>
  <div class="view">
    <div class="view__head">
      <h1 class="view__title">Alerts</h1>
      <p v-if="status" class="view__note">
        {{ status.enabled ? `${status.fired} fired` : 'Alerts are off in config.yml.' }}
        <template v-if="status.quietNow"> · quiet hours are active</template>
      </p>
    </div>

    <EmptyState v-if="failure" title="Could not load alerts" :detail="failure" />

    <template v-else-if="status">
      <div class="grid grid--wide">
        <PanelCard title="Rules">
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Rule</th><th>Type</th><th>Severity</th>
                  <th class="right">Fired</th><th>Last</th>
                  <th v-if="can('manage_config')" class="right">Test</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="rule in status.rules" :key="rule.id">
                  <td>
                    <b>{{ rule.id }}</b>
                    <div v-if="!rule.recognised" class="panel__aside">unknown type</div>
                  </td>
                  <td class="num">{{ rule.type }}</td>
                  <td><StatusTag :text="rule.severity" :tone="severityTone(rule.severity)" /></td>
                  <td class="right num">{{ rule.timesFired }}</td>
                  <td class="num">{{ rule.lastFiredAt ? since(rule.lastFiredAt) : 'never' }}</td>
                  <td v-if="can('manage_config')" class="right">
                    <button class="btn btn--sm" type="button" @click="test(rule.id)">
                      Send test
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </PanelCard>

        <PanelCard title="Channels">
          <div class="table-wrap">
            <table>
              <thead><tr><th>Channel</th><th>State</th><th>Problem</th></tr></thead>
              <tbody>
                <tr v-for="channel in status.channels" :key="channel.id">
                  <td>{{ channel.id }}</td>
                  <td>
                    <StatusTag :text="channel.enabled ? 'on' : 'off'"
                               :tone="channel.enabled ? 'good' : ''" />
                  </td>
                  <td class="wrap">{{ channel.problem }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </PanelCard>
      </div>

      <PanelCard title="History">
        <div v-if="history.length" class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>When</th><th>Rule</th><th>Severity</th>
                <th>What happened</th><th>Sent to</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(alert, index) in history" :key="`${alert.at}-${index}`">
                <td class="num">{{ stamp(alert.at) }}</td>
                <td>{{ alert.rule ?? alert.ruleId }}</td>
                <td><StatusTag :text="alert.severity" :tone="severityTone(alert.severity)" /></td>
                <td class="wrap">
                  <b>{{ alert.title }}</b>
                  <div class="panel__aside">{{ alert.message }}</div>
                </td>
                <td class="num">{{ alert.channels || (alert.delivered ? '' : 'not sent') }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <EmptyState v-else title="Nothing has fired"
                    detail="Alerts appear here as soon as a rule triggers." />
      </PanelCard>
    </template>
  </div>
</template>
