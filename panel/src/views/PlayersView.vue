<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { api } from '../lib/api.js';
import { can, toast } from '../lib/store.js';
import { t } from '../lib/i18n.js';
import { duration } from '../lib/format.js';
import PanelCard from '../components/PanelCard.vue';
import StatusTag from '../components/StatusTag.vue';
import EmptyState from '../components/EmptyState.vue';

const players = ref([]);
const failure = ref('');
const showAddresses = ref(false);
const pending = ref(null);

async function load() {
  try {
    const data = await api.get('/api/players');
    players.value = data.players ?? [];
    showAddresses.value = players.value.some((p) => p.ip !== undefined);
    failure.value = '';
  } catch (error) {
    failure.value = error.message;
  }
}

function healthTone(player) {
  const pct = player.healthPercent ?? 0;
  if (pct < 30) return 'bad';
  if (pct < 70) return 'warn';
  return 'good';
}

/* The server enforces security.require-confirm regardless, so this dialog is
   about giving the operator the reason field rather than about safety. */
async function act(player, action) {
  const reason = window.prompt(
    action === 'kick'
      ? t('players.kickReason', { name: player.name })
      : t('players.banReason', { name: player.name }),
    action === 'kick' ? t('players.kickDefault') : t('players.banDefault'));
  if (reason === null) return;

  pending.value = `${player.name}:${action}`;
  try {
    const result = await api.post(
      `/api/players/${encodeURIComponent(player.name)}/${action}`,
      { reason, confirm: true });
    toast(result.message ?? t('toast.done'), 'good');
    await load();
  } catch (error) {
    toast(error.message, 'bad');
  } finally {
    pending.value = null;
  }
}

let timer = null;
onMounted(() => {
  load();
  timer = setInterval(load, 5000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>

<template>
  <div class="view">
    <div class="view__head">
      <h1 class="view__title">{{ t('players.title') }}</h1>
      <p class="view__note">{{ t('players.online', { count: players.length }) }}</p>
    </div>

    <EmptyState v-if="failure" :title="t('players.failure')" :detail="failure" />

    <PanelCard v-else-if="players.length" :title="t('players.onlineNow')">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{{ t('players.player') }}</th>
              <th>{{ t('players.ping') }}</th>
              <th>{{ t('players.health') }}</th>
              <th>{{ t('players.mode') }}</th>
              <th>{{ t('players.position') }}</th>
              <th>{{ t('players.played') }}</th>
              <th v-if="showAddresses">{{ t('players.address') }}</th>
              <th v-if="can('player_action')" class="right">{{ t('players.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="player in players" :key="player.uuid">
              <td>
                <b>{{ player.name }}</b>
                <div v-if="player.displayName && player.displayName !== player.name"
                     class="panel__aside">{{ player.displayName }}</div>
              </td>
              <td class="num">{{ player.ping }} ms</td>
              <td>
                <StatusTag :tone="healthTone(player)"
                           :text="`${Math.round(player.health)} / ${Math.round(player.maxHealth)}`" />
              </td>
              <td class="num">{{ player.gamemode.toLowerCase() }}</td>
              <td class="num wrap">
                {{ player.world }}
                {{ Math.round(player.x) }}, {{ Math.round(player.y) }}, {{ Math.round(player.z) }}
              </td>
              <td class="num">{{ duration(player.playTimeMs) }}</td>
              <td v-if="showAddresses" class="num">{{ player.ip || '--' }}</td>
              <td v-if="can('player_action')" class="right">
                <button class="btn btn--sm" type="button"
                        :disabled="pending === `${player.name}:kick`"
                        @click="act(player, 'kick')">{{ t('players.kick') }}</button>
                <button class="btn btn--sm btn--danger" type="button"
                        :disabled="pending === `${player.name}:ban`"
                        @click="act(player, 'ban')">{{ t('players.ban') }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>

    <EmptyState v-else :title="t('players.nobody')" :detail="t('players.nobodyDetail')" />
  </div>
</template>
