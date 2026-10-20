---
name: End-to-end Kotlin
status: todo
depends-on: [009, 012]
reviewers: [correctness, lsp-fidelity]
---

# 013 — End-to-end Kotlin

## Goal

Prove the vertical slice on a real Kotlin workspace with a real LSP: seed,
expand, dot export, and browser navigation.

## Scope

- Add a small Kotlin fixture under `testing-grounds/kotlin/` with a hand-checked
  reference shape: entities, files, and namespaces with both incoming and
  outgoing references.
- Add a run script or just recipe that starts the engine, renderer, and browser.
- Verify a private local reference does not create an edge.
- Verify file and namespace neighbours appear for the seed.
- Reconcile README, docs, justfile, and config examples with the implemented
  behavior.
- Record manual checks in `docs/manual-test.md`.

## Acceptance criteria

- The fixture yields the expected neighbour set at the seed.
- The dot output renders with Graphviz.
- The browser expands nodes on navigation and caches them.
- Any non-automatable check is recorded with status and ticket id.
- `just test` passes.
- No comments.

## Out of scope

Non-Kotlin languages and packaging.

## Notes

If `kotlin-lsp` is not available in the environment, the automated tests still
pass with fake LSP fixtures; the real-LSP check stays manual and unverified.
