<script setup>
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import { api } from '../lib/api.js';
import { can, on, toast } from '../lib/store.js';
import { clock } from '../lib/format.js';
import EmptyState from '../components/EmptyState.vue';

/** Lines held in the DOM. Beyond this the browser, not the server, is the limit. */
const MAX_LINES = 800;
const RANK = { DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4, FATAL: 5 };

const lines = ref([]);
const level = ref('');
const follow = ref(true);
const enabled = ref(true);
const restricted = ref(false);
const note = ref('');
const command = ref('');
const sending = ref(false);
const box = ref(null);

let sequence = 0;

function keep(line) {
  if (level.value && (RANK[line.level] ?? 0) < RANK[level.value]) return;
  lines.value.push({ ...line, key: ++sequence });
  if (lines.value.length > MAX_LINES) lines.value.splice(0, lines.value.length - MAX_LINES);
  scroll();
}

/* Only follows when the operator is already at the bottom. Someone scrolled up
   reading a stack trace is reading it on purpose. */
function scroll() {
  if (!follow.value) return;
  const node = box.value;
  if (!node) return;
  const atBottom = node.scrollHeight - node.scrollTop - node.clientHeight < 60;
  if (!atBottom && lines.value.length > 1) return;
  nextTick(() => { node.scrollTop = node.scrollHeight; });
}

async function load() {
  lines.value = [];
  try {
    const data = await api.get(
      `/api/console/log?limit=400&level=${encodeURIComponent(level.value)}`);
    enabled.value = data.enabled;
    restricted.value = data.restricted;
    note.value = data.enabled
      ? (data.restricted
          ? 'Your role sees warnings and errors only.'
          : `${data.totalLines} lines since the server started`)
      : '';
    for (const line of data.lines ?? []) lines.value.push({ ...line, key: ++sequence });
    nextTick(() => { if (box.value) box.value.scrollTop = box.value.scrollHeight; });
  } catch (error) {
    note.value = error.message;
  }
}

async function run() {
  const text = command.value.trim();
  if (!text) return;
  sending.value = true;
  try {
    const result = await api.post('/api/console', { command: text, confirm: true });
    toast(result.message ?? 'Command dispatched', result.ok ? 'good' : 'bad');
    command.value = '';
  } catch (error) {
    toast(error.message, 'bad');
  } finally {
    sending.value = false;
  }
}

watch(level, load);

let off = null;
onMounted(() => {
  load();
  off = on('console', keep);
});
onBeforeUnmount(() => off?.());
</script>

<template>
  <div class="view">
    <div class="view__head">
      <h1 class="view__title">Console</h1>
      <p class="view__note">{{ note }}</p>
    </div>

    <div class="controls">
      <select v-model="level" class="input" aria-label="Minimum level">
        <option value="">All levels</option>
        <option value="INFO">Info and above</option>
        <option value="WARN">Warnings and above</option>
        <option value="ERROR">Errors only</option>
      </select>
      <label class="link-state">
        <input v-model="follow" type="checkbox"> Follow output
      </label>
    </div>

    <div v-if="enabled" ref="box" class="logbox">
      <div v-for="line in lines" :key="line.key" class="logline" :class="`logline--${line.level}`">
        <span class="logline__at">{{ clock(line.at) }}</span>
        <span class="logline__lvl">{{ line.level }}</span>
        <span class="logline__msg">{{ line.message }}</span>
      </div>
    </div>

    <EmptyState v-else title="Console capture is off">
      Set <code>capture.console-buffer-lines</code> above zero in config.yml and run
      <code>/godview reload</code>.
    </EmptyState>

    <form v-if="can('command')" class="controls" style="margin-top:1rem" @submit.prevent="run">
      <input v-model="command" class="input" style="flex:1;min-width:14rem"
             placeholder="Run a console command" autocapitalize="none" spellcheck="false">
      <button class="btn btn--primary" type="submit" :disabled="sending">Run</button>
    </form>
  </div>
</template>
