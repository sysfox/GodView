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

**Every scale is produced by one capture.** When an area is snapshotted, scales 1, 2 and 4
are all rendered from the same chunk data and cached together. The column walk shrinks with
the square of the scale, so the extra two cost about a third of the first — and zooming out
then costs nothing at all. Before 1.1.0 the capture queue was keyed by scale, which meant
zooming out asked for the same ground again at sixteen times the tile count and the budget
never got anywhere.

The memory cache is bounded by **bytes, not tile count**. An ocean tile compresses to a
couple of kilobytes and a jungle village to fifty, so "keep 200 tiles" would mean anywhere
between half a megabyte and ten — and `tile-cache-mb` would be a number that did not mean
anything. Eviction is least-recently-used, which suits how a map is actually browsed.

Tiles also expire after `tile-ttl-seconds`, because the world changes. A map showing a base
that was demolished an hour ago is worse than a blank one.

## Stored tiles

```yaml
map:
  persist: true
  persist-days: 7
```

Rendered tiles are written to `plugins/GodView/map/`, one directory per world, one file per
tile and scale.

This is what makes the map accumulate rather than resetting. With memory alone, a tile older
than `tile-ttl-seconds` has to be re-captured at `chunks-per-tick`, and the map can never
show more world than that budget produces inside one TTL — a small patch around wherever
players happen to be standing. With a store, an area is captured once and stays: panning
over ground somebody already looked at costs a file read, not sixty-four chunk snapshots.

**Only complete tiles are stored.** A tile rendered while some of its chunks were unloaded
has holes in it, and persisting that would make the holes permanent. Those stay in memory on
a twenty-second lifetime and are redrawn once the chunks arrive; the response carries
`X-GodView-Complete: false` so the panel knows to ask again.

Files older than `persist-days` are deleted on read, and swept at startup on a background
thread. Set `persist: false` to keep everything in memory, at the cost of the map never
growing past the tick budget.

## Unloaded chunks

```yaml
map:
  load-unloaded: true
  unloaded-per-second: 8
```

**On by default since 1.1.0.** With it off the map can only draw the chunks that happen to
be loaded right now — a few dozen around each player — and everything else renders as void.
That is not a subtle difference: it is the difference between a map and a smear.

Unloaded chunks are requested **asynchronously** and rate-limited to `unloaded-per-second`.
Nothing waits for the result: the chunk becomes available for the next render of that tile,
so it fills in a few seconds later rather than costing a tick spent blocking on disk.

Turn it off if the server's disk is the bottleneck. The map will then only ever show where
people have been while the panel was open — which, with `persist` on, still accumulates.

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
ground you have already seen costs a 304 rather than another render. They also carry
`X-GodView-Complete`; `false` means some chunks were missing, the browser lifetime drops to
five seconds, and the tile is worth asking for again shortly.

The panel requests tiles nearest-the-middle-first, a few at a time, and retries everything
with a backoff. Every outcome is retryable — that is the point. Before 1.1.0 a tile that
answered anything other than 200 or 202 was cached as a permanent blank and never requested
again, so the map filled in one patch and stopped.

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
the terrain looks like right now. It is not a public web map: there is no pre-rendered
archive of terrain nobody has looked at, no tile pyramid beyond scale 4, and no anonymous
access. Coverage grows as the panel is used, not as a background job.

If you want a public map, run Dynmap or BlueMap alongside. They solve a different problem
and solve it well.

---

Next: [Configuration](configuration.md) · [The panel](panel.md) · [REST API](api.md#map)
