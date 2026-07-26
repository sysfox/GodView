# GodView documentation

Back to the [project README](../README.md).

## Setting up

| Page | What it covers |
|---|---|
| [Installation](installation.md) | Installing, the first account, reverse proxies, TLS, securing the panel |
| [Configuration](configuration.md) | Every key in `config.yml`, what it costs, and what needs a restart |
| [Commands](commands.md) | `/godview` and its subcommands, account management, one-time sign-in links |

## Using it

| Page | What it covers |
|---|---|
| [The panel](panel.md) | Each view, and how to read the tick ribbon |
| [Alerts](alerts.md) | Rule types, sustain and cooldown, the six delivery channels |
| [Map](map.md) | How the world map is rendered and what it costs the server |
| [Integrations](integrations.md) | What GodView reads from LuckPerms, CoreProtect, Vault and the rest |

## Building on it

| Page | What it covers |
|---|---|
| [REST API](api.md) | Every endpoint, its permission, and the shape it returns |
| [Architecture](architecture.md) | The design decisions, and why each one is the way it is |
| [Development](development.md) | Building from source, running the tests, working on the panel |

## When something is wrong

[Troubleshooting](troubleshooting.md) — start there. It is organised by symptom.

## A note on how these pages are written

Every page tries to say *why*, not only *what*. A setting that says "controls the sampling
interval" tells you nothing you could not guess from its name; one that says "this is the
main cost of the map on a low-end server, set it to 1–2" tells you how to make a decision.

Where a choice has a real trade-off — and several here do — the trade-off is stated rather
than hidden behind a default.
