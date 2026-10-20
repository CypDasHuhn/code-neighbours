---
name: Kotlin entity discovery and identity
status: todo
depends-on: [002, 003]
reviewers: [lsp-fidelity, correctness]
---

# 004 — Kotlin entity discovery and identity

## Goal

Turn a Kotlin file's document symbols into entities with stable ids and a
conservative visibility filter.

## Scope

- Walk `DocumentSymbol` trees and keep declarations that code outside the file
  can reference.
- Exclude locals and function-scoped declarations. Exclude `private` top-level
  and member declarations. Include `internal`, `protected`, and nested
  declarations that are externally reachable.
- Build qualified names from package/namespace and the symbol hierarchy.
- Build the signature fingerprint from `detail` or the declaration text after
  the name. Mark an entity unstable when overloads cannot be distinguished.
- Id format: `graphId/fileUri/qualifiedName/kind/signatureFingerprint`.
- Visibility and package extraction may read declaration text; no parser is
  introduced.
- Tests for top-level, member, nested, private, internal, local, overload, and
  companion cases.

## Acceptance criteria

- Overloads receive distinct ids.
- A private or local declaration is not an entity.
- Re-running discovery on the same file yields the same ids.
- Entity ids never contain line or column.
- `just test-engine` passes.
- No comments.

## Out of scope

References, expansion, file/group nodes, and non-Kotlin languages.

## Notes

The filter must be conservative: when visibility is unclear, prefer excluding a
private-looking declaration only if the text proves it; otherwise include it and
let reference resolution drop unconnected nodes.
