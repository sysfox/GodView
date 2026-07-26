# Security

GodView exposes an HTTP server that can kick players, ban accounts and run console
commands. It is worth treating its security seriously.

## Reporting a vulnerability

**Please do not open a public issue.**

[Report it privately](https://github.com/sysfox/GodView/security/advisories/new) through
GitHub's security advisories.

Useful things to include: what an attacker can do, what access they need to start, and the
GodView and Paper versions. A proof of concept helps but is not required — a clear
description of the flaw is enough.

You will get an acknowledgement, and a fix or an explanation of why it is not one.

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x | Yes |

## What GodView already does

Worth knowing before reporting, and worth knowing as an operator.

**Authentication**

- No default account and no default password. Until an admin runs `/godview user add`,
  nobody can sign in.
- Passwords are PBKDF2-HMAC-SHA256 at 210 000 iterations with a per-account salt. The
  iteration count travels with the hash, so raising it later leaves existing accounts
  working and upgrades them on next sign-in.
- Failed sign-ins are indistinguishable from each other, and an unknown account still pays
  the full hash cost against a dummy — returning early would make "no such user" measurably
  faster and enumerate account names.
- Sign-ins are rate-limited per address before the password is examined, because PBKDF2
  costs the server as much as the attacker.

**Sessions**

- Server-side, so revocation is immediate rather than whenever a token expires.
- Cookies are `HttpOnly` and `SameSite=Strict`, and `Secure` when served over TLS.
- Every state-changing request must also echo a CSRF token in a header — a second,
  independent barrier a cross-origin request cannot pass.
- One-time sign-in links live five minutes and redeem once.

**Authorization**

- The permission a route requires lives in the route table, not in the handler, so it
  cannot be forgotten in one place.
- The audit log needs `admin`; INFO-level console output needs `operator`, because it
  routinely contains player IP addresses.
- Player addresses are served only to roles that can act on players, and never appear in
  the live stream, which is broadcast to every subscriber regardless of role.

**Network**

- `X-Forwarded-For` is ignored unless `behind-proxy` is on *and* the connection came from a
  listed trusted proxy — and then the rightmost untrusted hop is taken, never the leftmost.
- Optional IP allowlist with CIDR support, checked before authentication.
- A strict Content-Security-Policy permitting only same-origin resources, plus `nosniff`,
  `DENY` framing, and HSTS when served over TLS.

**Other**

- Every panel-initiated change is audited, successful or refused.
- Destructive actions require an explicit confirmation flag, enforced server-side rather
  than by the panel's dialog.
- The console command endpoint applies a configurable filter, and audits the verdict either
  way.

## Deploying it safely

The default binding is `0.0.0.0:8899`, which is convenient and not safe on a public host.
Read [Securing the panel](docs/installation.md#securing-the-panel) — the short version is
to put it behind a reverse proxy with TLS, or restrict `security.ip-allowlist`, or both.

Two files deserve care:

- **`users.yml`** holds password hashes. GodView narrows its permissions to the owner where
  the filesystem supports it. It is in `.gitignore` by default. Never paste it into an
  issue.
- **`godview.db`** holds chat, commands and player addresses.

## Out of scope

- Anything requiring an existing valid session with sufficient role. An `admin` account can
  run console commands — that is the feature.
- Denial of service through legitimate authenticated use.
- Vulnerabilities in Paper, Java, or the plugins GodView reads from. Report those upstream.
