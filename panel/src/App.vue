<script setup>
import { ref, computed, onMounted } from 'vue';
import { state, bootstrap, signIn, signOut, toast } from './lib/store.js';
import { t, i18n, setLocale, LOCALES } from './lib/i18n.js';
import { num } from './lib/format.js';
import TickRibbon from './components/TickRibbon.vue';
import AppIcon from './components/AppIcon.vue';

const views = computed(() => [
  { path: '/live', name: t('nav.live'), icon: 'live' },
  { path: '/players', name: t('nav.players'), icon: 'players' },
  { path: '/console', name: t('nav.console'), icon: 'console' },
  { path: '/events', name: t('nav.events'), icon: 'events' },
  { path: '/map', name: t('nav.map'), icon: 'map' },
  { path: '/alerts', name: t('nav.alerts'), icon: 'alerts' },
  { path: '/diagnostics', name: t('nav.diagnostics'), icon: 'diagnostics' },
]);

const user = ref('');
const password = ref('');
const error = ref('');
const busy = ref(false);

// A ribbon on the sign-in card too, drawn from a fixed sample. It is the only
// mark this product has, and inventing a second one for the door would be a
// logo nobody sees again.
const gateTicks = [50, 50, 51, 50, 50, 52, 50, 50, 51, 50, 50, 50, 118, 61, 50,
  50, 51, 50, 50, 50, 51, 50, 50, 52, 50, 50, 50, 51, 50, 50];

async function submit() {
  error.value = '';
  busy.value = true;
  try {
    await signIn(user.value.trim(), password.value);
    password.value = '';
  } catch (failure) {
    error.value = failure.message;
  } finally {
    busy.value = false;
  }
}

async function leave() {
  await signOut();
  toast(t('toast.signedOut'));
}

onMounted(bootstrap);
</script>

<template>
  <!-- Nothing renders until the session is known, or the panel would flash the
       sign-in card at somebody who is already signed in. -->
  <template v-if="state.ready">
    <div v-if="!state.signedIn" class="gate">
      <form class="gate__card" @submit.prevent="submit">
        <div class="gate__mark">
          <TickRibbon :ticks="gateTicks" variant="brand" :bar-width="4" :gap="2" />
        </div>
        <h1 class="gate__title">GodView</h1>
        <p class="gate__sub">
          {{ state.configured ? t('gate.subtitle') : t('gate.unconfigured') }}
        </p>

        <p v-if="!state.configured" class="gate__error">
          {{ t('gate.hint', { command: '/godview user add <name> admin' }) }}
        </p>

        <label class="field">
          <span class="field__label">{{ t('gate.account') }}</span>
          <input v-model="user" class="field__input" autocomplete="username"
                 autocapitalize="none" spellcheck="false" required>
        </label>
        <label class="field">
          <span class="field__label">{{ t('gate.password') }}</span>
          <input v-model="password" class="field__input" type="password"
                 autocomplete="current-password" required>
        </label>

        <p v-if="error" class="gate__error" role="alert">{{ error }}</p>

        <button class="btn btn--primary btn--wide" type="submit" :disabled="busy">
          {{ busy ? t('gate.signingIn') : t('gate.signIn') }}
        </button>

        <div class="gate__lang">
          <button v-for="option in LOCALES" :key="option.id" type="button"
                  class="langpick" :class="{ 'is-active': i18n.locale === option.id }"
                  @click="setLocale(option.id)">{{ option.label }}</button>
        </div>
      </form>
    </div>

    <div v-else class="shell">
      <header class="topbar">
        <RouterLink to="/live" class="topbar__brand" :aria-label="t('gate.brandLabel')">
          <TickRibbon :ticks="state.ticks" variant="brand" />
          <span class="topbar__word">GodView</span>
        </RouterLink>

        <dl class="vitals">
          <div class="vital">
            <dt>{{ t('topbar.tps') }}</dt>
            <dd class="num">{{ state.pulse ? num(Math.min(state.pulse.tps1, 20), 2) : '--' }}</dd>
          </div>
          <div class="vital">
            <dt>{{ t('topbar.mspt') }}</dt>
            <dd class="num">{{ state.pulse ? num(state.pulse.mspt, 1) : '--' }}</dd>
          </div>
          <div class="vital">
            <dt>{{ t('topbar.heap') }}</dt>
            <dd class="num">{{ state.pulse ? `${num(state.pulse.heapPercent, 0)}%` : '--' }}</dd>
          </div>
          <div class="vital">
            <dt>{{ t('topbar.players') }}</dt>
            <dd class="num">{{ state.pulse ? state.pulse.players : '--' }}</dd>
          </div>
        </dl>

        <div class="topbar__end">
          <span class="link-state" :data-state="state.link" :title="t('topbar.link')">
            <span class="link-state__dot" />
            {{ state.link === 'live' ? t('topbar.live')
              : state.link === 'lost' ? t('topbar.lost') : t('topbar.connecting') }}
          </span>

          <select class="langswitch" :value="i18n.locale" :aria-label="t('lang.label')"
                  @change="setLocale($event.target.value)">
            <option v-for="option in LOCALES" :key="option.id" :value="option.id">
              {{ option.label }}
            </option>
          </select>

          <button class="btn btn--ghost" type="button" @click="leave">
            <AppIcon name="out" /> {{ t('topbar.signOut') }}
          </button>
        </div>
      </header>

      <nav class="rail" :aria-label="t('nav.label')">
        <RouterLink v-for="view in views" :key="view.path" :to="view.path"
                    class="rail__item" active-class="is-active">
          <AppIcon :name="view.icon" />
          <span>{{ view.name }}</span>
          <span v-if="view.path === '/alerts' && state.alertCount" class="rail__badge">
            {{ state.alertCount }}
          </span>
        </RouterLink>
      </nav>

      <main class="stage">
        <RouterView />
      </main>
    </div>
  </template>

  <div class="toasts" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="item in state.toasts" :key="item.id"
           class="toast" :class="item.tone ? `toast--${item.tone}` : ''">
        {{ item.message }}
      </div>
    </TransitionGroup>
  </div>
</template>
