<script setup>
/* Hand-drawn 16px paths rather than an icon package.
 *
 * The panel loads no external assets and the whole set is seven glyphs, so a
 * dependency would cost more than it saves -- and these are drawn on the same
 * 16-unit grid as each other, which an off-the-shelf set rarely is. */
defineProps({ name: { type: String, required: true } });

const paths = {
  live: [['path', 'M1 8h3l2-5 3 10 2-5h4']],
  players: [
    ['circle', null, { cx: 6, cy: 5, r: 2.5 }],
    ['path', 'M1.5 14c0-2.8 2-4.5 4.5-4.5s4.5 1.7 4.5 4.5'],
    ['path', 'M11 4.2a2.4 2.4 0 0 1 0 4.6M12.5 13.8c0-2 .6-3.3 2-4'],
  ],
  console: [
    ['rect', null, { x: 1.5, y: 2.5, width: 13, height: 11, rx: 1.5 }],
    ['path', 'M4.5 6.5 6.8 8.5 4.5 10.5M8.5 11h3'],
  ],
  events: [['path', 'M2 4h12M2 8h12M2 12h8']],
  map: [
    ['path', 'M1.5 3.5 6 2l4 1.5L14.5 2v10.5L10 14l-4-1.5L1.5 14z'],
    ['path', 'M6 2v10.5M10 3.5V14'],
  ],
  alerts: [
    ['path', 'M8 2.5a4 4 0 0 0-4 4c0 3-1.5 4.5-1.5 4.5h11S12 9.5 12 6.5a4 4 0 0 0-4-4z'],
    ['path', 'M6.5 13.5a1.8 1.8 0 0 0 3 0'],
  ],
  diagnostics: [
    ['circle', null, { cx: 8, cy: 8, r: 2 }],
    ['path', 'M8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.4 3.4l1.4 1.4M11.2 11.2l1.4 1.4M12.6 3.4l-1.4 1.4M4.8 11.2l-1.4 1.4'],
  ],
  out: [
    ['path', 'M10 4.5V3a1.5 1.5 0 0 0-1.5-1.5h-5A1.5 1.5 0 0 0 2 3v10a1.5 1.5 0 0 0 1.5 1.5h5A1.5 1.5 0 0 0 10 13v-1.5'],
    ['path', 'M6.5 8h8M12 5.5 14.5 8 12 10.5'],
  ],
};
</script>

<template>
  <svg class="icon" viewBox="0 0 16 16" aria-hidden="true">
    <template v-for="(shape, index) in paths[name] || []" :key="index">
      <path v-if="shape[0] === 'path'" :d="shape[1]" />
      <circle v-else-if="shape[0] === 'circle'" v-bind="shape[2]" />
      <rect v-else-if="shape[0] === 'rect'" v-bind="shape[2]" />
    </template>
  </svg>
</template>
