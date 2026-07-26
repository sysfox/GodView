# REST API

[← Documentation index](README.md)

The panel is a client of this API and uses nothing private. Anything the console can do,
a script can.

## Authenticating

Sign in, keep the cookie, echo the CSRF token on writes.

```bash
# Sign in. The Content-Type is required, not optional.
curl -c jar.txt -X POST http://localhost:8899/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"user":"admin","password":"..."}'
```

```json
{
  "user": "admin",
  "role": "admin",
  "csrf": "kR7f...",
  "expiresAt": 1730000000000,
  "permissions": ["view", "player_action", "command", "world_action",
                  "manage_users", "manage_config"]
}
```

```bash
# Read: cookie only.
curl -b jar.txt http://localhost:8899/api/overview

# Write: cookie plus the CSRF header.
curl -b jar.txt -X POST http://localhost:8899/api/players/Steve/kick \
  -H 'Content-Type: application/json' \
  -H 'X-GodView-CSRF: kR7f...' \
  -d '{"reason":"spam","confirm":true}'
```

Three rules the server enforces:

- **`Content-Type: application/json` is required on login.** Browsers preflight
  cross-origin JSON posts and GodView answers no preflight, so a hostile page cannot log a
  visitor into an attacker-controlled account.
- **Every non-GET needs `X-GodView-CSRF`.** The session cookie is `SameSite=Strict`, and
  the header is a second, independent barrier.
- **Destructive actions need `"confirm": true`** while
  [`security.require-confirm`](configuration.md#security) is on. Enforced here, not in the
  panel's dialog.

## Errors

Every failure is JSON with a stable `error` code and a human `message`.

```json
{ "error": "server_busy", "message": "the server's main thread did not respond within 3000ms" }
```

| Status | Means |
|---|---|
| 400 | Malformed request |
| 401 | No session, or it expired |
| 403 | Role lacks the permission, CSRF failed, or the address is not allowlisted |
| 404 | No such endpoint, player or world |
| 405 | Wrong method — the `Allow` header lists the right ones |
| 409 | Confirmation required |
| 413 | Body over `web.max-body-kb` |
| 415 | Login without a JSON content type |
| 429 | Login lockout — `Retry-After` says how long |
| 503 | The main thread did not respond, or a dependency is unavailable |

A 503 from an action endpoint means the server is not ticking. That is a real answer, and
a deliberate one: the panel is most useful exactly when the server is struggling, so a
frozen tick loop has to produce a clear message rather than a hung request.

## Public endpoints

No session required. These four are the entire unauthenticated surface.

| | |
|---|---|
| `GET /api/health` | `{"ok":true}`. For uptime monitors |
| `GET /api/auth/state` | Whether any account exists and whether you are signed in |
| `POST /api/auth/login` | Sign in |
| `GET /api/auth/link/{token}` | Redeem a one-time link from `/godview link`; 303 to the panel |

## Session

| | Permission | |
|---|---|---|
| `GET /api/auth/me` | view | Current session, role, permissions, CSRF token |
| `POST /api/auth/logout` | view | Ends this session |
| `GET /api/auth/sessions` | view | Your sessions; all of them with `manage_users` |
| `POST /api/auth/sessions/revoke` | manage_users | `{"user":"..."}`, or omit to end all |
| `POST /api/auth/password` | view | `{"currentPassword","newPassword"}`. Ends every session for the account |

## Reading

None of these touch the Bukkit API. Everything is served from caches the metrics sampler
fills on the main thread, so polling hard cannot turn into load on the tick loop.

| | Permission | |
|---|---|---|
| `GET /api/overview` | view | The dashboard document: server, sample, worlds, players, performance, storage, and 200 tick durations |
| `GET /api/pulse` | view | The compact once-a-second frame |
| `GET /api/series?names=&window=` | view | In-memory chart data. `names` is comma-separated from `tps,mspt,heap,cpu,players,chunks,entities`; `window` in ms picks the resolution |
| `GET /api/history?from=&to=&points=` | view | Long-range metrics from storage, bucketed server-side to at most `points` |
| `GET /api/spikes?live=&limit=&from=&to=` | view | Spikes, plus a per-plugin tally. `live=1` reads the in-memory ring |
| `GET /api/gc` | view | Collection counts, total pause, recent collections |
| `GET /api/worlds` | view | Per-world stats, top entity types, hotspot chunks |
| `GET /api/players` | view | Online players. Addresses included only with `player_action` |
| `GET /api/players/{name}` | view | One player plus their recent events |
| `GET /api/events?...` | view | `type`, `actor`, `search`, `from`, `to`, `limit`, `offset` |
| `GET /api/console/log?limit=&level=` | view | Console lines. Below WARN needs `command` |
| `GET /api/system` | view | JVM, host, plugins, GodView's own counters |
| `GET /api/audit?from=&to=&limit=` | **manage_users** | Who did what, including refusals |
| `GET /api/stream` | view | Server-sent events — see below |

Every list endpoint clamps `limit` to 1000 and chart requests to 2000 points.

## The live stream

```javascript
const stream = new EventSource('/api/stream');
stream.addEventListener('pulse', e => console.log(JSON.parse(e.data)));
```

| Event | Sent when |
|---|---|
| `pulse` | Every snapshot interval. Headline numbers plus the ticks elapsed since the last one |
| `snapshot` | Every few seconds. The full `/api/overview` document |
| `event` | A server event was captured |
| `console` | A console line was logged |
| `alert` | An alert fired |

Two frame types because their costs differ by two orders of magnitude — sending the full
document at pulse rate would push megabytes a minute at each open panel for data that
mostly has not changed.

The connection does not hold an HTTP worker. Frames are queued per subscriber and written
by one shared thread; a client too slow to keep up loses its oldest frames rather than
back-pressuring the server.

## Acting

Everything here hops to the main thread with a timeout and is written to the audit log,
successful or not.

| | Permission | Body |
|---|---|---|
| `POST /api/players/{name}/kick` | player_action | `{"reason"}` |
| `POST /api/players/{name}/ban` | player_action | `{"reason","durationMs","confirm"}` — omit `durationMs` for permanent |
| `POST /api/players/{name}/unban` | player_action | `{"confirm"}` |
| `POST /api/players/{name}/message` | player_action | `{"message"}` |
| `POST /api/players/{name}/gamemode` | player_action | `{"mode"}` |
| `POST /api/players/{name}/teleport` | player_action | `{"target"}` or `{"world","x","y","z"}` |
| `POST /api/broadcast` | player_action | `{"message"}` |
| `POST /api/worlds/{name}/weather` | world_action | `{"type"}` — clear, rain, thunder |
| `POST /api/worlds/{name}/time` | world_action | `{"time"}` — 0 to 24000 |
| `POST /api/worlds/{name}/save` | world_action | Synchronous; 30 s timeout |
| `POST /api/console` | command | `{"command","confirm"}` |

The console endpoint applies
[`security.command-blacklist` and `command-whitelist`](configuration.md#security) before
the command reaches the server, and audits the verdict either way — so a refused `/stop` is
on the record.

Output is not returned. It appears in `/api/console/log`, timestamped alongside the audit
entry.

## Integrations

All degrade rather than fail. A server without LuckPerms gets `available: false` and an
empty payload, not a 404.

| | Permission | |
|---|---|---|
| `GET /api/integrations` | view | Which are present, and why any are not |
| `GET /api/permissions/groups` | view | LuckPerms groups by weight |
| `GET /api/players/{name}/plugins` | view | Permissions, balance and EssentialsX state in one request |
| `GET /api/blocks?world=&x=&y=&z=&radius=&seconds=` | view | CoreProtect lookup. Radius ≤ 50, window ≤ 30 days |
| `POST /api/placeholders` | view | `{"placeholders":[...],"player":"..."}`. At most 16 per request |

## Map

| | Permission | |
|---|---|---|
| `GET /api/map/status` | view | Tile geometry, cache statistics, which worlds are enabled |
| `GET /api/map/markers?world=` | view | Players and entity hotspots |
| `GET /api/map/tile/{world}/{x}/{z}?scale=` | view | PNG. `scale` is 1, 2 or 4 |
| `POST /api/map/invalidate/{world}` | world_action | Drops cached tiles so they redraw |

A tile that is still rendering answers **202** with `Retry-After: 1` and a progress body:

```json
{ "status": "rendering", "capturedChunks": 24, "totalChunks": 64 }
```

Ask again. Tiles carry an ETag and a private cache lifetime, so revisiting ground you have
already seen costs a 304.

## Alerts

| | Permission | |
|---|---|---|
| `GET /api/alerts?live=&limit=` | view | History, or the in-memory ring with `live=1` |
| `GET /api/alerts/status` | view | Every rule with its counters, every channel with its problems |
| `POST /api/alerts/test/{rule}` | **manage_config** | Sends a test through that rule's channels |

---

Next: [Architecture](architecture.md) · [The panel](panel.md) · [Commands](commands.md)
