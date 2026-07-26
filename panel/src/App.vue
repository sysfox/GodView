<script setup>
import { ref, onMounted } from 'vue';
import { state, bootstrap, signIn, signOut, toast } from './lib/store.js';
import { num } from './lib/format.js';
import TickRibbon from './components/TickRibbon.vue';
import AppIcon from './components/AppIcon.vue';

const views = [
  { path: '/live', name: 'Live', icon: 'live' },
  { path: '/players', name: 'Players', icon: 'players' },
  { path: '/console', name: 'Console', icon: 'console' },
  { path: '/events', name: 'Events', icon: 'events' },
  { path: '/map', name: 'Map', icon: 'map' },
  { path: '/alerts', name: 'Alerts', icon: 'alerts' },
  { path: '/diagnostics', name: 'Diagnostics', icon: 'diagnostics' },
];

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
  toast('Signed out.');
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
          {{ state.configured
            ? 'Sign in to watch the server.'
            : 'No account exists yet. Create one in the server console.' }}
        </p>

        <p v-if="!state.configured" class="gate__error">
          Run <code>/godview user add &lt;name&gt; admin</code> on the server, then sign in
          with the password it prints.
        </p>

        <label class="field">
          <span class="field__label">Account</span>
          <input v-model="user" class="field__input" autocomplete="username"
                 autocapitalize="none" spellcheck="false" required>
        </label>
        <label class="field">
          <span class="field__label">Password</span>
          <input v-model="password" class="field__input" type="password"
                 autocomplete="current-password" required>
        </label>

        <p v-if="error" class="gate__error" role="alert">{{ error }}</p>

        <button class="btn btn--primary btn--wide" type="submit" :disabled="busy">
          {{ busy ? 'Signing in' : 'Sign in' }}
        </button>
      </form>
    </div>

    <div v-else class="shell">
      <header class="topbar">
        <RouterLink to="/live" class="topbar__brand" aria-label="GodView, live view">
          <TickRibbon :ticks="state.ticks" variant="brand" />
          <span class="topbar__word">GodView</span>
        </RouterLink>

        <dl class="vitals">
          <div class="vital">
            <dt>TPS</dt>
            <dd class="num">{{ state.pulse ? num(Math.min(state.pulse.tps1, 20), 2) : '--' }}</dd>
          </div>
          <div class="vital">
            <dt>MSPT</dt>
            <dd class="num">{{ state.pulse ? num(state.pulse.mspt, 1) : '--' }}</dd>
          </div>
          <div class="vital">
            <dt>Heap</dt>
            <dd class="num">{{ state.pulse ? `${num(state.pulse.heapPercent, 0)}%` : '--' }}</dd>
          </div>
          <div class="vital">
            <dt>Players</dt>
            <dd class="num">{{ state.pulse ? state.pulse.players : '--' }}</dd>
          </div>
        </dl>

        <div class="topbar__end">
          <span class="link-state" :data-state="state.link" title="Live connection">
            <span class="link-state__dot" />
            {{ state.link === 'live' ? 'Live' : state.link === 'lost' ? 'Reconnecting' : 'Connecting' }}
          </span>
          <button class="btn btn--ghost" type="button" @click="leave">
            <AppIcon name="out" /> Sign out
          </button>
        </div>
      </header>

      <nav class="rail" aria-label="Views">
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
