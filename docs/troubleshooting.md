# Troubleshooting

[← Documentation index](README.md)

Organised by symptom. Start with `/godview status` — it shows in one screen whether GodView
is collecting, what the panel is bound to, and whether storage is working.

## I cannot sign in

### "No account exists yet"

Expected on a fresh install. GodView ships with no default account:

```
/godview user add admin admin
```

The password is printed once, in the console.

### I lost the password

```
/godview user passwd admin
```

Generates a new one and ends every session for that account.

### "Too many failed attempts"

The login rate limiter locked your address. Wait out the `Retry-After`, or from the console:

```
/godview unlock <your-ip>
/godview unlock all
```

### Incorrect account or password, and I am sure it is right

Account names are case-insensitive; passwords are not. Check for a trailing space from
copying out of the console.

If GodView is behind a reverse proxy and you have `behind-proxy` on without listing the
proxy in `trusted-proxies`, the lockout is being applied to the proxy's address — which
means one person locking out affects everyone. See
[Securing the panel](installation.md#securing-the-panel).

## The panel will not load

### Nothing at all on port 8899

Check the console at startup. If the port was taken you will see:

```
[GodView] GodView could not start the web panel on 0.0.0.0:8899 — the panel is
          unavailable, everything else keeps running.
```

Change `web.port` and **restart** — the port is not re-read by `/godview reload`.

### "The API is running, but the panel's front-end files are not in this jar"

The jar was built with `-Dgodview.panel.skip=true`, or without a Node toolchain. The API
works; the console does not. Rebuild with the frontend enabled — see
[Development](development.md).

### A blank page

Open the browser console. GodView serves the panel under a Content-Security-Policy that
permits only same-origin resources, so a blocked script would appear there.

If you are behind a proxy that injects scripts or rewrites HTML, that is the cause.

## The panel loads but shows no live data

### "Reconnecting" in the header

The live stream is not connecting. Almost always a reverse proxy buffering server-sent
events. For nginx:

```nginx
proxy_buffering off;
proxy_read_timeout 3600s;
```

GodView sends `X-Accel-Buffering: no`, which nginx honours in most configurations, but an
explicit `proxy_buffering off` is more reliable.

### Charts are empty

Charts read from stored history. On a fresh install there is nothing yet — metric rows are
written every ten seconds, so give it a minute.

If they stay empty, check Diagnostics → Storage. If `kind` is `memory`, SQLite could not be
opened; see below.

### Everything reads zero

`/godview status` will say. If TPS is 20.00 and MSPT is 0.00 with nothing else populated,
the metrics service did not start. On Folia it says so explicitly at startup — per-tick
timing needs a single main thread.

## History is not being kept

### Storage says "memory" but the config says sqlite

GodView fell back. The console has the reason at startup:

```
GodView could not open plugins/GodView/godview.db — falling back to in-memory
history, which is lost on restart. Check that sqlite-jdbc downloaded and that
the folder is writable.
```

Two usual causes:

- **The driver did not download.** It comes through Paper's library loader on first start,
  which needs network access. An offline first start leaves it missing.
- **The folder is not writable.** Check ownership of `plugins/GodView/`.

### Rows are being dropped

Diagnostics → Storage shows `queued / dropped`. Dropped should be zero. Anything else means
the disk could not keep up and rows were discarded rather than stalling the server — which
is the deliberate trade, but it means you are losing history.

Usually a slow or contended disk. Raising `storage.batch-size` and `flush-interval-ms`
helps by writing less often in bigger transactions.

### Events are empty

Check [`capture:`](configuration.md#capture). A disabled event registers no handler at all,
so nothing is recorded for it. Block events are off by default.

## Alerts are not arriving

### Nothing fires at all

Alerts → the rule list shows every rule with a fire count and whether its type was
recognised. A rule marked "unknown type" has a typo in its `type` field — GodView logs this
at startup too.

### A rule fires but nothing arrives

Alerts → Channels shows each channel and why it is unusable, e.g. "alerts.channels.discord.webhook
is empty".

Use **Send test** on the rule. It bypasses quiet hours and pushes a synthetic alert through
that rule's channels. If it fails, the console has the reason — usually an HTTP status from
the service.

### Alerts fire but nobody is told, overnight only

Quiet hours. `info` and `warn` are silenced during the window; `critical` is not. Silenced
alerts still appear in the panel's history, marked as not sent.

### One alert becomes fifty

Raise the rule's `cooldown-seconds`, or set `sustain-seconds` so a momentary condition does
not qualify. See [Alerts](alerts.md#how-a-rule-works).

## The map is slow or blank

### Only a small patch of the world renders

Check `GET /api/map/status`:

- `loadUnloaded: false` — the map can only draw chunks that are loaded right now, so
  everything away from a player is void. Set `map.load-unloaded: true`. It defaults to true
  as of 1.1.0; if yours says false, your `config.yml` predates that.
- `persist: false` — tiles only live in memory for `tile-ttl-seconds`, so the map is
  permanently rebuilding the same small area. Set `map.persist: true`.
- `tilesIncomplete` climbing — tiles are rendering before their chunks arrive. They redraw
  on their own; raise `map.unloaded-per-second` if it is slow.
- `areasRefused` climbing — you are panning faster than `chunks-per-tick` can capture. The
  panel retries; raise the budget if the server can afford it.

Coverage grows as the panel is used. It is not a background pre-render — see
[What it is not](map.md#what-it-is-not).

### Tiles never fill in

If `chunksSkippedUnloaded` is climbing and `loadUnloaded` is true, the world is being read
from disk slower than tiles are being asked for. Raise `map.unloaded-per-second`, or go
there in game so the chunks are already loaded.

### The map is making the server lag

Lower `map.chunks-per-tick` to 1. That is the map's entire cost to the tick loop. See
[Map](map.md#what-it-costs).

### Tiles are stale

They expire after `map.tile-ttl-seconds` (five minutes by default). To force a redraw now,
Map → the world picker, or:

```
POST /api/map/invalidate/{world}
```

## Commands do nothing

### /godview prints usage and stops

The subcommand was not recognised. `/godview` on its own lists what you can run — the list
is shorter without `godview.admin`.

### "You do not have permission"

`godview.admin` for everything, `godview.link` for sign-in links only. Operators have both
by default.

### A console command from the panel is refused

`security.command-blacklist` blocked it — `/stop` and `/restart` are blocked by default.
The refusal is written to the audit log. See
[Configuration → security](configuration.md#security).

### "This action needs confirm: true"

`security.require-confirm` is on. The panel handles this for you; a script has to send
`"confirm": true` in the body. This is enforced by the server, not the panel's dialog.

## Performance concerns

### GodView itself is showing up in spike attribution

It should not — but if it does, the frame will name which part. The usual suspect is world
statistics on a server with very many entities. Raise
`performance.world-stats-interval-ticks`, or let `profile: auto` handle it.

### I want it to cost less

In order of effect:

1. `map.chunks-per-tick: 1`, or `map.enabled: false`
2. `performance.world-stats-interval-ticks: 200`
3. `performance.idle-suspend: true` (already the default)
4. `capture.chat: false`, `capture.commands: false` if you do not need the history
5. `storage.type: memory` if you do not need history across restarts

Do **not** turn off `performance.tick-sampler` to save time. It costs under 0.01 ms per
tick and is the only reason GodView can tell you what is slowing your server down.

## Still stuck

`/godview status` and the Diagnostics view together cover almost everything. Both are safe
to paste into a bug report — neither contains passwords, session tokens or player IP
addresses.

---

Next: [Configuration](configuration.md) · [Architecture](architecture.md) · [Development](development.md)
