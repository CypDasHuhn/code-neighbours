---
name: API graph contract
status: done
depends-on: [001]
reviewers: [api-fidelity, correctness]
---

# 002 — API graph contract

## Goal

Freeze the node, edge, expansion, and error types used by the engine, CLI, and
socket.

## Scope

- Package `dev.codenb.api` in the `api` module.
- `GraphId`, `NodeId`, `NodeKind` (`ENTITY`, `FILE`, `GROUP`), `GroupingKind`
  (`DIRECTORY`, `NAMESPACE`), `EdgeKind` (`CONTAINS`, `REFERENCES`,
  `FILE_REFERENCES`, `GROUP_REFERENCES`).
- `Node`, `Edge`, `EdgeOrigin`, `Expansion`, `GraphInfo`, `EngineError`.
- Node ids are graph-scoped strings. Edge keys are `(kind, min, max)`.
- Reference edges keep directed origins and counts as metadata while navigation
  treats them as undirected.
- kotlinx.serialization codec with stable field names and a protocol version.
- Contract tests for round-trip serialization, edge-key identity, and
  self-edge rejection.

## Acceptance criteria

- `just test-engine` passes.
- Types contain no LSP, file-system, or renderer dependencies.
- JSON is stable and explicit; no polymorphic guessing.
- The contract encodes the neighbour model, not a particular language.
- No comments.

## Out of scope

Graph storage, expansion logic, socket framing, and dot output.

## Notes

This module changes only deliberately after this ticket. Other modules depend on
it; it does not depend on them.
