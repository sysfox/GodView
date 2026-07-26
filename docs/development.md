# Development

[← Documentation index](README.md)

## Building

```bash
mvn package
```

Produces `target/GodView-1.0.0.jar` — server and console in one file.

Requirements: **JDK 21+** and **Maven 3.9+**. Node is *not* required: the build downloads a
pinned Node into `target/` and uses that, so the same jar comes out of any checkout and CI
needs nothing installed.

First build takes a few minutes (Paper API, Node, npm packages). After that it is seconds.

### Working on the Java only

```bash
mvn package -Dgodview.panel.skip=true
```

Skips the whole frontend toolchain. The jar still works; the server serves a placeholder
page explaining that the API is available and the panel is not.

### Working on the panel

```bash
cd panel
npm install
npm run dev
```

Vite serves on `:5173` and proxies `/api` to `127.0.0.1:8899`, so you develop against a
real running server. Almost nothing in the panel is meaningful without one.

> **One thing to watch.** The dev server has no Content-Security-Policy; the real one does,
> and it forbids inline scripts. `npm run build` is configured to inline nothing, so what
> you get in production is what you tested — but if you change `vite.config.js`, check
> `target/classes/web/index.html` afterwards and make sure every `<script>` still has a
> `src`. The test suite asserts this, which is the safety net.

## Layout

```
src/main/java/dev/godview/
  GodViewPlugin.java       entry point; owns every service, fixes startup order
  compat/                  every 1.21.x divergence, in one file
  config/                  the typed, validated view of config.yml
  util/                    JSON codec, primitive rings, time series, text, main-thread bridge
  metrics/                 tick sampler, spike watchdog, attribution, GC, world stats
  storage/                 the Storage contract, SQLite and memory implementations
  web/                     HTTP server, routing, sessions, auth
  web/api/                 REST handlers, SSE stream, snapshot building
  listener/                event capture, console mirroring
  command/                 /godview
  alert/                   rule engine and delivery channels
  integration/             LuckPerms, CoreProtect, Vault, PlaceholderAPI, EssentialsX, spark
  map/                     block colours, tile renderer, tile cache, capture scheduling
  i18n/                    command message tables

panel/                     the Vue console, built into the jar
src/test/java/dev/godview/ the verification suites
```

## Tests

```bash
mvn test
```

Eleven suites. They drive the real thing rather than mocks: a bound socket, a SQLite file on
disk, an attached Log4j appender, real PNGs decoded back out of the renderer, the built Vue
bundle served through the real static handler.

| Suite | Covers |
|---|---|
| `UtilSuiteTest` | JSON codec, primitive rings, multi-resolution time series |
| `MetricsSuiteTest` | Profiles, governor hysteresis, attribution, an end-to-end simulated stall |
| `StorageSuiteTest` | Both backends against one set of expectations |
| `AuthSuiteTest` | Proxy headers, password hashing, roles, the login guard |
| `WebSuiteTest` | The whole HTTP pipeline over real requests |
| `ApiSuiteTest` | Route table and the live event stream |
| `ConsoleSuiteTest` | Console capture through a real appender |
| `IntegrationSuiteTest` | The degradation contract with nothing installed |
| `AlertSuiteTest` | Rules and delivery, against an endpoint that records what arrives |
| `MapSuiteTest` | Colours, tile cache, real PNGs from synthetic terrain |
| `PanelSuiteTest` | The built panel through the real static handler |

Running one:

```bash
mvn test -Dtest=MapSuiteTest
```

They are slower than unit tests and that is the point. Most of what they caught lived in the
seams between components — a bucketing rule that differed between two storage backends, a
streaming flag that leaked on an exception path, a log4j version mismatch invisible to the
compiler. A mock would have papered over all three.

### Writing a new one

Everything uses one assertion, from `dev.godview.Checks`:

```java
check("cooldown suppresses repeats", rule.timesFired(), 1L);
```

The name reads as a sentence about the system, because a failing assertion is usually read
by somebody who did not write it, months later, and the name is the whole explanation they
get. Comparison is by string form, which keeps assertions about behaviour rather than about
which numeric type a JSON parser happened to pick.

## The conventions that matter

**Nothing expensive on the main thread.** If a piece of work is `O(entities)`, `O(chunks)`
or touches the disk or the network, it belongs on another thread or on a slow clock that
respects the performance profile. When it genuinely must run on the main thread, it gets a
budget.

**Fail soft, and say so.** A missing plugin, an unreadable database, an unparseable config
value — none of these may stop GodView from starting. Degrade, log once with the reason,
and make the degraded state visible in the panel. The one exception is diagnostics itself:
the console appender swallows its failures silently, because an appender that logs from
inside `append` recurses until the stack ends.

**Version divergence lives in `Compat`.** Resolved once in a static initializer, never per
call. A sampler running twenty times a second cannot afford a `getMethod` lookup and
definitely cannot afford an exception.

**Suppress deprecations at the call site, with a reason.** The build runs `-Xlint:all` and
is warning-free. Where a legacy Bukkit API is the right choice — usually because its
Adventure replacement does not exist across the supported range — the suppression is
narrow and carries a comment saying why.

**Comments explain why, not what.** The code says what it does. A comment earns its place
by recording a decision, a constraint, or a trap.

## Releasing

1. Bump `<version>` in `pom.xml`. `plugin.yml` is filtered from it, so it follows.
2. `mvn clean package` — this runs the tests.
3. Check `target/GodView-<version>.jar` contains `web/index.html` and `web/assets/`.
4. Smoke test on a real Paper server: create an account, sign in, watch the ribbon move.

The SQLite version is a single property in `pom.xml`, interpolated into `plugin.yml` so the
declared runtime library and the compile-time dependency cannot drift apart.

---

Next: [Architecture](architecture.md) · [Troubleshooting](troubleshooting.md) · [Documentation index](README.md)
