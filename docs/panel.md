# The panel

[← Documentation index](README.md)

The console is a Vue application compiled into the plugin jar. It loads nothing from the
internet — no CDN, no web fonts, no analytics — and is served under a Content-Security-Policy
that permits only same-origin resources. It works on an air-gapped server.

## The tick ribbon

The strip of vertical bars in the header is the one thing to learn.

```
▏▎▏▏▎▏▏▎▏▏▏▎▏▏▎▏▏▏▎▏▏▎█▏▏▎▏▏▏▎▏▏▎▏▏▏
                      ↑
                 one tick took 340 ms
```

**One bar per tick, height by duration.** A horizontal line marks 50 ms — a tick's entire
budget. Below the line the server is keeping up; above it, it is falling behind.

The ribbon is deliberately monochrome until something is wrong. A healthy server draws an
even slate comb that recedes into the chrome; a bar over 75 ms turns amber and one over
120 ms turns red. Since nothing else on the page is coloured, one red bar is the only
coloured thing on screen — which is what makes it findable at a glance rather than another
blinking indicator you learn to ignore.

It stays in the header on every view, so you can be reading a log and still see the moment
a tick overruns.

## Live

The dashboard. It opens with a sentence, not a gauge:

> **Struggling**
> 12.40 TPS, worst tick 812 ms in the last ten seconds. **ShopGUI** stalled the server for
> 812 ms, 40 seconds ago.

At 2am that sentence is worth more than any number on the page, so the verdict leads and
everything else supports it.

Below: the full-size ribbon, four headline stats, an hour of TPS and heap, per-world entity
and chunk counts with the three commonest entity types, and the recent spike table.

**Reading the spike table.** "Blamed on" is resolved by walking the captured stack from the
innermost frame outward and naming the first plugin it meets. A plugin that calls into a
slow server method still gets the blame — which is the honest answer, since it chose to
make that call. `Minecraft` means no plugin appeared in the stack at all: vanilla work,
usually entity ticking or chunk generation.

For a proper call tree, install [spark](integrations.md#spark). GodView tells you *that* a
tick took 800 ms and which plugin's frame was on top; spark tells you where the time went
across thousands of samples.

## Players

Everyone online, refreshed every five seconds, with ping, health, gamemode, position and
play time. Operators also see addresses and get Kick and Ban.

Both actions ask for a reason, and both are written to the audit log whether they succeed
or not.

## Console

The real server console — the root Log4j output, not just Bukkit's logger, so Netty, the
world loader and other plugins' raw logging all appear.

Level filter, and a Follow toggle that only auto-scrolls when you are already at the
bottom. Someone scrolled up reading a stack trace is reading it on purpose.

Operators get a command box. Output is **not** returned inline — it appears in the log
above, timestamped. Capturing it would mean handing plugins a proxied `CommandSender`, and
plugins that cast the sender to a concrete type would break in ways you could not diagnose.

Viewers see warnings and errors only. INFO output routinely contains player IP addresses
on join.

## Events

Everything the server recorded: joins, quits, chat, commands, deaths, kicks, console
errors, world loads. Filter by type, player or free text.

Which events exist here is controlled by [`capture:`](configuration.md#capture). Block
events are off by default because they fire thousands of times a second — install
CoreProtect for block history instead.

The unfiltered list refreshes live; a filtered one does not, because reissuing your search
under you while you read the results would be worse than being a moment stale.

## Map

A top-down render of the world, drag to pan and scroll to zoom, with players drawn on top.

Tiles are produced on a strict per-tick budget, so a tile you have not visited before fills
in over a second or two rather than appearing instantly. That is the trade that keeps the
map from being the thing that lags your server. See [Map](map.md).

## Alerts

Every rule with its type, severity, fire count and cooldown state; every channel with
whether it is usable and why not; and the history of what has fired.

Admins get **Send test** on each rule, which pushes a synthetic alert through that rule's
channels. It bypasses quiet hours — it was sent by hand, right now, by somebody who wants
to see whether it arrives.

The history shows which channels accepted each alert. An alert that fired but reached
nobody is a different problem from one that never fired, and only one of them is a
misconfigured webhook.

## Diagnostics

What GodView itself is doing: JVM and host details, its own request and stream counters,
storage row counts and queue depth, GC statistics, which integrations connected, and the
full plugin list.

Two numbers to watch here:

- **Storage queued / dropped.** Dropped should be zero. Anything else means the disk could
  not keep up and rows were discarded rather than stalling the server.
- **Stream frames dropped.** A client too slow to keep up loses frames rather than
  back-pressuring the tick loop.

## Sessions and sign-out

Sessions last two hours and renew while you are active, so the panel does not sign you out
mid-incident. Signing out ends the session server-side immediately.

Changing your own password from the panel requires your current password — the session
already proves who you are, but that means a walk-up to an unlocked browser cannot silently
take the account over. It also ends every other session for the account.

---

Next: [Alerts](alerts.md) · [Map](map.md) · [REST API](api.md)
