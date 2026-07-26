# Architecture

[← Documentation index](README.md)

A monitoring plugin has one obligation above all others: **it must not be the thing that
slows the server down.** Every design decision here follows from that, and this page is
mostly a record of where the trade-offs were made and why.

## The layers

```
                    ┌──────────────────────────────────────────┐
   main thread      │  TickSampler   1/tick    <0.01 ms        │
                    │  MetricsService  1/s     TPS, heap, CPU  │
                    │  WorldStats      1/5s    O(entities)     │
                    │  MapService      1/tick  N snapshots      │
                    │  EventCapture    on event                 │
                    └───────────────┬──────────────────────────┘
                                    │ volatile-swapped snapshots
                    ┌───────────────▼──────────────────────────┐
   own threads      │  SpikeWatchdog   stack capture           │
                    │  StorageWriter   batched SQLite          │
                    │  EventStream     one writer, all clients │
                    │  AlertDispatcher HTTP delivery           │
                    │  MapRenderer     PNG encoding            │
                    └───────────────┬──────────────────────────┘
                                    │
                    ┌───────────────▼──────────────────────────┐
   HTTP workers     │  WebServer  →  routes  →  handlers       │
                    │  reads: from caches, never Bukkit        │
                    │  writes: hop to main thread with timeout │
                    └──────────────────────────────────────────┘
```

The rule the arrows encode: **the main thread produces, everything else consumes.** No
HTTP request ever waits on the tick loop for a read.

## Spike attribution

This is the feature the rest of the design exists to support.

Knowing a tick took 800 ms is nearly useless. Knowing *what the server was doing* during
those 800 ms is the whole point — and by the time the tick ends, the evidence is gone.

So there are two pieces:

**The sampler** runs as a 1-tick repeating task. Each invocation is separated from the last
by exactly one full tick of work, so the gap between them is the tick's real duration: 50 ms
on a healthy server, and however long it actually took once the server falls behind. Cost
is one `nanoTime`, one array store, one comparison.

**The watchdog** is a separate daemon thread watching the timestamp the sampler publishes.
The moment a tick has been running longer than the threshold, it takes a stack trace of the
main thread **while it is still stuck**, resolves it to a plugin, and hands the result back.
The sampler joins that evidence to the exact duration when the tick finally ends.

Two honest costs. `Thread.getStackTrace()` on another thread needs that thread to reach a
safepoint, so a tick stuck in a tight counted loop can delay the capture until the loop
ends — the watchdog blocks, never the server. And the capture is not free, which is why it
is rate-limited.

### Why tick period is not MSPT

They are different numbers and GodView reports both, from their proper sources.

- **MSPT** is what Paper measures: time spent working, excluding the sleep at the end of a
  spare tick. On an idle server it reads about 2 ms.
- **Tick period** is the gap between two 1-tick tasks: `max(50 ms, actual work)`. On an idle
  server it reads 50 ms.

Period is what matters for lag detection — a period of 300 ms means 250 ms of overrun,
whatever caused it. Reporting period as MSPT would be wrong by an order of magnitude.

### Blame resolution

Attribution walks the captured stack from the innermost frame outward and names the first
plugin it meets, matching on package prefixes built from each loaded plugin's main class.

Innermost-first is what makes it correct rather than merely plausible: when a plugin calls
into the server and the server is where the time goes, the plugin frame still sits below it
and gets the blame — which is honest, since the plugin chose to make that call.

Prefixes include ancestors, so a plugin whose main class sits in `com.example.core` is still
recognised in `com.example.listeners`. An ancestor claimed by two different plugins is
dropped for both rather than guessed at, and matching prefers the longest prefix.

`StackTraceElement` from another thread carries only names, so resolving each one to a
`Class` would mean class loading on the watchdog thread — expensive, and able to run static
initializers as a side effect of diagnostics. Package prefixes are the right trade.

## Automatic degradation

`performance.profile: auto` moves between three profiles as TPS changes. A profile
**scales** the configured numbers rather than replacing them, so anyone who tuned their
values keeps their ratios.

Degrade after **ten** consecutive bad seconds; recover after **sixty** good ones. Combined
with a gap between the two thresholds, that gives hysteresis on both value and time — a
server hovering at the boundary settles on one profile instead of thrashing.

What `low` does **not** disable: tick sampling and stack capture. A struggling server is
precisely when you need to know which plugin is eating the tick, so the capture rate is
halved rather than turned off.

## Idle suspend

With nobody watching, the expensive collection stops entirely. Cheap sampling continues,
because alert rules depend on it and it costs almost nothing.

The subscriber count comes from the live stream, and a disconnected client is only
discoverable by writing to it — that is all TCP will report, and often not on the first
attempt, since a write into a socket the peer has closed usually lands in the local buffer
and fails on the one after. With frames flowing that resolves in a couple of seconds; on a
completely idle stream the heartbeat bounds it to about twenty. So the count can briefly
overcount after somebody closes a tab. The alternative would be a liveness probe costing
more than the collection it is trying to avoid.

## Storage

One rule: the main thread never waits on disk.

Every write goes to a bounded queue drained by a single thread that batches into one
transaction. When the queue fills — a disk stall, a burst of events — rows are **dropped
and counted** rather than back-pressuring the tick. Losing an audit line is bad; stalling
the server to write one is worse, and the count is visible so the trade is never silent.

A single writer also removes the need for write locking, since SQLite serializes writers
anyway. Readers use a separate connection, which WAL journalling lets run without blocking
the writer. `purge()` enqueues a request rather than running inline, because a JDBC
connection is not thread-safe and a retention sweep from the main thread would be using the
writer's connection underneath it.

Chart queries are bucketed in SQL. A month of samples is a quarter of a million rows and
none of them need to reach the heap to be averaged. Bucket width comes from the span the
samples actually cover, not the span requested — an open-ended range would otherwise
collapse everything into one point.

## The web layer

The JDK's own `com.sun.net.httpserver`. No shaded framework, no extra megabytes, no
classloader argument with the Netty a Minecraft server already runs.

**Authorization lives in the route table**, not in handler bodies. A route is registered
with the permission it needs, so the check cannot be forgotten in a handler; only four
public routes exist and they are declared in one place.

The worker pool is deliberately tiny and stays viable because streaming responses do not
hold a worker: the SSE handler writes its headers, hands the stream to the broadcaster and
returns.

### Security decisions worth naming

- **`X-Forwarded-For` is a header the client writes.** It is consulted only when
  `behind-proxy` is on *and* the peer is a trusted proxy, and then the rightmost untrusted
  hop is taken, never the leftmost — a client that sends its own header has the proxy
  append to it.
- **Login is rate-limited before the password is examined.** PBKDF2 costs us a tenth of a
  second per guess as much as it costs an attacker, so an unthrottled endpoint would be a
  denial-of-service aimed at the server being monitored.
- **Failed logins are indistinguishable**, and an unknown account still pays the full hash
  cost against a dummy — returning early would make "no such user" measurably faster and
  enumerate accounts.
- **No default account.** A panel shipping with `admin/admin` on a known port would be found
  by scanners within the hour.
- **The live snapshot carries no IP addresses.** One SSE frame goes to every subscriber
  whatever their role, so nothing role-dependent can live in it.

## The panel

A Vue application compiled into the jar by Maven, served under a CSP that permits only
same-origin resources. Vite is configured to inline nothing — a bundler that inlined its
bootstrap script would produce a blank page that works perfectly in `npm run dev` and fails
only in production, which is why the test suite serves the built output through the real
static handler and asserts the CSP against the emitted HTML.

## Version compatibility

One jar covers 1.21 through 1.21.11, compiled against the oldest of them. Everything that
moved inside that range is reached reflectively in a single file, `Compat`, resolved once in
a static initializer and never per call — a metrics sampler running twenty times a second
cannot afford a lookup, and definitely cannot afford an exception.

What actually moved: the `Attribute` enum became a registry interface in 1.21.3 and
`GENERIC_MAX_HEALTH` was renamed; `BanList.Type` gave way to a top-level `BanListType`; and
Paper's cheaper chunk and block-entity counters are not on every fork.

## Testing

Eleven suites, run by `mvn test`, driving the real thing rather than mocks: a bound socket,
a SQLite file on disk, an attached Log4j appender, real PNGs decoded back out of the
renderer. Slower than unit tests and worth it — most of what they caught was in the seams
between components, which is exactly what a mock papers over.

Some of what they found, as examples of the class of defect:

- SQLite bucketed chart data by the *requested* window rather than the data's own span, so
  an open-ended range collapsed 30 samples into one point. The memory backend did not have
  the bug, which is why a shared contract test found it.
- The SSE streaming flag lived in a `ThreadLocal`, so a handler that threw mid-stream left
  it set and leaked the connection.
- `log4j-core` was pinned to a version the API did not match — invisible to a plain compile,
  fatal the moment anything touched `Configurator`.

See [Development](development.md) for running them.

---

Next: [Development](development.md) · [Configuration](configuration.md) · [REST API](api.md)
