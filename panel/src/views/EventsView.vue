<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { api } from '../lib/api.js';
import { on } from '../lib/store.js';
import { stamp, eventTone } from '../lib/format.js';
import PanelCard from '../components/PanelCard.vue';
import StatusTag from '../components/StatusTag.vue';
import EmptyState from '../components/EmptyState.vue';

const TYPES = ['join', 'quit', 'chat', 'command', 'death', 'kick', 'console_error', 'world_load'];

const events = ref([]);
const failure = ref('');
const type = ref('');
const actor = ref('');
const search = ref('');

async function load() {
  const params = new URLSearchParams({ limit: '200', from: '0' });
  if (type.value) params.set('type', type.value);
  if (actor.value.trim()) params.set('actor', actor.value.trim());
  if (search.value.trim()) params.set('search', search.value.trim());

  try {
    const data = await api.get(`/api/events?${params}`);
    events.value = data.events ?? [];
    failure.value = '';
  } catch (error) {
    failure.value = error.message;
  }
}

function filtered() {
  return Boolean(type.value || actor.value.trim() || search.value.trim());
}

let off = null;
onMounted(() => {
  load();
  // Live-refresh only the unfiltered list. Reissuing a search under somebody
  // while they are reading the results is worse than being a moment stale.
  off = on('event', () => { if (!filtered()) load(); });
});
onBeforeUnmount(() => off?.());
</script>

<template>
  <div class="view">
    <div class="view__head">
      <h1 class="view__title">Events</h1>
      <p class="view__note">Everything the server recorded, newest first.</p>
    </div>

    <form class="controls" @submit.prevent="load">
      <select v-model="type" class="input" aria-label="Event type">
        <option value="">All events</option>
        <option v-for="value in TYPES" :key="value" :value="value">
          {{ value.replace(/_/g, ' ') }}
        </option>
      </select>
      <input v-model="actor" class="input" placeholder="Player" style="min-width:9rem">
      <input v-model="search" class="input" placeholder="Search text" style="min-width:12rem">
      <button class="btn" type="submit">Search</button>
    </form>

    <EmptyState v-if="failure" title="Could not load events" :detail="failure" />

    <PanelCard v-else-if="events.length" :title="`${events.length} events`">
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>When</th><th>Type</th><th>Who</th><th>Detail</th></tr>
          </thead>
          <tbody>
            <tr v-for="(event, index) in events" :key="`${event.at}-${index}`">
              <td class="num">{{ stamp(event.at) }}</td>
              <td><StatusTag :text="event.type" :tone="eventTone(event.type)" /></td>
              <td>{{ event.actor ?? '--' }}</td>
              <td class="wrap">{{ event.detail }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </PanelCard>

    <EmptyState v-else title="Nothing matched"
                detail="Events are kept for as long as storage.retention.events-days allows." />
  </div>
</template>
