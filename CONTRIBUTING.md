# Contributing

Thanks for looking. This page covers the things that are specific to GodView —
[docs/development.md](docs/development.md) covers building and testing in more detail.

## Getting set up

```bash
git clone https://github.com/sysfox/GodView.git
cd GodView
mvn verify
```

You need **JDK 21+** and **Maven 3.9+**. Node is downloaded by the build, so you do not
need it installed.

Working on the Java only? `mvn verify -Dgodview.panel.skip=true` skips the whole frontend
toolchain.

Working on the panel? `cd panel && npm run dev` serves it on `:5173` and proxies `/api` to
a real server on `:8899`. Almost nothing in the panel is meaningful without one running.

## The rule everything else follows from

**GodView must not be the thing that slows the server down.** It is a monitoring plugin;
the moment it costs the tick loop anything noticeable, it has failed at its own job.

In practice, three questions to ask of any change:

1. **Does it run on the main thread?** If it is `O(entities)`, `O(chunks)`, or touches the
   disk or the network, it belongs on another thread or on a slow clock. If it genuinely
   must be on the main thread, give it a budget and make that budget honour the performance
   profile and `idle-suspend`.
2. **What happens when it fails?** A missing plugin, an unreadable database, an
   unparseable config value — none of these may stop GodView from starting. Degrade, log
   once with the reason, and make the degraded state visible in the panel.
3. **Where does the version divergence go?** In `Compat`, resolved once in a static
   initializer. Never a reflective lookup on a hot path.

## Code style

Nothing exotic. Four spaces, 100 columns, standard Java conventions. The build runs
`-Xlint:all` and is warning-free — keep it that way. Where a legacy Bukkit API is the right
choice because its Adventure replacement is not available across the supported range,
suppress narrowly at the call site and say why.

**Comments explain why, not what.** The code already says what it does. A comment earns its
place by recording a decision, a constraint, or a trap somebody would otherwise fall into.
There are a lot of these in the codebase, and they are the reason it can be picked up
again months later.

## Tests

New behaviour needs a test. The suites live in `src/test/java/dev/godview/suite/` and drive
the real thing rather than mocks — a bound socket, a SQLite file, an attached appender.

That is deliberate. Most of what these tests caught lived in the seams between components:
a bucketing rule that differed between two storage backends, a streaming flag that leaked
on an exception path, a log4j version mismatch invisible to the compiler. A mock would have
papered over all three.

One assertion, from `dev.godview.Checks`:

```java
check("cooldown suppresses repeats", rule.timesFired(), 1L);
```

Name it as a sentence about the system. A failing assertion is usually read by somebody who
did not write it, months later, and the name is the whole explanation they get.

## Commits and pull requests

Write commit messages that explain the reasoning, not just the change. A message that says
what a diff already shows is wasted; one that says why the obvious approach was wrong saves
somebody an afternoon.

Small pull requests get reviewed faster. If a change is large, say in the description which
part is the interesting one.

## Reporting bugs

[Open an issue](https://github.com/sysfox/GodView/issues/new/choose). The template asks for
`/godview status` and the Diagnostics view — both are safe to paste, neither contains
passwords, session tokens or player addresses.

**Never paste `users.yml`.** It holds password hashes.

## Security issues

Do not open a public issue.
[Report privately](https://github.com/sysfox/GodView/security/advisories/new) — see
[SECURITY.md](SECURITY.md).

## Licence

By contributing you agree your work is licensed under the [MIT License](LICENSE).
