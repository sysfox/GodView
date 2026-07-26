<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { api } from '../lib/api.js';
import { can, toast } from '../lib/store.js';
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
    action === 'kick' ? `Reason for kicking ${player.name}` : `Reason for banning ${player.name}`,
    action === 'kick' ? 'Kicked by an operator' : 'Banned by an operator');
  if (reason === null) return;

  pending.value = `${player.name}:${action}`;
  try {
    const result = await api.post(
      `/api/players/${encodeURIComponent(player.name)}/${action}`,
      { reason, confirm: true });
    toast(result.message ?? 'Done', 'good');
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
      <h1 class="view__title">Players</h1>
      <p class="view__note">{{ players.length }} online</p>
    </div>

    <EmptyState v-if="failure" title="Could not load players" :detail="failure" />

    <PanelCard v-else-if="players.length" title="Online now">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Player</th>
              <th>Ping</th>
              <th>Health</th>
              <th>Mode</th>
              <th>Position</th>
              <th>Played</th>
              <th v-if="showAddresses">Address</th>
              <th v-if="can('player_action')" class="right">Actions</th>
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
                        @click="act(player, 'kick')">Kick</button>
                <button class="btn btn--sm btn--danger" type="button"
                        :disabled="pending === `${player.name}:ban`"
                        @click="act(player, 'ban')">Ban</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>

    <EmptyState v-else title="Nobody is online"
                detail="Players appear here as soon as they join." />
  </div>
</template>
