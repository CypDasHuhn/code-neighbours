---
name: LSP client and workspace
status: done
depends-on: [001]
reviewers: [lsp-fidelity, correctness]
---

# 003 — LSP client and workspace

## Goal

Speak LSP over stdio and provide the workspace file set and Kotlin language
frontend that every later analysis ticket uses.

## Scope

- JSON-RPC over stdio with `Content-Length` framing.
- Lifecycle: `initialize`, `initialized`, `shutdown`, `exit`, process cleanup.
- Request ids, timeouts, cancellation cleanup, and error mapping.
- Document methods: `didOpen`, `didChange`, `didClose`.
- Query methods: `documentSymbol`, `references`, `definition`,
  `semanticTokens/full`, `workspace/symbol`.
- Capability check for semantic tokens; a missing capability is an explicit
  unsupported-language error.
- Workspace discovery from a root: include/exclude globs, gitignore rules, no
  symlink following by default.
- Kotlin frontend wiring with a configurable command, default
  `kotlin-lsp --stdio`, and `.kt`/`.kts` extensions.

## Acceptance criteria

- A fake stdio LSP server in tests exercises framing, lifecycle, timeouts, and
  each query method.
- Workspace discovery respects `.gitignore` in a git repository and falls back
  to include/exclude globs outside git.
- Kotlin files are registered under one LSP id and one graph scope.
- No test requires a live LSP installation.
- `just test-engine` passes.
- No comments.

## Out of scope

Entity identity, expansion semantics, graph storage, and the daemon.

## Notes

LSP is the only intelligence source. Do not add tree-sitter or a fallback parser.
The frontend module selects files and language ids; it does not interpret
symbols.

Deferred to ticket 015: replace the query read-lock-across-await pattern with
in-flight request tracking and cancellation. Bounded close and forced teardown
cover the correctness requirement until then.
