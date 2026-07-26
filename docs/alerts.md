# Alerts

[← Documentation index](README.md)

Alerts exist to tell somebody once, at the right time. Two behaviours decide whether an
alerting system gets kept or muted within a week, and both are described below.

## How a rule works

```yaml
alerts:
  rules:
    - id: tps-low
      type: tps_below
      threshold: 15.0
      sustain-seconds: 30
      severity: warn
      channels: [panel, discord]
```

| Field | Meaning |
|---|---|
| `id` | Yours to choose. Appears in every notification and in the panel |
| `type` | See [rule types](#rule-types) |
| `threshold` | A value for threshold rules, a count per minute for rate rules |
| `sustain-seconds` | How long the condition must hold before firing. Threshold rules only |
| `severity` | `info`, `warn` or `critical` |
| `cooldown-seconds` | Optional. Falls back to `default-cooldown-seconds` |
| `pattern` | Java regex, for the matching rules |
| `channels` | Where it goes. Defaults to `[panel]` |

### Sustain

TPS dips below 15 for a single sample every time a world saves. Alerting on that teaches
people to ignore alerts, so a threshold rule only fires once the condition has held for
`sustain-seconds` continuously. The counter resets the moment the condition clears.

### Cooldown

After firing, the rule stays quiet for its cooldown **even though the condition persists** —
because it usually does; the server is still broken. The point is to tell somebody once,
not every second while they are already looking at it.

One detail worth knowing: if the condition never went away, the rule re-alerts the moment
the cooldown lapses rather than waiting out another sustain window. A problem that has been
continuous for five minutes deserves a prompt reminder. A condition that recovered and came
back late in the cooldown does have to sustain properly again.

## Rule types

### Threshold — sampled once a second, honour `sustain-seconds`

| Type | Fires when |
|---|---|
| `tps_below` | 1-minute TPS drops below `threshold` |
| `mspt_above` | Mean milliseconds per tick exceeds `threshold` |
| `memory_above` | Heap usage percentage exceeds `threshold` |
| `player_count_above` | More players online than `threshold` |
| `player_count_below` | Fewer players online than `threshold` |

### Occurrence — fire on a discrete event

| Type | Fires when | `pattern` matches against |
|---|---|---|
| `tick_spike` | A tick overran by more than `threshold` ms | — |
| `gc_pause` | A collection paused longer than `threshold` ms | — |
| `player_join` | Anyone joins | player name |
| `first_join` | Someone joins for the first time | player name |
| `player_quit` | Anyone leaves | player name |
| `player_death` | Anyone dies | death message |
| `chat_match` | A chat line matches | the message |
| `command_match` | A command is run | the full command |

With no `pattern`, these fire on every occurrence — which for `player_join` on a busy
server means you want a generous cooldown, or a pattern naming the few players you care
about.

### Rate — count inside a one-minute window

| Type | Fires when |
|---|---|
| `console_error` | `threshold` console errors in the last minute |
| `login_failed` | `threshold` failed panel sign-ins in the last minute |

One console error is normal on any server; twenty in a minute is a plugin in a loop. These
alert on the rate, not the occurrence.

## Channels

```yaml
alerts:
  channels:
    panel:
      enabled: true
    discord:
      enabled: false
      webhook: ""
      mention: ""            # e.g. "<@&123456789012345678>"
    telegram:
      enabled: false
      bot-token: ""
      chat-id: ""
    webhook:
      enabled: false
      url: ""
      method: "POST"
      headers: {}
    serverchan:
      enabled: false
      key: ""
    bark:
      enabled: false
      url: ""                # https://api.day.app/<your-key>
```

| Channel | Notes |
|---|---|
| `panel` | Publishes onto the live stream. No network; an open dashboard shows it immediately |
| `discord` | Webhook with an embed. `mention` is only used for `critical` — an alert nobody is pinged for is a log line |
| `telegram` | Bot API. Plain text on purpose: alert bodies contain player names and chat lines, and any markup mode would let those break the message |
| `webhook` | The alert's own JSON, POSTed wherever you point it. Custom headers supported |
| `serverchan` | ServerChan (Server酱) |
| `bark` | iOS push. `critical` alerts request an alarm sound that can wake somebody |

Delivery is **not retried** and times out after eight seconds. An alert about a five-second
stall that lands ninety seconds later has already been overtaken by you opening the panel.
Redirects are never followed — an alert URL that starts redirecting is a mistake or a
hijack, not something to chase silently.

Every alert is stored with the channels that actually accepted it. The panel shows which,
so "fired but nobody was told" is visibly different from "never fired".

## Quiet hours

```yaml
alerts:
  quiet-hours: "23:00-07:00"
```

Server local time. Wrapping windows are the normal case and work as written.

**`info` and `warn` are silenced. `critical` is not.** Someone who sets quiet hours wants
to sleep through a chat filter match, not through the server going down — silencing
everything would make this a setting nobody dares turn on.

Silenced alerts are still recorded, so the panel shows what happened overnight.

## Testing a channel

From the panel, Alerts → **Send test** on any rule. Or:

```
POST /api/alerts/test/{rule}
```

It pushes a synthetic alert through that rule's channels and bypasses quiet hours. Needs
`admin`, because it reaches an external service even though it changes nothing on the
server.

## The defaults

The shipped `config.yml` has seven rules, meant as a starting point:

| Rule | What it catches |
|---|---|
| `tps-low` | TPS under 15 for 30 s |
| `tps-critical` | TPS under 10 for 15 s |
| `tick-spike` | Any tick over 500 ms |
| `heap-pressure` | Heap over 92% for a minute |
| `console-error-burst` | 20 console errors in a minute |
| `panel-bruteforce` | 5 failed sign-ins in a minute |
| `op-granted` | `/op`, `/deop`, or a LuckPerms parent set to admin/owner |

That last one is worth keeping. Operator being granted is the single event most worth
knowing about immediately, and it goes to `critical` on every channel by default.

## Writing your own

A few patterns that come up:

```yaml
# A specific player joining
- id: owner-online
  type: player_join
  pattern: "^Notch$"
  severity: info
  channels: [panel, discord]

# Anyone using a dangerous command
- id: dangerous-commands
  type: command_match
  pattern: "(?i)^/?(gamemode|give|tp)\\s"
  severity: warn
  cooldown-seconds: 60
  channels: [panel]

# The server emptying out unexpectedly
- id: everyone-left
  type: player_count_below
  threshold: 1
  sustain-seconds: 300
  severity: info
  channels: [discord]
```

Patterns are Java regex, matched with `find()` — they do not need to match the whole
string. A pattern that fails to compile is dropped with a warning naming the rule; the rest
of the file still loads.

---

Next: [Configuration](configuration.md) · [The panel](panel.md) · [Troubleshooting](troubleshooting.md)
