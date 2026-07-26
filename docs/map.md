# Map

[← Documentation index](README.md)

A top-down render of your worlds, drawn from live chunk data.

## What it costs

This is the first thing to understand, because it is the only part of GodView with a
budget you may want to change.

Rendering a tile needs chunk data, and taking a `ChunkSnapshot` **must** happen on the main
thread. Everything after that — walking columns, shading, PNG encoding — is pure
computation on a detached copy and runs on its own thread.

So the main thread does the minimum: at most `map.chunks-per-tick` snapshots, then hands
them off and forgets about them.

```yaml
map:
  chunks-per-tick: 4
```

At the default of 4, a full 8×8 tile takes 16 ticks — under a second — while costing well
under a millisecond of any individual tick. **On a low-end server set this to 1 or 2.** A
map plugin that renders a whole region on demand is exactly the kind of thing GodView's own
spike detector exists to catch.

The budget also scales with the [performance profile](configuration.md#profile): doubled
under `full`, halved under `low`. And with `idle-suspend` on, no tiles are captured at all
while nobody has the panel open.

## Tiles

```yaml
map:
  chunks-per-tile: 8      # 8 x 8 chunks = 128 x 128 pixels
  tile-cache-mb: 24
  tile-ttl-seconds: 300
```

One pixel per block column. `chunks-per-tile` must be a power of two between 1 and 32,
because tile coordinates are derived by shifting chunk coordinates.

`scale` on the request downsamples for zoomed-out views: `2` gives a quarter of the pixels,
`4` a sixteenth. The panel switches automatically as you zoom.

The cache is bounded by **bytes, not tile count**. An ocean tile compresses to a couple of
kilobytes and a jungle village to fifty, so "keep 200 tiles" would mean anywhere between
half a megabyte and ten — and `tile-cache-mb` would be a number that did not mean anything.
Eviction is least-recently-used, which suits how a map is actually browsed.

Tiles also expire after `tile-ttl-seconds`, because the world changes. A map showing a base
that was demolished an hour ago is worse than a blank one.

## Unloaded chunks

```yaml
map:
  load-unloaded: false
  unloaded-per-second: 2
```

Off by default. Reading the world off disk to draw a picture of somewhere nobody is
standing is a real cost, and it should be a deliberate choice.

With it on, unloaded chunks are requested **asynchronously** and rate-limited to
`unloaded-per-second`. Nothing waits for the result: the chunk becomes available for the
next render of that tile, so it fills in a few seconds later rather than costing a tick
spent blocking on disk.

Without it, unloaded chunks render as void — the dark blue-black background.

## How a column is drawn

For each block column, walk down from the highest block until something opaque is found,
colour it, then shade it against its northern neighbour so terrain has relief. Three shading
steps rather than a smooth gradient, matching how Minecraft's own maps do it — it reads more
clearly at small sizes.

Water is handled separately: the floor beneath it is coloured and then tinted by depth,
which is what makes coastlines and drop-offs legible instead of one flat blue sheet. Kelp
and seagrass do not stop the descent, or a kelp forest would render as an opaque green slab.

### Colours

Block colours are matched by **name**, not by enumerating the `Material` enum.

Minecraft adds dozens of blocks every release. A table built purely from the enum would
render each new wood set, stone variant and copper state as blank grey until somebody
updated the plugin. Matching on the name means a block called `PALE_OAK_PLANKS` draws as
planks the day it ships, without GodView knowing it exists.

Named blocks get an exact colour; everything else falls through rules ordered
most-specific-first — anything ending in `_LEAVES` is foliage, `DEEPSLATE*` is dark stone
before the generic stone rule can claim it, `_STAIRS` resolves to wood or stone by its
prefix.

## Requesting a tile

```
GET /api/map/tile/{world}/{tileX}/{tileZ}?scale=1
```

A tile that is still being captured answers **202** rather than blocking:

```json
{ "status": "rendering", "capturedChunks": 24, "totalChunks": 64 }
```

with `Retry-After: 1`. Ask again. That is what keeps a full-screen pan — dozens of tiles at
once — from pinning every HTTP worker while the capture queue drains a tick at a time.

Ready tiles carry an ETag and a one-minute browser cache lifetime, so scrolling back over
ground you have already seen costs a 304 rather than another render.

## Turning it off

```yaml
map:
  enabled: false
```

Or per world:

```yaml
map:
  disabled-worlds:
    - "world_nether"
    - "creative_plots"
```

Requests for a disabled world get a 404 with a message saying so, and the panel hides it
from the world picker.

## What it is not

This is a live operational view — where players are, where the entity hotspots are, what
the terrain looks like right now. It is not a public web map: there is no browsable tile
archive, no persistent render of unexplored terrain, and no anonymous access.

If you want a public map, run Dynmap or BlueMap alongside. They solve a different problem
and solve it well.

---

Next: [Configuration](configuration.md) · [The panel](panel.md) · [REST API](api.md#map)
