---
name: Neighbour expansion
status: todo
depends-on: [002, 003, 004]
reviewers: [lsp-fidelity, correctness]
---

# 005 — Neighbour expansion

## Goal

Compute the immediate entity neighbours of one entity using LSP facts only.

## Scope

- Incoming: `textDocument/references` on the entity declaration range; map each
  reference location to the enclosing discovered entity; add the observation.
- Outgoing: `textDocument/semanticTokens/full` for the entity span, then
  `textDocument/definition` for candidate reference tokens; map each resolved
  location to an in-workspace entity.
- Drop targets that are local, private, outside the workspace, or unresolved.
- Deduplicate observations by entity pair and retain reference counts.
- Return the expansion as contract types; no file/group aggregation here.
- Tests with a fake LSP covering incoming, outgoing, external, local, private,
  unresolved, duplicate, and overload cases.

## Acceptance criteria

- Incoming and outgoing references both produce neighbours.
- A reference to an external library or a local declaration creates no edge.
- Duplicate references increment metadata, not duplicate edges.
- The expansion never depends on a second parser.
- `just test-engine` passes.
- No comments.

## Out of scope

Caching, graph storage, containment nodes, socket transport, and rendering.

## Notes

If semantic tokens are unavailable for the selected language, fail with an
explicit unsupported-capability error. Do not return a partial graph silently.
