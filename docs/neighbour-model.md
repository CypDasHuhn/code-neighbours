# Neighbour Model

The graph is a per-LSP map of entities and the places that contain them.
Adjacency is about reference, not dependency direction.

## Nodes

Every node has a stable id scoped to its graph, a kind, a display label, an
optional source location, and a parent.

| kind | meaning |
| --- | --- |
| `ENTITY` | A declaration that code outside its file can reference. |
| `FILE` | The file that contains an entity. |
| `GROUP` | A directory or a namespace/package, chosen per graph. |

A group is **either** directory-based **or** namespace-based for a graph. The
engine does not mix both in one view. Kotlin defaults to namespace grouping.

### Entity eligibility

An entity is anything that can be referenced from outside its file:

- top-level functions, classes, objects, interfaces, type aliases, and
  properties,
- members that are not private and not local,
- constructors and companion members when they are externally reachable.

Not entities:

- local variables and parameters,
- declarations private to one file,
- anonymous declarations with no stable name,
- synthetic or compiler-generated declarations,
- declarations in gitignored files.

Visibility filtering is a frontend responsibility. Kotlin initially uses the
document symbol shape (to exclude local scopes) plus declaration modifiers read
from the declaration text (to exclude `private`). `internal` and `protected` are
entities because other files can reference them. The filter must be tested
against real Kotlin sources; it is not a parser.

### Entity identity

```
graphId / fileUri / qualifiedName / kind / signatureFingerprint
```

- `qualifiedName` comes from the document symbol hierarchy and the package or
  namespace.
- `signatureFingerprint` distinguishes overloads. It is built from the
  declaration detail or the declaration text after the name.
- When the signature cannot be recovered, the declaration range is included and
  the entity is marked unstable.
- An entity keeps its id across edits as long as file, qualified name, kind, and
  signature remain compatible. Line and column are never part of the id.

## Edges

Edges are undirected for navigation. They keep metadata about origin and
reference counts.

| kind | connects | source of truth |
| --- | --- | --- |
| `CONTAINS` | entity → file, file → group | file path and grouping policy |
| `REFERENCES` | entity ↔ entity | LSP references and definitions |
| `FILE_REFERENCES` | file ↔ file | aggregation of entity reference edges |
| `GROUP_REFERENCES` | group ↔ group | aggregation of file reference edges |

There is no separate "used by" edge. If any entity in file A references any
entity in file B, then A and B are neighbours regardless of direction. The same
applies to groups. The underlying directed observations are retained for
metadata, counts, and the Graphviz export.

An edge key is `(graph, kind, minNodeId, maxNodeId)` so it cannot be added twice.
Self-edges are suppressed at every level.

## What is next to what

- An **entity** is next to:
  - its containing file,
  - its group,
  - every entity that references it,
  - every entity it references.
- A **file** is next to:
  - the entities it contains,
  - its group,
  - every file connected by at least one entity reference.
- A **group** is next to:
  - the files and entities it contains,
  - every group connected by at least one file reference.

The seed's immediate neighbours are returned first. Further neighbours are
returned only when the user navigates to them and the renderer requests an
expansion.

## Expansion

Expansion is lazy, node-local, and cached.

```
expand(node):
  if cache has node.expansion and revision is current:
    return cached
  compute immediate neighbours from LSP facts and containment
  merge into graph store
  cache result
  return node + neighbours + edges
```

Invariants:

- Expanding a node never removes nodes or edges.
- Expanding a node twice with the same file hashes returns the same graph.
- An entity reference edge implies its file and group aggregations.
- A node returned as a neighbour is itself expandable.
- A failure is returned as an error; a partial expansion is not cached as if it
  were complete.

## Workspace scope

- One graph per LSP. There is no edge from one LSP's graph to another.
- Files are discovered from the root and filtered by gitignore.
- Explicit include/exclude globs are applied after gitignore.
- External library declarations are not nodes in v1. A reference that resolves
  outside the workspace is recorded as unresolved and does not create an edge.
- Symlinks are not followed by default.

## LSP facts used

- `initialize` and capability negotiation.
- `textDocument/documentSymbol` for entity discovery.
- `textDocument/references` for incoming edges.
- `textDocument/semanticTokens/full` for candidate reference tokens inside an
  entity.
- `textDocument/definition` for resolving candidate outgoing references.
- `workspace/symbol` for seed search when no explicit selector is supplied.

The engine does not parse the language. The declaration text is read only where
LSP does not expose a fact the contract needs, currently visibility and the
signature fingerprint.

## Example

Kotlin file `src/main/kotlin/app/OrderService.kt` declares
`app.order.OrderService.placeOrder`. It calls `app.pricing.PriceCalculator.total`
in `PriceCalculator.kt`.

Nodes:

- `OrderService.placeOrder` — entity
- `PriceCalculator.total` — entity
- `OrderService.kt`, `PriceCalculator.kt` — files
- `app.order`, `app.pricing` — namespaces

Edges:

- `CONTAINS`: `placeOrder` → `OrderService.kt` → `app.order`
- `REFERENCES`: `placeOrder` ↔ `total`
- `FILE_REFERENCES`: `OrderService.kt` ↔ `PriceCalculator.kt`
- `GROUP_REFERENCES`: `app.order` ↔ `app.pricing`

Starting at `placeOrder` shows its file, its namespace, and `total`. Moving to
`total` expands it and can reveal its own neighbours.

## Open risks

- Kotlin visibility is not part of `DocumentSymbol`; the modifier filter must be
  validated and kept conservative.
- Outgoing references depend on semantic tokens. A server without them needs an
  explicit unsupported-capability error rather than a silently incomplete graph.
- Cross-LSP files are deliberately not connected; a polyglot project is several
  graphs.
- Orthogonal crossings must not create false navigation paths. The layout tries
  to avoid unrelated crossings; any crossing that remains is a crossroad by
  definition and must be reviewed.
