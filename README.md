# GodView

A realtime web operations console for Paper servers.

GodView answers one question fast: **is the server healthy, and if not, whose fault is it?**
It samples every tick, and when one overruns it captures the main thread's stack *while the
tick is still running* — so instead of "a tick took 800 ms" you get "ShopGUI stalled the
server for 812 ms, at `com.shopgui.core.Task.run:88`".

Everything ships in one jar: the metrics collector, the storage, the HTTP server, and the
web console itself.

```
Healthy
20.00 TPS, worst tick 51 ms in the last ten seconds.
No plugin has stalled the server recently.

▏▎▏▏▎▏▏▎▏▏▏▎▏▏▎▏▏▏▎▏▏▎█▏▏▎▏▏▏▎▏▏▎▏▏▏
```

## What it does

| | |
|---|---|
| **Tick-level diagnosis** | Per-tick sampling, mid-stall stack capture, blame resolved to a plugin |
| **Live console** | TPS, MSPT, heap, CPU, players, per-world entity and chunk counts, pushed over SSE |
| **History** | Events, metrics, spikes, alerts and an audit trail in SQLite, with retention |
| **Server console** | The real Log4j output, mirrored live, with a command box |
| **World map** | Rendered from chunk snapshots on a strict per-tick budget |
| **Alerts** | Threshold and pattern rules to Discord, Telegram, ServerChan, Bark or any webhook |
| **Integrations** | Reads LuckPerms, CoreProtect, Vault, PlaceholderAPI, EssentialsX and spark when present |

## Requirements

- **Paper 1.21 – 1.21.11** (one jar covers the whole line)
- **Java 21+**

Not Spigot: GodView uses Paper-only APIs throughout. Not Folia either — per-tick timing
and main-thread stack capture assume a single main thread, and the plugin says so on
startup rather than reporting fiction.

## Getting started

1. Drop `GodView-1.0.0.jar` into `plugins/` and start the server.
2. Create the first account — there is no default login:

   ```
   /godview user add admin admin
   ```

   The password is printed once, in the console.
3. Open `http://your-server:8899` and sign in.

Full walkthrough in [docs/installation.md](docs/installation.md).

> **Before you expose it.** The panel binds `0.0.0.0:8899` by default. On a public host,
> read [Securing the panel](docs/installation.md#securing-the-panel) first — the short
> version is: put it behind a reverse proxy with TLS, or restrict `security.ip-allowlist`.

## Documentation

| | |
|---|---|
| [Installation](docs/installation.md) | Install, first account, reverse proxy, TLS |
| [Configuration](docs/configuration.md) | Every key in `config.yml`, and what it costs |
| [Commands](docs/commands.md) | `/godview`, accounts, sign-in links, permissions |
| [The panel](docs/panel.md) | What each view shows and how to read it |
| [Alerts](docs/alerts.md) | Rule types, thresholds, channels, quiet hours |
| [Map](docs/map.md) | How tiles are rendered and what they cost |
| [Integrations](docs/integrations.md) | What GodView reads from other plugins |
| [REST API](docs/api.md) | Every endpoint, with the permission it needs |
| [Architecture](docs/architecture.md) | How it stays cheap on a struggling server |
| [Troubleshooting](docs/troubleshooting.md) | When something is not working |
| [Development](docs/development.md) | Building, the test suite, the panel toolchain |

## Performance

GodView is built for the servers that need monitoring most — the small, overloaded ones.

- The per-tick sampler costs **under 0.01 ms per tick** and never suspends, because it is
  what makes spike detection possible at all.
- Expensive work (entity counts, map tiles) runs on its own slow clock, scaled by an
  automatic performance profile, and **stops entirely when nobody has the panel open**.
- The web layer never touches the tick loop for reads: everything is served from caches
  the sampler fills. A hundred open panels cost two threads and one SSE writer.
- Storage writes are queued and drained by one thread. If the disk cannot keep up, rows
  are dropped and counted rather than stalling the server.

The reasoning behind each of these is in [docs/architecture.md](docs/architecture.md).

## Contributing

Bug reports, ideas and pull requests are welcome — see
[CONTRIBUTING.md](CONTRIBUTING.md). Security issues should be
[reported privately](SECURITY.md) rather than opened as an issue.

## Licence

[MIT](LICENSE).
