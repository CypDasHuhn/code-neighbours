---
name: Web graph rendering
status: todo
depends-on: [002, 010]
reviewers: [renderer-contract, correctness]
---

# 011 — Web graph rendering

## Goal

Draw the expanded graph in the Kotlin/JS app with orthogonal connectors and a
deterministic layout.

## Scope

- Kotlin/JS browser app with a Canvas 2D renderer.
- Load the seed graph from the HTTP API and keep the expanded graph client-side.
- Deterministic layout: nodes on a grid, axis-aligned connectors only, no curves
  or diagonal segments.
- Node styling by kind, labels, group frames or labels, and reference-count
  labels where useful.
- Viewport, zoom, and pan; active node is always visible.
- Layout must not move the active node when a new node is expanded.
- Tests for layout determinism, orthogonal edges, label overlap avoidance, and
  viewport following.

## Acceptance criteria

- The seed and its immediate neighbours render from the HTTP response.
- Every connector is horizontal or vertical.
- Expanding a node appends nodes without re-laying out existing nodes.
- The app contains no socket code and no engine protocol.
- `just test-renderer` passes.
- No comments.

## Out of scope

Keyboard navigation and crossroad traversal; those are ticket 012.

## Notes

Use the renderer `shared` module for transport DTOs. Do not duplicate the engine
`api` DTOs.
