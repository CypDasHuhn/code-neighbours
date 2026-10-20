---
name: Additional language frontends
status: todo
depends-on: [013]
reviewers: [architecture, lsp-fidelity]
---

# 014 — Additional language frontends

## Goal

Add JavaScript/TypeScript, Vue, and C# frontends without changing the graph
contract or the engine core.

## Scope

- One frontend per language: LSP command, file extensions, workspace include
  rules, namespace or directory grouping, and visibility rules.
- Separate graph per LSP. No cross-language edges.
- Document the semantic-token capability requirement per server.
- Add frontend contract tests with fake LSPs and one manual fixture per language.
- Keep the renderer and socket protocol unchanged.

## Acceptance criteria

- Each added language produces entity, file, and group neighbours through the
  same API.
- A missing capability fails loudly and names the language.
- No language-specific branching appears in core graph or renderer code.
- `just test` passes.
- No comments.

## Out of scope

Language servers that do not support references, definitions, document symbols,
and semantic tokens.
