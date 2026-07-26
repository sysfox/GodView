<script setup>
defineProps({
  title: { type: String, required: true },
  aside: { type: String, default: '' },
});
</script>

<template>
  <section class="panel">
    <div class="panel__head">
      <h2 class="panel__title">{{ title }}</h2>
      <span v-if="aside" class="panel__aside">{{ aside }}</span>
    </div>
    <slot />
  </section>
</template>
