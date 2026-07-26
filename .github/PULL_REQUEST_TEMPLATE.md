## What this changes

<!-- One or two sentences. What is different afterwards? -->

## Why

<!-- The problem this solves. If it fixes an issue, "Fixes #123" here. -->

## How it was verified

<!--
`mvn verify` is the baseline and CI runs it. What did you check beyond that?

If the change touches something the suites cover, say which assertions moved.
If it touches something they do not, that is worth a new one -- most of what
these tests caught lived in the seams between components.
-->

- [ ] `mvn verify` passes
- [ ] Tried on a real Paper server

## Anything worth knowing

<!--
Trade-offs, things deliberately left out, decisions a reviewer might question.

Two that come up often in this codebase:

- Does anything new run on the main thread? If so, what is its budget, and does
  it respect the performance profile and idle-suspend?
- Does anything new reach the panel? If it can contain player-supplied text, it
  must be bound through a template, never innerHTML.
-->
