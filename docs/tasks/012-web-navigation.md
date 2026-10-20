---
name: Web navigation
status: todo
depends-on: [011]
reviewers: [renderer-contract, correctness]
---

# 012 — Web navigation

## Goal

Make the graph walkable with arrows, WASD, and hjkl from node to node through
connectors and crossroads.

## Scope

- Position model: either a node or a point on a connector.
- At a node, a direction key selects the connector leaving in that direction.
- On a connector, a direction key moves to the next node or crossroad in that
  direction. Bends are transparent.
- At a crossroad, a direction selects a departing segment; a missing direction is
  ignored.
- Lazy expansion: arriving at an unexpanded node requests `POST /api/expand` and
  caches the result client-side.
- Viewport follows the active position.
- Tie-breaking is deterministic and documented.
- Tests for node moves, connector traversal, crossroads, dead ends, ties, and
  caching of expansion requests.

## Acceptance criteria

- Starting at the seed, all four directions behave predictably.
- Crossing a junction and returning lands on the same positions.
- A node is expanded at most once per session.
- The app still uses HTTP only.
- `just test-renderer` passes.
- No comments.

## Out of scope

Search, profiles, persistence, and live updates.

## Notes

Keep the navigation state machine pure and testable; separate it from Canvas
drawing and DOM events.
