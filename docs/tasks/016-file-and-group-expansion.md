---
name: File and group expansion
status: todo
depends-on: [005, 007, 008]
reviewers: [correctness, api-fidelity]
---

# 016 — File and group expansion

## Goal

Expand `FILE` and `GROUP` nodes with real discovery and aggregation facts so the
whole graph is walkable, not only entity nodes.

## Scope

- File expansion: entities in the file, the file's group, and files connected by
  entity references.
- Group expansion: files and entities in the group and connected groups.
- Use LSP discovery for facts not yet in the store; do not return a projection
  of the current snapshot as a complete expansion.
- Preserve graph revision semantics; a derived expansion must not go stale as
  the graph grows.
- Keep `GraphInfo.nodeKinds` as all returnable kinds (`ENTITY`, `FILE`,
  `GROUP`) and add `FILE` and `GROUP` to the initialize
  `expandableNodeKinds` capability once both kinds expand.
- Tests for file/group neighbours, repeated expansion after new nodes arrive,
  cache behavior, and containment/reference aggregation.
- Socket-level tests for `node/expand` on file and group nodes.

## Acceptance criteria

- Expanding a file node returns its entities, group, and connected files.
- Expanding a group node returns its files, entities, and connected groups.
- Repeated expansion reflects new nodes added since the previous expansion.
- `expandableNodeKinds` advertises all implemented kinds in enum order.
- `just test-engine` passes.
- No comments.

## Out of scope

Renderer layout, navigation, HTTP transport, and cross-LSP edges.

## Notes

Ticket 008 explicitly returns `unsupported-capability` for file and group
expansion and advertises only `ENTITY` in `expandableNodeKinds` until this
ticket lands. `nodeKinds` already lists all returnable kinds.
