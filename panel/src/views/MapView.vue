<script setup>
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue';
import { api } from '../lib/api.js';
import { state, on } from '../lib/store.js';
import EmptyState from '../components/EmptyState.vue';

const canvas = ref(null);
const frame = ref(null);
const worlds = ref([]);
const world = ref('');
const failure = ref('');
const disabled = ref(false);

const view = reactive({ x: 0, z: 0, scale: 1 });
const geometry = { tilePixels: 128, chunksPerTile: 8 };

/* Tiles are fetched once and kept. A tile that is still rendering answers 202,
   so it is forgotten after a moment and asked for again on the next frame --
   which is what lets the map fill in progressively instead of blocking. */
const tiles = new Map();
let alive = true;

async function init() {
  try {
    const status = await api.get('/api/map/status');
    if (!status.enabled) {
      disabled.value = true;
      return;
    }
    geometry.tilePixels = status.tilePixels ?? 128;
    geometry.chunksPerTile = status.chunksPerTile ?? 8;
    worlds.value = Object.entries(status.worlds ?? {})
      .filter(([, on_]) => on_)
      .map(([name]) => name);
    world.value = worlds.value[0] ?? '';
    draw();
  } catch (error) {
    failure.value = error.message;
  }
}

function tileImage(key, url) {
  const existing = tiles.get(key);
  if (existing) return existing.image;

  const entry = { image: null };
  tiles.set(key, entry);

  api.raw(url).then(async (response) => {
    if (response.status === 202) {
      setTimeout(() => tiles.delete(key), 900);
      return;
    }
    if (!response.ok) return;
    const image = new Image();
    image.onload = () => {
      entry.image = image;
      draw();
    };
    image.src = URL.createObjectURL(await response.blob());
  }).catch(() => tiles.delete(key));

  return null;
}

function draw() {
  if (!alive || !canvas.value || !frame.value || !world.value) return;

  const ratio = window.devicePixelRatio || 1;
  const width = frame.value.clientWidth;
  const height = frame.value.clientHeight;
  canvas.value.width = Math.round(width * ratio);
  canvas.value.height = Math.round(height * ratio);

  const ctx = canvas.value.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.imageSmoothingEnabled = false;
  ctx.fillStyle = '#0E1116';
  ctx.fillRect(0, 0, width, height);

  const drawn = geometry.tilePixels / view.scale;
  const blocksPerTile = geometry.chunksPerTile * 16;
  const originX = width / 2 - (view.x / blocksPerTile) * drawn;
  const originZ = height / 2 - (view.z / blocksPerTile) * drawn;

  const firstX = Math.floor(-originX / drawn);
  const firstZ = Math.floor(-originZ / drawn);
  const countX = Math.ceil(width / drawn) + 1;
  const countZ = Math.ceil(height / drawn) + 1;

  for (let ix = 0; ix <= countX; ix += 1) {
    for (let iz = 0; iz <= countZ; iz += 1) {
      const tx = firstX + ix;
      const tz = firstZ + iz;
      const key = `${world.value}/${tx}/${tz}/${view.scale}`;
      const image = tileImage(key,
        `/api/map/tile/${encodeURIComponent(world.value)}/${tx}/${tz}?scale=${view.scale}`);
      const px = originX + tx * drawn;
      const pz = originZ + tz * drawn;
      if (image) {
        ctx.drawImage(image, px, pz, drawn, drawn);
      } else {
        ctx.fillStyle = '#12161C';
        ctx.fillRect(px, pz, drawn - 1, drawn - 1);
      }
    }
  }

  for (const player of state.snapshot?.players ?? []) {
    if (player.world !== world.value) continue;
    const px = originX + (player.x / blocksPerTile) * drawn;
    const pz = originZ + (player.z / blocksPerTile) * drawn;
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
  draw();
}

function onUp() {
  dragging = null;
}

function onWheel(event) {
  event.preventDefault();
  const next = event.deltaY > 0 ? view.scale * 2 : view.scale / 2;
  const clamped = Math.min(4, Math.max(1, next));
  if (clamped === view.scale) return;
  view.scale = clamped;
  // Tiles are rendered per scale, so the cache is keyed by it and the old
  // entries are simply the wrong pictures now.
  tiles.clear();
  draw();
}

function changeWorld() {
  tiles.clear();
  draw();
}

let off = null;
onMounted(() => {
  init();
  window.addEventListener('resize', draw);
  off = on('snapshot', draw);
});

onBeforeUnmount(() => {
  alive = false;
  window.removeEventListener('resize', draw);
  off?.();
});
</script>

<template>
  <div class="view">
    <div class="view__head">
      <h1 class="view__title">Map</h1>
      <p class="view__note">
        {{ Math.round(view.x) }}, {{ Math.round(view.z) }} · 1:{{ view.scale }}
      </p>
    </div>

    <EmptyState v-if="disabled" title="The map is off">
      Set <code>map.enabled</code> to true in config.yml and run <code>/godview reload</code>.
    </EmptyState>

    <EmptyState v-else-if="failure" title="Could not reach the map" :detail="failure" />

    <template v-else>
      <div class="controls">
        <select v-model="world" class="input" aria-label="World" @change="changeWorld">
          <option v-for="name in worlds" :key="name" :value="name">{{ name }}</option>
        </select>
        <span class="panel__aside">Drag to pan, scroll to zoom</span>
      </div>

      <div ref="frame" class="mapframe"
           @pointerdown="onDown" @pointermove="onMove" @pointerup="onUp"
           @pointercancel="onUp" @wheel="onWheel">
        <canvas ref="canvas" />
      </div>
    </template>
  </div>
</template>
