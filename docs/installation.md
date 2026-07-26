# Installation

[← Documentation index](README.md)

## Requirements

| | |
|---|---|
| Server | Paper 1.21 – 1.21.11 |
| Java | 21 or newer |

GodView is compiled against the oldest 1.21 API so one jar covers the whole line. It is
**not** compatible with Spigot — it uses Paper-only APIs for chat capture, chunk counting
and asynchronous chunk loading. On Folia it starts, warns, and disables metrics sampling:
per-tick timing and main-thread stack capture both assume a single main thread, and
reporting numbers that do not mean anything would be worse than reporting none.

## Install

1. Put `GodView-1.0.0.jar` in `plugins/`.
2. Start the server.

On first start GodView writes `plugins/GodView/config.yml`, downloads the SQLite driver
through Paper's library loader, and binds the panel to `0.0.0.0:8899`.

You will see this in the console:

```
[GodView] Running on Paper 1.21.4
[GodView] GodView history is persisted to godview.db
[GodView] GodView panel listening on http://0.0.0.0:8899
[GodView] No panel account exists yet. Run this in the console or in game:
[GodView]   /godview user add <name> admin
```

That last line matters: **GodView ships with no default account and no default password.**
A panel that shipped with `admin/admin` on a known port would be found by scanners within
the hour. Until you create an account, nobody can sign in — including you.

## Create the first account

From the server console, or in game as an operator:

```
/godview user add admin admin
```

The first `admin` is the account name; the second is the role. GodView generates a
password and prints it once:

```
[GodView] Created account 'admin' with role admin.
Password: xTmqRfPjbWnKdVs3Hh7A
This is shown once and is now in your chat log. Change it after signing in.
```

Copy it, sign in at `http://your-server:8899`, and change it from the panel.

The three roles are described in [Commands → Roles](commands.md#roles). Give people
`viewer` or `operator` unless they genuinely need to manage accounts.

### If you would rather not type a password

Operators in game can generate a single-use sign-in link instead:

```
/godview link
```

It is clickable, valid for five minutes, and works once. It still travels through chat —
which is written to disk and echoed to the console — so the command says so when it prints
it.

## Securing the panel

The default binding is `0.0.0.0:8899`, which is convenient and **not safe on a public
host**. Pick one of these before exposing it.

### Option 1 — Reverse proxy with TLS (recommended)

Bind GodView to localhost only:

```yaml
web:
  bind: "127.0.0.1"
  port: 8899
  behind-proxy: true
  trusted-proxies:
    - "127.0.0.1"
    - "::1"
```

Then put nginx or Caddy in front of it. Caddy, in full:

```
godview.example.com {
    reverse_proxy 127.0.0.1:8899
}
```

nginx needs two extra lines for the live stream, or the panel will appear to hang:

```nginx
location / {
    proxy_pass http://127.0.0.1:8899;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    # Server-sent events: without these, nginx buffers the stream and the panel
    # never receives an update.
    proxy_buffering off;
    proxy_read_timeout 3600s;
}
```

**`behind-proxy` and `trusted-proxies` must both be set.** `X-Forwarded-For` is a header
the client writes, so GodView ignores it unless the connection came from an address you
have listed. Turn `behind-proxy` on without listing your proxy and the header is still
ignored — deliberately, because the safe reading of a misconfiguration is to trust
nothing. Get it wrong the other way and anyone can forge an address to bypass the login
rate limiter and the IP allowlist.

### Option 2 — Built-in TLS

Workable, but a reverse proxy handles certificate renewal and you almost certainly want
one anyway.

```yaml
web:
  tls:
    enabled: true
    keystore: "keystore.p12"
    password: "your-keystore-password"
```

The keystore is PKCS#12, resolved relative to `plugins/GodView/`. To convert a Let's
Encrypt certificate:

```bash
openssl pkcs12 -export \
  -in fullchain.pem -inkey privkey.pem \
  -out keystore.p12 -name godview
```

TLS settings are read at startup only — changing them needs a server restart, not
`/godview reload`.

### Option 3 — Restrict by address

If the panel is only ever reached from your own network:

```yaml
security:
  ip-allowlist:
    - "192.168.1.0/24"
    - "10.0.0.5"
    - "2001:db8::/32"
```

Both plain addresses and CIDR ranges work, IPv4 and IPv6. An empty list means no
restriction. Requests from anywhere else get a 403 before authentication is even
considered.

This composes with the other options — an allowlist behind a reverse proxy is stricter
than either alone, as long as `trusted-proxies` is set so the real client address is the
one being checked.

## What else is on by default

| | Default | Note |
|---|---|---|
| Login lockout | 5 failures in 5 minutes | Locks that address for 5 minutes |
| Session lifetime | 2 hours, renewed while active | |
| Sessions per account | 5 | A sixth sign-in ends the least recently used |
| Audit log | on | Every panel-initiated change, successful or not |
| Command filter | `/stop` and `/restart` blocked | See [Configuration](configuration.md#security) |
| Confirmation | required | Bans, unbans and console commands need an explicit confirm |

## Upgrading

Replace the jar and restart. `config.yml` is not overwritten; new keys take their
defaults, and GodView logs a warning for any value it had to clamp or could not parse.

The SQLite schema is versioned and created with `IF NOT EXISTS`, so an existing
`godview.db` is carried forward.

## Uninstalling

Remove the jar. `plugins/GodView/` is left alone — delete it yourself if you want the
history and accounts gone. The database is a plain SQLite file if you want to keep it.

---

Next: [Configuration](configuration.md) · [Commands](commands.md) · [The panel](panel.md)
