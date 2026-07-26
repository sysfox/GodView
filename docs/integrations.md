# Integrations

[← Documentation index](README.md)

GodView reads from six other plugins when they are present. Every one is optional twice
over: the plugin may not be installed, and you may switch it off in
[`integrations:`](configuration.md#integrations).

Neither case is an error. A server without LuckPerms gets an empty permissions panel, not a
broken page — and every endpoint returns `available: false` rather than a 404, so the panel
renders the same view either way.

Turning a flag off means GodView never constructs that integration, so it never touches the
other plugin's API classes at all.

The Diagnostics view lists which connected and, for any that did not, why.

## LuckPerms

**Reads:** primary group, prefix and suffix, group membership, permission nodes, and every
loaded group with its weight.

**Writes: nothing.** Deliberately. One mistyped node hands out operator, and LuckPerms
already has a well designed interface for that. GodView shows you what the permissions are
and points at LuckPerms for changing them; a monitoring panel should not be a second, worse
permissions editor.

Only users LuckPerms already holds in memory are consulted — loading an offline user is a
database round trip, and a panel refresh must not become one query per player. Players who
are not loaded show as "not loaded" rather than as having no groups.

Where: the panel's player detail, and `GET /api/permissions/groups`.

## CoreProtect

**Reads:** block history around a point.

GodView deliberately does not keep its own block history — that is exactly why
`capture.block-place` and `block-break` are off by default. Those events fire thousands of
times a second, and a plugin built for the job already does it far better. With CoreProtect
installed, GodView queries it instead of keeping a second, worse copy.

**Rollbacks are not exposed.** They rewrite the world, they are hard to undo, and getting
the radius wrong from a web form is a very expensive mistake. CoreProtect's own command has
the confirmation flow for that.

Lookups run on an HTTP worker, never the main thread — CoreProtect's API requires it, and a
database query on the tick loop is exactly the stall GodView exists to catch. Radius is
capped at 50 and the window at 30 days.

Where: `GET /api/blocks?world=&x=&y=&z=&radius=&seconds=`.

Needs CoreProtect API version 9 or newer, with the API enabled in its own config.

## Vault

**Reads:** player balance, formatted with the economy plugin's own formatting, plus the
currency name and which plugin is providing it.

Vault being installed is not the same as an economy existing — plenty of servers have Vault
for its permissions bridge alone. "Available" here means an economy provider is registered.

Read-only. Editing balances from a monitoring panel invites exactly the kind of unlogged,
unreviewed change that economy plugins have audited commands for.

Where: the panel's player detail, and `GET /api/players/{name}/plugins`.

## PlaceholderAPI

**Resolves** placeholder expressions, so the panel can show values GodView knows nothing
about — a faction name, a rank from some other plugin, a custom statistic.

```bash
curl -b jar.txt -X POST http://localhost:8899/api/placeholders \
  -H 'Content-Type: application/json' -H 'X-GodView-CSRF: ...' \
  -d '{"player":"Steve","placeholders":["%vault_eco_balance%","%player_ping%"]}'
```

Expansions are third-party code that may touch the world, so resolution hops to the main
thread with a timeout — which makes this the most expensive endpoint in the API. **At most
16 expressions per request.** One broken expansion returns an empty value for itself
without losing the other fifteen.

Output is stripped of formatting codes; expansions return text meant for chat, and a raw
`§a` renders as mojibake in a browser.

## EssentialsX

**Reads:** nickname, mute state and reason, AFK, jailed, god mode, vanished, home count.

These are the facts that explain what you are seeing. Somebody who has said nothing for an
hour looks idle until you can see they are muted, and a player apparently stuck in one place
is obviously jailed once the panel says so.

Reached reflectively, and every getter is individually guarded — EssentialsX renames things
between major versions, and one missing method should cost that one field rather than the
whole integration.

Where: the panel's player detail, and `GET /api/players/{name}/plugins`.

## spark

**Detected and pointed at**, deliberately shallow.

spark is the best profiler in the ecosystem and GodView is not competing with it. The two
answer different questions: GodView tells you *that* a tick took 800 ms and which plugin's
frame was on top; spark tells you where the time went across thousands of samples, with a
proper call tree.

So this integration reports that spark is available and surfaces the command to run:

```
/spark profiler start --timeout 60
```

Wrapping its profiler API would produce a worse version of spark's own output.

Its TPS reading is polled as a cross-check only. If GodView and spark disagree about TPS,
something is wrong with one of them and you should know.

## Why these are built the way they are

Vault, spark and EssentialsX are reached by reflection; LuckPerms, CoreProtect and
PlaceholderAPI are compiled against.

That split is not arbitrary. The first three would each add a repository and a jar to the
build for a handful of getters — not a trade worth making. The other three publish to Maven
and GodView uses enough of their APIs to justify it.

Either way, every call is guarded and every failure means "this integration has nothing for
you" rather than an exception surfacing in a panel request. The test suite asserts exactly
that: with nothing installed at all, every integration reports itself unavailable, returns
empty data, and still serializes valid JSON.

---

Next: [The panel](panel.md) · [REST API](api.md#integrations) · [Configuration](configuration.md#integrations)
