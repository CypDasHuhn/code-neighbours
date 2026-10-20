---
name: Graph store and lazy cache
status: done
depends-on: [002, 005]
reviewers: [correctness, api-fidelity]
---

# 006 — Graph store and lazy cache

## Goal

Maintain one per-LSP graph and cache node expansions by content revision.

## Scope

- In-memory graph store keyed by `GraphId`.
- Merge expansions idempotently; reject duplicate node ids with conflicting data;
  suppress self-edges.
- Cache expansion results keyed by node id and revision (file hashes).
- Invalidate cached expansions when any contributing file hash changes.
- Optional disk cache interface; v1 may ship memory-only behind the interface.
- LRU or size-bounded eviction. A cache miss must be correct, not just slower.
- Tests for merge idempotence, stale revision, eviction, conflicting nodes, and
  repeated expansion.

## Acceptance criteria

- Expanding the same node twice with the same revision returns the same graph.
- A revision change invalidates the affected entries.
- A cache miss recomputes and returns the correct result.
- The store never removes nodes or edges.
- `just test-engine` passes.
- No comments.

## Out of scope

LSP calls, containment aggregation, daemon transport, and UI.

## Notes

The store is per LSP. There is no global graph and no cross-LSP merge.
