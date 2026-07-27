<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { api } from '../lib/api.js';
import { can, on, toast } from '../lib/store.js';
import { t } from '../lib/i18n.js';
import { stamp, since, severityTone, severityLabel } from '../lib/format.js';
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
      <h1 class="view__title">{{ t('alerts.title') }}</h1>
      <p v-if="status" class="view__note">
        {{ status.enabled ? t('alerts.fired', { count: status.fired }) : t('alerts.off') }}
        <template v-if="status.quietNow"> {{ t('alerts.quiet') }}</template>
      </p>
    </div>

    <EmptyState v-if="failure" :title="t('alerts.failure')" :detail="failure" />

    <template v-else-if="status">
      <div class="grid grid--wide">
        <PanelCard :title="t('alerts.rules')">
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{{ t('alerts.rule') }}</th>
                  <th>{{ t('alerts.type') }}</th>
                  <th>{{ t('alerts.severity') }}</th>
                  <th class="right">{{ t('alerts.firedCount') }}</th>
                  <th>{{ t('alerts.last') }}</th>
                  <th v-if="can('manage_config')" class="right">{{ t('alerts.test') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="rule in status.rules" :key="rule.id">
                  <td>
                    <b>{{ rule.id }}</b>
                    <div v-if="!rule.recognised" class="panel__aside">
                      {{ t('alerts.unknownType') }}
                    </div>
                  </td>
                  <td class="num">{{ rule.type }}</td>
                  <td>
                    <StatusTag :text="severityLabel(rule.severity)"
                               :tone="severityTone(rule.severity)" />
                  </td>
                  <td class="right num">{{ rule.timesFired }}</td>
                  <td class="num">
                    {{ rule.lastFiredAt ? since(rule.lastFiredAt) : t('alerts.never') }}
                  </td>
                  <td v-if="can('manage_config')" class="right">
                    <button class="btn btn--sm" type="button" @click="test(rule.id)">
                      {{ t('alerts.sendTest') }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </PanelCard>

        <PanelCard :title="t('alerts.channels')">
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{{ t('alerts.channel') }}</th>
                  <th>{{ t('alerts.state') }}</th>
                  <th>{{ t('alerts.problem') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="channel in status.channels" :key="channel.id">
                  <td>{{ channel.id }}</td>
                  <td>
                    <StatusTag :text="channel.enabled ? t('alerts.on') : t('alerts.off2')"
                               :tone="channel.enabled ? 'good' : ''" />
                  </td>
                  <td class="wrap">{{ channel.problem }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </PanelCard>
      </div>

      <PanelCard :title="t('alerts.history')">
        <div v-if="history.length" class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{{ t('events.when') }}</th>
                <th>{{ t('alerts.rule') }}</th>
                <th>{{ t('alerts.severity') }}</th>
                <th>{{ t('alerts.what') }}</th>
                <th>{{ t('alerts.sentTo') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(alert, index) in history" :key="`${alert.at}-${index}`">
                <td class="num">{{ stamp(alert.at) }}</td>
                <td>{{ alert.rule ?? alert.ruleId }}</td>
                <td>
                  <StatusTag :text="severityLabel(alert.severity)"
                             :tone="severityTone(alert.severity)" />
                </td>
                <td class="wrap">
                  <b>{{ alert.title }}</b>
                  <div class="panel__aside">{{ alert.message }}</div>
                </td>
                <td class="num">
                  {{ alert.channels || (alert.delivered ? '' : t('alerts.notSent')) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <EmptyState v-else :title="t('alerts.nothing')" :detail="t('alerts.nothingDetail')" />
      </PanelCard>
    </template>
  </div>
</template>
