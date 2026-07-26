/* Talking to the plugin.
 *
 * Every state-changing request carries the CSRF token the session was issued
 * with; the server refuses anything else. The token lives only in memory --
 * putting it in localStorage would outlive the session it belongs to and leave
 * a shared browser holding a token for somebody else's account. */

let csrf = null;
let onUnauthenticated = () => {};

export function setCsrf(token) {
  csrf = token;
}

export function onSignedOut(handler) {
  onUnauthenticated = handler;
}

/** Thrown for any non-2xx response, carrying the server's own error code. */
export class ApiError extends Error {
  constructor(status, code, message) {
    super(message || code || `HTTP ${status}`);
    this.status = status;
    this.code = code;
  }
}

async function request(method, path, body) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && csrf) headers['X-GodView-CSRF'] = csrf;

  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
    });
  } catch {
    // The panel is served by the server it monitors, so a network failure here
    // usually means that server has gone away.
    throw new ApiError(0, 'unreachable', 'The server is not responding.');
  }

  if (response.status === 401) {
    onUnauthenticated();
    throw new ApiError(401, 'unauthenticated', 'Your session ended. Sign in again.');
  }
  if (response.status === 204) return null;

  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = null;
    }
  }

  if (!response.ok) throw new ApiError(response.status, payload?.error, payload?.message);
  return payload;
}

export const api = {
  get: (path) => request('GET', path),
  post: (path, body) => request('POST', path, body ?? {}),

  /** Raw response, for the map tiles that answer 202 while they render. */
  async raw(path) {
    const response = await fetch(path, { credentials: 'same-origin' });
    if (response.status === 401) onUnauthenticated();
    return response;
  },
};

/**
 * The server-sent event stream.
 *
 * EventSource reconnects by itself, but only for a dropped connection. A
 * restarted server answers 401 until a session exists again, and the browser
 * would retry that forever behind a spinner that never resolves -- so failures
 * are counted, backed off to a ceiling, and reported.
 */
export class LiveFeed {
  constructor(handlers, onState) {
    this.handlers = handlers;
    this.onState = onState;
    this.source = null;
    this.retries = 0;
    this.timer = null;
    this.closed = false;
  }

  start() {
    this.closed = false;
    this.open();
  }

  stop() {
    this.closed = true;
    clearTimeout(this.timer);
    if (this.source) {
      this.source.close();
      this.source = null;
    }
  }

  open() {
    if (this.closed) return;
    this.onState(this.retries === 0 ? 'connecting' : 'lost');

    const source = new EventSource('/api/stream');
    this.source = source;

    source.onopen = () => {
      this.retries = 0;
      this.onState('live');
    };

    source.onerror = () => {
      source.close();
      if (this.closed) return;
      this.onState('lost');
      // A server that is down stays down for a while; hammering it while it
      // boots helps neither of us.
      this.retries += 1;
      const delay = Math.min(30000, 1000 * 2 ** Math.min(this.retries, 5));
      this.timer = setTimeout(() => this.open(), delay);
    };

    for (const [name, handler] of Object.entries(this.handlers)) {
      source.addEventListener(name, (event) => {
        try {
          handler(JSON.parse(event.data));
        } catch {
          // One malformed frame must not take down the stream.
        }
      });
    }
  }
}
