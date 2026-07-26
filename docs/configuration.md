# Configuration

[← Documentation index](README.md)

Everything lives in `plugins/GodView/config.yml`. Run `/godview reload` after editing.
Keys marked **[restart]** are read once at startup and need a full server restart.

GodView never rejects a config file. An out-of-range number is clamped, a bad regex is
dropped, an unknown value falls back to a default — and each one logs a line saying what
happened. A typo in one alert rule should not stop the panel from starting.

- [locale](#locale)
- [web](#web) — binding, TLS, proxies
- [security](#security) — sessions, lockout, allowlist, command filter
- [performance](#performance) — sampling, spike capture, automatic degradation
- [storage](#storage) — backend and retention
- [capture](#capture) — which events are recorded
- [map](#map)
- [integrations](#integrations)
- [alerts](#alerts) — see [Alerts](alerts.md) for the rule reference

## locale

```yaml
locale: zh_CN
```

Language for the in-game command output. `zh_CN` or `en_US`; anything else falls back to
English. The panel follows your browser.

## web

```yaml
web:
  enabled: true
  bind: "0.0.0.0"        # [restart]
  port: 8899             # [restart]
  threads: 3             # [restart]
  max-body-kb: 256
  gzip: true
  behind-proxy: false
  trusted-proxies:
    - "127.0.0.1"
    - "::1"
  tls:
    enabled: false       # [restart]
    keystore: "keystore.p12"
    password: ""
```

| Key | Notes |
|---|---|
| `enabled` | Turning this off leaves metrics, alerts and commands running with no panel |
| `bind` | `127.0.0.1` when behind a reverse proxy. See [Securing the panel](installation.md#securing-the-panel) |
| `threads` | 2 is genuinely enough. Streaming responses do not hold a worker — the SSE handler writes its headers and hands the connection to one shared thread, so a hundred open panels still cost `threads` workers |
| `max-body-kb` | Request body ceiling. Checked while reading, not from `Content-Length`, since that header is whatever the client sent |
| `gzip` | Compresses text responses over 512 bytes. PNG tiles are never gzipped — they are already compressed |
| `behind-proxy` | **Only turn this on with `trusted-proxies` populated.** See below |
| `tls.*` | Built-in HTTPS. A reverse proxy is usually the better answer |

### About `behind-proxy`

`X-Forwarded-For` is a request header, which means the client writes it. GodView consults
it only when `behind-proxy` is on **and** the connection came from an address in
`trusted-proxies` — and then takes the rightmost entry that is not itself a trusted proxy,
never the leftmost.

That last detail matters. A client that sends its own `X-Forwarded-For` has the proxy
*append* to it, so the leftmost value is attacker-controlled. Taking it would let anyone
forge their address and walk past both the login rate limiter and the IP allowlist.

## security

```yaml
security:
  session-timeout-minutes: 120
  session-sliding-renew: true
  max-sessions-per-user: 5

  login:
    max-attempts: 5
    window-seconds: 300
    lockout-seconds: 300

  ip-allowlist: []

  command-blacklist:
    - "(?i)^\\s*/?(stop|restart)\\b"
  command-whitelist: []

  require-confirm: true
  audit: true
```

| Key | Notes |
|---|---|
| `session-timeout-minutes` | With `sliding-renew`, an active session does not expire mid-incident |
| `max-sessions-per-user` | A sign-in past the cap ends the least recently used session, so a new device pushes out the laptop somebody left open last week |
| `login.*` | Passwords are PBKDF2 with 210 000 iterations — about 0.1 s per attempt. That protects the passwords and would equally be a denial-of-service vector aimed at your server, which is what this rate limit exists to prevent |
| `ip-allowlist` | Plain addresses and CIDR, v4 and v6. Empty means no restriction |
| `command-blacklist` | Java regex, matched with `find()`. **Blacklist wins over whitelist** |
| `command-whitelist` | Empty means "everything not blacklisted". Non-empty means "only these" |
| `require-confirm` | Bans, unbans and console commands need `"confirm": true` in the request. Enforced by the server, not by the panel's dialog — a confirmation that only exists in JavaScript protects nobody holding curl |
| `audit` | Writes every state change, successful or refused. A rejected attempt to grant operator is exactly the line an investigation needs |

## performance

```yaml
performance:
  profile: auto
  idle-suspend: true
  snapshot-interval-ticks: 20
  tick-sampler: true
  spike-threshold-ms: 120
  spike-stack-capture: true
  spike-max-per-minute: 6
  gc-monitor: true
  world-stats-interval-ticks: 100
  degrade:
    tps-below: 17.0
    restore-tps-above: 19.0
```

### profile

`auto`, `low`, `balanced` or `full`.

A profile **scales** the numbers you set rather than replacing them — the values in this
file are the `balanced` baseline, so `snapshot-interval-ticks: 20` becomes 10 under `full`
and 40 under `low`. Anyone who has tuned their numbers keeps their ratios.

| | Intervals | Per-tick budgets | Block-entity counts |
|---|---|---|---|
| `full` | halved | doubled | yes |
| `balanced` | as written | as written | yes |
| `low` | doubled | halved | skipped |

Under `auto`, GodView starts at `full` and moves a step down after **ten** consecutive
seconds below `degrade.tps-below`, a step up after **sixty** consecutive seconds above
`restore-tps-above`. Degrading quickly and recovering slowly, with a gap between the two
thresholds, is what stops a server hovering at the boundary from flapping between
profiles every few seconds.

Note what `low` does **not** disable: tick sampling and stack capture. A struggling server
is precisely when you need to know which plugin is eating the tick, so the capture *rate*
is halved instead.

### idle-suspend

With nobody watching the panel, the expensive collection stops — world entity counts, map
tiles, snapshot pushes. Cheap sampling (TPS, memory, the tick sampler) keeps running,
because alert rules depend on it and it costs almost nothing.

Strongly recommended on small servers. Detection of "nobody is watching" is bounded by the
stream heartbeat, so it can lag a closed browser tab by up to about twenty seconds.

### spike detection

| Key | Notes |
|---|---|
| `tick-sampler` | Under 0.01 ms per tick. Turning it off removes spike detection entirely |
| `spike-threshold-ms` | A tick period over this is a spike. Normal is 50 ms, so 120 means 70 ms of overrun |
| `spike-stack-capture` | **The feature that makes GodView worth running.** A watchdog thread captures the main thread's stack while the tick is still stuck, so you get the culprit rather than an aftermath |
| `spike-max-per-minute` | Capturing a stack is not free and needs a safepoint. This bounds it |

Spikes above the ceiling are still recorded — with the duration and the tick number, just
without a stack.

### world-stats-interval-ticks

Per-world entity counts, chunk counts and hotspots. This walk is `O(entities)`, which is
why it is on its own slow clock, respects `idle-suspend`, and drops the block-entity
portion under the `low` profile. 100 ticks is five seconds; do not go much below that on a
large server.

## storage

```yaml
storage:
  type: sqlite
  file: "godview.db"
  batch-size: 200
  flush-interval-ms: 2000
  retention:
    events-days: 14
    metrics-days: 30
    spikes-days: 30
    audit-days: 90
    alerts-days: 30
```

`sqlite` persists to `plugins/GodView/godview.db`; `memory` keeps a bounded window and
loses it on restart.

`sqlite` is a preference, not a requirement. If the driver did not download or the folder
is not writable, GodView falls back to memory and says so once — refusing to start because
a chart cannot be persisted would be a poor trade for a monitoring plugin.

Writes never block the server. Rows go to a bounded queue drained by one thread; if the
disk cannot keep up, rows are **dropped and counted** rather than stalling the tick. The
count is visible in the panel's Diagnostics view, so the trade is never silent.

Retention is swept hourly. Metric rows are written every ten seconds — a month is about a
quarter of a million rows, and no chart drawn from it could tell the difference from one
per second.

## capture

```yaml
capture:
  join-quit: true
  chat: true
  commands: true
  deaths: true
  kicks-bans: true
  teleports: false
  world-load: true
  console-errors: true
  block-place: false
  block-break: false
  container-open: false
  console-buffer-lines: 400
```

A disabled event registers **no handler at all** — not a handler that returns early. The
flag is checked once, when listeners are registered.

That matters most for the block events. They fire thousands of times a second on a busy
server, which is why they are off by default. If you want block history, install
CoreProtect: GodView will query it instead of keeping a second, worse copy. See
[Integrations](integrations.md#coreprotect).

`console-buffer-lines` is how many console lines are held in memory for the panel. Set it
to 0 to disable console capture entirely.

## map

```yaml
map:
  enabled: true
  chunks-per-tick: 4
  load-unloaded: false
  unloaded-per-second: 2
  tile-cache-mb: 24
  tile-ttl-seconds: 300
  chunks-per-tile: 8
  disabled-worlds: []
```

`chunks-per-tick` is the number that matters — it is the map's entire cost to the tick
loop. Set it to 1–2 on a low-end server. Details and the reasoning in [Map](map.md).

`chunks-per-tile` must be a power of two between 1 and 32, because tile coordinates are
derived by shifting chunk coordinates.

## integrations

```yaml
integrations:
  luckperms: true
  coreprotect: true
  placeholderapi: true
  vault: true
  spark: true
  essentials: true
```

Each flag is a permission to *look*, not a requirement. Turning one off means GodView never
constructs that integration, so it never touches the other plugin's API classes at all.
See [Integrations](integrations.md).

## alerts

The `alerts:` section has its own page: **[Alerts](alerts.md)**. It covers every rule type,
how sustain and cooldown interact, all six delivery channels, and quiet hours.

One thing worth knowing here: quiet hours silence `info` and `warn` but never `critical`.
Somebody who sets quiet hours wants to sleep through a chat filter match, not through the
server going down.

---

Next: [Commands](commands.md) · [Alerts](alerts.md) · [Architecture](architecture.md)
