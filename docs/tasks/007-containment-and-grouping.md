---
name: Containment and grouping
status: done
depends-on: [002, 004]
reviewers: [correctness, architecture]
---

# 007 — Containment and grouping

## Goal

Add file and group nodes and aggregate entity reference edges to file and group
edges.

## Scope

- Entity→file `CONTAINS` edges.
- File→group `CONTAINS` edges, with `GroupingKind` selected per graph:
  directory from the relative path, or namespace/package.
- Kotlin package extraction for namespace grouping; directory grouping needs no
  source parsing.
- Aggregate entity references to `FILE_REFERENCES` between different files and
  `GROUP_REFERENCES` between different groups.
- Suppress self-edges at every level.
- Tests for same-file references, cross-file references, same-group references,
  namespace vs directory grouping, and nested paths.

## Acceptance criteria

- Every entity has exactly one containing file and one group.
- Reference aggregation is deduplicated and undirected.
- Same-file and same-group references do not create self-edges.
- Group ids are stable for the same namespace or relative path.
- `just test-engine` passes.
- No comments.

## Out of scope

Graph storage, cache, LSP calls, and rendering.

## Notes

A group is either directory-based or namespace-based for one graph. Do not mix
both in one view.
