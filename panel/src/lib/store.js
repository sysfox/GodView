import { reactive, computed } from 'vue';
import { api, setCsrf, onSignedOut, LiveFeed, ApiError } from './api.js';

/**
 * One shared store, plain reactive state rather than Pinia.
 *
 * There is exactly one of everything here -- one session, one live feed, one
 * rolling tick buffer -- so a store library would add a dependency and a layer
 * of ceremony without adding a capability.
 */

/** Ticks kept for the ribbon: ten seconds at twenty per second. */
const RIBBON_CAPACITY = 200;

export const state = reactive({
  ready: false,
  signedIn: false,
  configured: true,
  user: null,
  role: null,
  permissions: [],

  link: 'connecting',
  pulse: null,
  snapshot: null,
  ticks: [],
  lastSpike: null,
  alertCount: 0,

  toasts: [],
});

let feed = null;
let toastId = 0;

/* ----------------------------------------------------------------- events --- */

const listeners = new Map();

/** Subscribes to a live event. Returns its own unsubscribe. */
export function on(name, handler) {
  if (!listeners.has(name)) listeners.set(name, new Set());
  listeners.get(name).add(handler);
  return () => listeners.get(name)?.delete(handler);
}

function emit(name, payload) {
  for (const handler of listeners.get(name) ?? []) {
    try {
      handler(payload);
    } catch {
      // One view failing to handle a frame must not stop the others.
    }
  }
}

/* ------------------------------------------------------------------ toasts --- */

export function toast(message, tone = null) {
  const id = ++toastId;
  state.toasts.push({ id, message, tone });
  setTimeout(() => {
    const index = state.toasts.findIndex((t) => t.id === id);
    if (index >= 0) state.toasts.splice(index, 1);
  }, tone === 'bad' ? 7000 : 4000);
}

export const can = (permission) => state.permissions.includes(permission);

export const isAdmin = computed(() => state.role === 'admin');

/* ------------------------------------------------------------------ session --- */

export async function bootstrap() {
  try {
    const gate = await api.get('/api/auth/state');
    state.configured = gate.configured;
    if (gate.authenticated) {
      await adopt(await api.get('/api/auth/me'));
    }
  } catch {
    // An unreachable server still has to render the sign-in page.
  } finally {
    state.ready = true;
  }
}

export async function signIn(user, password) {
  const session = await api.post('/api/auth/login', { user, password });
  await adopt(session);
}

async function adopt(session) {
  setCsrf(session.csrf);
  state.user = session.user;
  state.role = session.role;
  state.permissions = session.permissions ?? [];
  state.signedIn = true;
  await primeAndConnect();
}

export async function signOut() {
  try {
    await api.post('/api/auth/logout');
  } catch {
    // Already gone as far as the server is concerned; clear up regardless.
  }
  teardown();
}

function teardown() {
  feed?.stop();
  feed = null;
  setCsrf(null);
  state.signedIn = false;
  state.user = null;
  state.role = null;
  state.permissions = [];
  state.pulse = null;
  state.snapshot = null;
  state.ticks = [];
  state.link = 'connecting';
}

onSignedOut(() => {
  if (state.signedIn) {
    teardown();
    toast('Your session ended. Sign in again.', 'bad');
  }
});

/* -------------------------------------------------------------------- live --- */

async function primeAndConnect() {
  // A full snapshot first, so the panel has a complete picture and a filled
  // ribbon before the first pulse rather than assembling itself a second at a
  // time in front of the operator.
  try {
    applySnapshot(await api.get('/api/overview'));
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) {
      toast(error.message, 'bad');
    }
  }

  feed?.stop();
  feed = new LiveFeed({
    pulse: (data) => {
      state.pulse = data;
      appendTicks(data.ticks);
      emit('pulse', data);
    },
    snapshot: (data) => {
      applySnapshot(data);
      emit('snapshot', data);
    },
    event: (data) => emit('event', data),
    console: (data) => emit('console', data),
    alert: (data) => {
      state.alertCount += 1;
      toast(`${data.title}`, data.severity === 'critical' ? 'bad' : null);
      emit('alert', data);
    },
  }, (link) => {
    state.link = link;
  });
  feed.start();
}

function applySnapshot(snapshot) {
  if (!snapshot) return;
  state.snapshot = snapshot;
  if (snapshot.sample) {
    state.pulse = {
      ...snapshot.sample,
      heapPercent: snapshot.sample.heapPercent,
    };
  }
  if (snapshot.ticks?.length) {
    state.ticks = snapshot.ticks.slice(-RIBBON_CAPACITY);
  }
}

function appendTicks(ticks) {
  if (!ticks?.length) return;
  state.ticks.push(...ticks);
  if (state.ticks.length > RIBBON_CAPACITY) {
    state.ticks.splice(0, state.ticks.length - RIBBON_CAPACITY);
  }
}

/** Fed by the Live view so the verdict can name whoever last stalled the server. */
export function noteSpike(spike) {
  state.lastSpike = spike;
}

/** Worst tick in the ribbon window, in milliseconds. */
export const worstTick = computed(() =>
  state.ticks.length ? Math.max(...state.ticks) : 0);
