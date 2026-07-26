# Commands

[← Documentation index](README.md)

One command, `/godview` (alias `/gv`), usable from the console or in game.

Output follows `locale` in [config.yml](configuration.md#locale) — `zh_CN` or `en_US`.

## Permissions

| Node | Default | Grants |
|---|---|---|
| `godview.admin` | op | Everything below |
| `godview.link` | op | `/godview link` only |

`godview.link` exists so you can give a moderator a way into the panel without giving them
account management. Someone with `godview.link` and an existing panel account can sign
themselves in and nothing else.

## Reference

### `/godview status`

Server and plugin state in one screen: TPS, MSPT, heap, the active performance profile and
why it is active, the worst recent tick, spike count, panel address, session count and
storage totals.

The fastest way to confirm GodView is actually collecting after a config change.

### `/godview reload`

Re-reads `config.yml` and pushes it into every service. Sessions survive; chart history
survives; capture flags, alert rules, integrations and map settings all take effect
immediately.

Bind address, port, thread count and TLS are **not** re-read — rebinding a socket under
live connections turns a reload into an outage. The command says so every time.

Warnings from parsing are counted in the reply and written in full to the console.

### `/godview user add <name> <role>`

Creates a panel account and prints a generated password **once**.

```
/godview user add alex operator
```

Names may be 1–32 characters of letters, digits, `_`, `.` or `-`. The password is 20
characters from an alphabet with the lookalikes removed — no `0`/`O`, no `1`/`l`/`I` —
because it gets read off a console and typed by hand at least once.

The reply warns that the password is now in your chat log. That is true and unavoidable;
change it after signing in, from the panel.

### `/godview user passwd <name>`

Generates a new password and **ends every session for that account**. A password reset
usually means the old one is suspect.

### `/godview user role <name> <viewer|operator|admin>`

Changes a role. Refused if it would demote the last enabled admin — otherwise nobody could
manage accounts afterwards.

### `/godview user remove <name>`

Deletes the account and ends its sessions. Also refused for the last admin.

### `/godview user list`

Every account with its role, last sign-in time and address.

### `/godview sessions list`

Active panel sessions: account, role, address, idle time.

### `/godview sessions revoke [name]`

Ends every session for one account, or — with no name — all of them. Immediate: GodView
keeps sessions server-side precisely so "cut this off now" works on the next request
rather than whenever a token happens to expire.

### `/godview link`

**Players only.** Generates a single-use sign-in link for your own account, valid five
minutes, clickable in chat.

Needs a panel account whose name matches yours, or one created for your UUID.

The link is a credential travelling through chat — which is written to disk and echoed to
the console. The command says so when it prints it, because a secret you know is exposed
is far safer than one you do not.

### `/godview unlock <ip|all>`

Lifts a login lockout. For when you locked yourself out testing the rate limiter.

## Roles

| Role | Can |
|---|---|
| `viewer` | Read everything except the audit log and INFO-level console output |
| `operator` | The above, plus kick, ban, teleport, gamemode, broadcast, world weather and time, console commands |
| `admin` | Everything, plus panel accounts, session revocation, config reload and alert tests |

Three roles rather than a permission matrix: a server's operator team is small, and a
matrix nobody understands is worse than a coarse split everyone does. The separation that
matters is between reading, acting on players, and deciding who else can log in.

Two things are deliberately gated tighter than you might expect:

- **The audit log needs `admin`.** It records who did what, including failed attempts.
- **INFO-level console output needs `operator`.** Warnings and errors are what a viewer is
  there to watch, but INFO routinely contains player IP addresses on join.

Player addresses in the API follow the same rule — `GET /api/players` includes them only
for roles that can act on players.

## Everything is audited

Account changes made from the game appear in the same audit log as panel actions, with
`in-game` as the address. So `/godview user add` from the console and a ban from the panel
sit in one history.

---

Next: [The panel](panel.md) · [Configuration](configuration.md) · [REST API](api.md)
