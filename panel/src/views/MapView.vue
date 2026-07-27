<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue';
import { api } from '../lib/api.js';
import { state, on, can, toast } from '../lib/store.js';
import { t } from '../lib/i18n.js';
import EmptyState from '../components/EmptyState.vue';

/* Fetching tiles.
 *
 * The old version asked for every visible tile at once and then remembered the
 * failures forever: a tile that answered 404 or 503 left a permanent null in
 * the cache and was never requested again, so the map filled in a patch and
 * then stopped. Everything here exists to avoid that.
 *
 * Tiles are requested nearest-the-middle-first, a few at a time, and every
 * outcome is retryable: a 202 means "still rendering", an error means "ask
 * again with a longer wait", and a tile the server marks incomplete -- some of
 * its chunks were not loaded -- is redrawn a little later on its own. */

/** In-flight tile requests. Enough to keep the server busy, not enough to bury it. */
const MAX_INFLIGHT = 6;
/** Tiles held in memory. A full screen is ~100; this is several screens of panning. */
const MAX_TILES = 700;
/** Wait before asking for a tile that is still rendering. */
const PENDING_DELAY_MS = 1200;
/** First retry after an error, doubling to the ceiling below. */
const RETRY_BASE_MS = 2000;
const RETRY_CEILING_MS = 30000;
/** How long a tile with holes in it is left alone before being asked for again. */
const INCOMPLETE_REFRESH_MS = 20000;

const canvas = ref(null);
const frame = ref(null);
const worlds = ref([]);
const world = ref('');
const failure = ref('');
const disabled = ref(false);
const follow = ref('');
const status = ref(null);

const view = reactive({ x: 0, z: 0, scale: 1 });
const coverage = reactive({ ready: 0, total: 0, rendering: 0, incomplete: 0 });
const geometry = reactive({ tilePixels: 128, chunksPerTile: 8, scales: [1, 2, 4] });

/** key -> { image, url, state, attempts, nextAt, refreshAt, wantedAt } */
const tiles = new Map();
let inflight = 0;
let alive = true;
let dirty = true;

const followable = computed(() =>
  (state.snapshot?.players ?? []).filter((p) => p.world === world.value));

async function init() {
  try {
    const data = await api.get('/api/map/status');
    status.value = data;
    if (!data.enabled) {
      disabled.value = true;
      return;
    }
    geometry.tilePixels = data.tilePixels ?? 128;
    geometry.chunksPerTile = data.chunksPerTile ?? 8;
    geometry.scales = data.scales?.length ? data.scales : [1, 2, 4];
    worlds.value = Object.entries(data.worlds ?? {})
      .filter(([, on_]) => on_)
      .map(([name]) => name);
    world.value = worlds.value[0] ?? '';
    // Start where the operators are rather than at 0,0, which on most servers is
    // a corner of the spawn chunks and tells you nothing.
    const anyone = (state.snapshot?.players ?? []).find((p) => p.world === world.value);
    if (anyone) {
      view.x = anyone.x;
      view.z = anyone.z;
    }
    dirty = true;
  } catch (error) {
    failure.value = error.message;
  }
}

/* ------------------------------------------------------------- geometry --- */

function layout() {
  const width = frame.value?.clientWidth ?? 0;
  const height = frame.value?.clientHeight ?? 0;
  const drawn = geometry.tilePixels / view.scale;
  const blocksPerTile = geometry.chunksPerTile * 16;
  return {
    width,
    height,
    drawn,
    blocksPerTile,
    originX: width / 2 - (view.x / blocksPerTile) * drawn,
    originZ: height / 2 - (view.z / blocksPerTile) * drawn,
  };
}

/** Every tile the viewport touches, nearest the middle first. */
function visibleTiles(box) {
  const firstX = Math.floor(-box.originX / box.drawn);
  const firstZ = Math.floor(-box.originZ / box.drawn);
  const countX = Math.ceil(box.width / box.drawn) + 1;
  const countZ = Math.ceil(box.height / box.drawn) + 1;
  const midX = firstX + countX / 2;
  const midZ = firstZ + countZ / 2;

  const list = [];
  for (let ix = 0; ix <= countX; ix += 1) {
    for (let iz = 0; iz <= countZ; iz += 1) {
      const tx = firstX + ix;
      const tz = firstZ + iz;
      list.push({ tx, tz, weight: (tx - midX) ** 2 + (tz - midZ) ** 2 });
    }
  }
  // Nearest the middle first: on a slow capture budget the operator watches the
  // centre fill outwards, which reads as progress rather than as a stalled grid.
  list.sort((a, b) => a.weight - b.weight);
  return list;
}

const keyOf = (tx, tz) => `${world.value}/${tx}/${tz}/${view.scale}`;

/* -------------------------------------------------------------- fetching --- */

function fetchTile(key, tx, tz) {
  const entry = tiles.get(key);
  entry.state = 'loading';
  inflight += 1;

  const url = `/api/map/tile/${encodeURIComponent(world.value)}/${tx}/${tz}`
    + `?scale=${view.scale}`;

  api.raw(url).then(async (response) => {
    if (!alive) return;
    if (response.status === 202) {
      // Still being captured. Not an error, and not a reason to stop asking.
      entry.state = 'retry';
      entry.nextAt = Date.now() + PENDING_DELAY_MS;
      return;
    }
    if (!response.ok) {
      backOff(entry);
      return;
    }

    const complete = response.headers.get('X-GodView-Complete') !== 'false';
    const blob = await response.blob();
    const image = new Image();
    const objectUrl = URL.createObjectURL(blob);
    image.onload = () => {
      if (!alive) {
        URL.revokeObjectURL(objectUrl);
        return;
      }
      if (entry.url) URL.revokeObjectURL(entry.url);
      entry.image = image;
      entry.url = objectUrl;
      entry.state = 'ready';
      entry.attempts = 0;
      entry.complete = complete;
      // A tile with holes is worth having now and worth replacing later.
      entry.refreshAt = complete ? Infinity : Date.now() + INCOMPLETE_REFRESH_MS;
      dirty = true;
    };
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      backOff(entry);
    };
    image.src = objectUrl;
  }).catch(() => {
    if (alive) backOff(entry);
  }).finally(() => {
    inflight -= 1;
  });
}

function backOff(entry) {
  entry.attempts = (entry.attempts ?? 0) + 1;
  entry.state = entry.image ? 'ready' : 'retry';
  const delay = Math.min(RETRY_CEILING_MS, RETRY_BASE_MS * 2 ** Math.min(entry.attempts, 4));
  entry.nextAt = Date.now() + delay;
  if (entry.image) entry.refreshAt = entry.nextAt;
}

/**
 * One pass: decide what the viewport needs, start a few requests, redraw.
 *
 * Runs on a timer rather than only on interaction, because most of what changes
 * here is the server finishing work nobody asked it about again.
 */
function pump() {
  if (!alive || !frame.value || !world.value) return;

  const box = layout();
  if (box.width === 0 || box.height === 0) return;

  const now = Date.now();
  const wanted = visibleTiles(box);
  let ready = 0;
  let rendering = 0;
  let incomplete = 0;
  const queue = [];

  for (const tile of wanted) {
    const key = keyOf(tile.tx, tile.tz);
    let entry = tiles.get(key);
    if (!entry) {
      entry = { image: null, url: null, state: 'idle', attempts: 0, nextAt: 0, complete: true };
      tiles.set(key, entry);
    }
    entry.wantedAt = now;

    if (entry.state === 'ready') {
      ready += 1;
      if (!entry.complete) incomplete += 1;
    } else if (entry.state === 'loading') {
      rendering += 1;
    }

    const due = entry.state === 'idle'
      || (entry.state === 'retry' && now >= entry.nextAt)
      || (entry.state === 'ready' && now >= (entry.refreshAt ?? Infinity));
    if (due) queue.push({ key, tile });
  }

  for (const item of queue) {
    if (inflight >= MAX_INFLIGHT) break;
    fetchTile(item.key, item.tile.tx, item.tile.tz);
  }

  coverage.ready = ready;
  coverage.total = wanted.length;
  coverage.rendering = rendering + Math.min(queue.length, MAX_INFLIGHT);
  coverage.incomplete = incomplete;

  evict(now);
  if (dirty) {
    draw(box);
    dirty = false;
  }
}

/** Keeps the tile map bounded by dropping whatever the viewport left behind. */
function evict(now) {
  if (tiles.size <= MAX_TILES) return;
  const cold = [...tiles.entries()]
    .filter(([, entry]) => entry.wantedAt !== now)
    .sort((a, b) => (a[1].wantedAt ?? 0) - (b[1].wantedAt ?? 0));
  for (const [key, entry] of cold) {
    if (tiles.size <= MAX_TILES) break;
    if (entry.url) URL.revokeObjectURL(entry.url);
    tiles.delete(key);
  }
}

function forget() {
  for (const entry of tiles.values()) {
    if (entry.url) URL.revokeObjectURL(entry.url);
  }
  tiles.clear();
  dirty = true;
}

/* --------------------------------------------------------------- drawing --- */

function draw(box = layout()) {
  if (!canvas.value || !frame.value || !world.value) return;

  const ratio = window.devicePixelRatio || 1;
  canvas.value.width = Math.round(box.width * ratio);
  canvas.value.height = Math.round(box.height * ratio);

  const ctx = canvas.value.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.imageSmoothingEnabled = false;
  ctx.fillStyle = '#0E1116';
  ctx.fillRect(0, 0, box.width, box.height);

  for (const tile of visibleTiles(box)) {
    const entry = tiles.get(keyOf(tile.tx, tile.tz));
    const px = box.originX + tile.tx * box.drawn;
    const pz = box.originZ + tile.tz * box.drawn;
    if (entry?.image) {
      ctx.drawImage(entry.image, px, pz, box.drawn, box.drawn);
    } else {
      ctx.fillStyle = '#12161C';
      ctx.fillRect(px, pz, box.drawn - 1, box.drawn - 1);
    }
  }

  for (const player of state.snapshot?.players ?? []) {
    if (player.world !== world.value) continue;
    const px = box.originX + (player.x / box.blocksPerTile) * box.drawn;
    const pz = box.originZ + (player.z / box.blocksPerTile) * box.drawn;
    ctx.fillStyle = '#7FB069';
    ctx.beginPath();
    ctx.arc(px, pz, 4, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#E6EBF2';
    ctx.font = '11px ui-monospace, monospace';
    ctx.textAlign = 'center';
    ctx.fillText(player.name, px, pz - 8);
  }
}

/* ---------------------------------------------------------- interaction --- */

let dragging = null;

function onDown(event) {
  dragging = { x: event.clientX, z: event.clientY };
  frame.value.setPointerCapture(event.pointerId);
}

function onMove(event) {
  if (!dragging) return;
  const blocksPerPixel = (geometry.chunksPerTile * 16) / (geometry.tilePixels / view.scale);
  view.x -= (event.clientX - dragging.x) * blocksPerPixel;
  view.z -= (event.clientY - dragging.z) * blocksPerPixel;
  dragging = { x: event.clientX, z: event.clientY };
  follow.value = '';
  dirty = true;
  pump();
}

function onUp() {
  dragging = null;
}

function zoom(direction) {
  const scales = geometry.scales;
  const index = scales.indexOf(view.scale);
  const next = scales[Math.min(scales.length - 1, Math.max(0, index + direction))];
  if (next === view.scale) return;
  view.scale = next;
  // Tiles are rendered per scale, so the cached images are simply the wrong
  // pictures now. The server keeps every scale from one capture, so refetching
  // them costs a cache read rather than another pass over the chunks.
  forget();
  pump();
}

function onWheel(event) {
  event.preventDefault();
  zoom(event.deltaY > 0 ? 1 : -1);
}

function changeWorld() {
  follow.value = '';
  forget();
  pump();
}

function recenter() {
  view.x = 0;
  view.z = 0;
  follow.value = '';
  dirty = true;
  pump();
}

function jump() {
  const answer = window.prompt(t('map.jumpPrompt'), `${Math.round(view.x)} ${Math.round(view.z)}`);
  if (answer === null) return;
  const parts = answer.trim().split(/[\s,]+/).map(Number);
  if (parts.length < 2 || parts.some(Number.isNaN)) {
    toast(t('map.jumpBad'), 'bad');
    return;
  }
  view.x = parts[0];
  view.z = parts[1];
  follow.value = '';
  dirty = true;
  pump();
}

async function redraw() {
  try {
    await api.post(`/api/map/invalidate/${encodeURIComponent(world.value)}`);
    forget();
    pump();
    toast(t('map.refreshed'), 'good');
  } catch (error) {
    toast(error.message, 'bad');
  }
}

function onSnapshot() {
  if (follow.value) {
    const target = (state.snapshot?.players ?? []).find((p) => p.name === follow.value);
    if (target && target.world === world.value) {
      view.x = target.x;
      view.z = target.z;
    }
  }
  // Player markers move every snapshot even when no tile changed.
  dirty = true;
  pump();
}

/* ------------------------------------------------------------ lifecycle --- */

let off = null;
let timer = null;

function onResize() {
  dirty = true;
  pump();
}

onMounted(async () => {
  await init();
  window.addEventListener('resize', onResize);
  off = on('snapshot', onSnapshot);
  // The pump is the only thing that starts requests, so it has to keep running
  // while tiles the server is still capturing come back 202.
  timer = setInterval(pump, 400);
  pump();
});

onBeforeUnmount(() => {
  alive = false;
  clearInterval(timer);
  window.removeEventListener('resize', onResize);
  off?.();
  forget();
});
</script>

<template>
  <div class="view">
    <div class="view__head">
      <h1 class="view__title">{{ t('map.title') }}</h1>
      <p class="view__note">
        {{ t('map.position', {
          x: Math.round(view.x), z: Math.round(view.z), scale: view.scale,
        }) }}
        <template v-if="coverage.total">
          · {{ t('map.coverage', { ready: coverage.ready, total: coverage.total }) }}
        </template>
      </p>
    </div>

    <EmptyState v-if="disabled" :title="t('map.off')"
                :detail="t('map.offDetail', {
                  key: 'map.enabled', command: '/godview reload',
                })" />

    <EmptyState v-else-if="failure" :title="t('map.failure')" :detail="failure" />

    <template v-else>
      <div class="controls">
        <select v-model="world" class="input" :aria-label="t('map.world')" @change="changeWorld">
          <option v-for="name in worlds" :key="name" :value="name">{{ name }}</option>
        </select>

        <select v-if="followable.length" v-model="follow" class="input"
                :aria-label="t('map.follow')" @change="onSnapshot">
          <option value="">{{ t('map.follow') }}</option>
          <option v-for="player in followable" :key="player.uuid" :value="player.name">
            {{ player.name }}
          </option>
        </select>

        <button class="btn btn--sm" type="button" :title="t('map.zoomIn')"
                :disabled="view.scale === geometry.scales[0]" @click="zoom(-1)">+</button>
        <button class="btn btn--sm" type="button" :title="t('map.zoomOut')"
                :disabled="view.scale === geometry.scales[geometry.scales.length - 1]"
                @click="zoom(1)">&minus;</button>
        <button class="btn btn--sm" type="button" @click="jump">{{ t('map.jump') }}</button>
        <button class="btn btn--sm" type="button" @click="recenter">{{ t('map.recenter') }}</button>
        <button v-if="can('world_action')" class="btn btn--sm" type="button" @click="redraw">
          {{ t('map.refresh') }}
        </button>

        <span class="panel__aside">{{ t('map.hint') }}</span>
      </div>

      <div ref="frame" class="mapframe"
           @pointerdown="onDown" @pointermove="onMove" @pointerup="onUp"
           @pointercancel="onUp" @wheel="onWheel">
        <canvas ref="canvas" />
      </div>

      <p class="panel__aside" style="margin-top:.6rem">
        <template v-if="coverage.incomplete">{{ t('map.incomplete') }}</template>
        <template v-else-if="status && !status.loadUnloaded">
          {{ t('map.unloadedHint', { key: 'map.load-unloaded' }) }}
        </template>
        <template v-else-if="coverage.rendering">
          {{ t('map.rendering', { count: coverage.rendering }) }}
        </template>
      </p>
    </template>
  </div>
</template>
