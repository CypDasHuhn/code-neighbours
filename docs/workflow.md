# Workflow

How work is planned, executed, reviewed, and committed for the design in
[architecture.md](architecture.md) and [neighbour-model.md](neighbour-model.md).

## Roles

The orchestrator is the primary session. All other roles run as depth-1
subagents; subagents never spawn subagents.

| Role | Runs as | Job | Writes |
| --- | --- | --- | --- |
| orchestrator | primary session | Owns the ticket queue, statuses, sequencing; drafts tickets; spawns workers; commits. | anything |
| implementor | `general` subagent | The only role that edits source. Implements one ticket, owns build/tests/format. | source + tests |
| reviewer | `general` subagent | Reads and reasons over one ticket's diff. Correctness, contracts, structure. | its report file only |
| scribe | `general` subagent | Maintains `docs/`. | `docs/` only |
| recon | `explore` subagent | Read-only reconnaissance. | nothing |

No named implementor or reviewer agents are configured. The roles are enforced
by the orchestrator's prompts: an implementor prompt grants source edits and
build commands, a reviewer prompt forbids them. Never let one subagent play two
roles on the same ticket in the same round.

## Pipeline

Per ticket (`docs/tasks/NNN-slug.md`):

1. The implementor implements the ticket and leaves the tree green, reporting the
   exact commands and their results.
2. The reviewer reviews the diff: logic, state, data, and contracts only. It
   writes `docs/reviews/<id>/<role>.md` and returns a one-paragraph summary.
3. The implementor addresses every finding: fix it, or defer it to a named
   ticket with a reason. Findings carry no severity labels; a finding is work.
4. Round 2 runs only if round 1 produced findings: resume the same sessions
   (`task_id`) and seed them with round-1 state. A reviewer never re-reports an
   addressed finding; it concurs or dissents. Zero findings closes the ticket.
5. Commit. Any acceptance criterion that cannot be automated goes to
   [manual-test.md](manual-test.md), naming the ticket.

Extra review dimensions join a ticket's `reviewers` list only when its surface
demands it. The dimensions in use:

- `correctness` — logic, state, data, contracts, error paths. The default.
- `api-fidelity` — the frozen graph and socket contract.
- `lsp-fidelity` — LSP capability use, reference/definition semantics, and
  workspace scope.
- `architecture` — module boundaries, backend/renderer separation, and the
  absence of a second analysis backend.
- `renderer-contract` — HTTP/socket contracts and navigation semantics.

## Verification ownership

- The implementor is the only role that runs the build, tests, and formatter.
  Reviewers assume a green handover; a red tree is a process problem, not a
  finding.
- Reviewers read and reason only. `git diff`, `git log`, and `git status` are
  allowed; build-class commands are not.
- A suspected failure is a finding with a reproduction path; the implementor
  reproduces it.

Engine commands are `just build-engine`, `just test-engine`, and
`just test-lsp`. Renderer commands are `just build-renderer` and
`just test-renderer`. The full suite is `just test`.

## Ticket format

`docs/tasks/NNN-slug.md` with YAML frontmatter:

```yaml
---
name: Human readable title
status: todo | in-progress | review | done | blocked
depends-on: [NNN]
reviewers: [correctness]
---
```

Body: goal, scope, acceptance criteria, out of scope, notes.
`depends-on` lists ticket ids that must be `done` first. Status is owned by the
orchestrator.

## Review reports

`docs/reviews/<ticket-id>/<role>.md`, one file per reviewer per ticket. Round
body: verdict, findings (location, problem, suggested fix), concurs/dissents. The
reply carries a one-paragraph summary so the implementor does not re-read files.

## Manual gate

Acceptance criteria that cannot be automated (live LSP against a real Kotlin
project, browser keyboard navigation, dot rendering) go in
[manual-test.md](manual-test.md) with status and, when unverified, a reason. A
ticket may be `done` while an entry is unverified, but the entry must exist and
name the ticket.

## Commits

- Conventional commits: `type(domain): description`, imperative, lowercase,
  ≤72 chars, no trailing period.
- One commit per unit of completed work; stage precisely, never `git add -A`.
- No authorship or tool trailers. Never push, merge, or force-push unless asked.

## Definition of done

- Acceptance criteria met; build green.
- Reviews complete; every finding fixed or explicitly deferred to a named ticket
  with a reason.
- Non-automatable criteria recorded in [manual-test.md](manual-test.md).
- Ticket `status: done` and committed.
