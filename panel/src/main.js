import { createApp } from 'vue';
import { createRouter, createWebHashHistory } from 'vue-router';
import App from './App.vue';
import './styles/app.css';

import LiveView from './views/LiveView.vue';
import PlayersView from './views/PlayersView.vue';
import ConsoleView from './views/ConsoleView.vue';
import EventsView from './views/EventsView.vue';
import MapView from './views/MapView.vue';
import AlertsView from './views/AlertsView.vue';
import DiagnosticsView from './views/DiagnosticsView.vue';

/**
 * Hash routing.
 *
 * History mode would need the server to serve index.html for every unknown
 * path, which it does -- but the panel is one file inside a jar behind whatever
 * reverse proxy an operator happens to run, and a hash needs nothing from any
 * of them. Deep links are not the point here; the seven views are.
 */
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/live' },
    { path: '/live', component: LiveView },
    { path: '/players', component: PlayersView },
    { path: '/console', component: ConsoleView },
    { path: '/events', component: EventsView },
    { path: '/map', component: MapView },
    { path: '/alerts', component: AlertsView },
    { path: '/diagnostics', component: DiagnosticsView },
    { path: '/:rest(.*)', redirect: '/live' },
  ],
});

createApp(App).use(router).mount('#app');
