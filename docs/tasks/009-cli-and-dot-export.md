---
name: CLI and dot export
status: todo
depends-on: [005, 006, 007]
reviewers: [correctness, api-fidelity]
---

# 009 — CLI and dot export

## Goal

Provide a one-shot CLI that opens a graph at a seed entity and exports graph
JSON and Graphviz dot.

## Scope

- Command shape:
  `codenb neighbours <root> --lsp kotlin --entity <file#qualifiedName> --out <graph.json> --dot <graph.dot>`.
- `--query` fallback uses `workspace/symbol` to select a seed.
- Expand the seed and include the returned nodes and edges. Do not crawl the
  whole workspace.
- Graph JSON uses the `api` contract.
- Dot writer: undirected graph, group clusters, node shapes by kind, escaped
  labels, reference counts, and deterministic output.
- Tests for arguments, seed resolution, JSON output, dot escaping, and
  determinism.

## Acceptance criteria

- A run against a fake LSP produces a valid graph JSON and dot file.
- `dot` renders the output without warnings.
- Labels and ids are escaped correctly.
- The CLI does not contain rendering or HTTP code.
- `just test-engine` passes.
- No comments.

## Out of scope

The daemon, HTTP renderer, web layout, and multi-language support.

## Notes

Dot is an export, not the interactive graph layout. Keep the writer independent
of Ktor and the renderer.
