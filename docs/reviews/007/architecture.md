## Round 1

**Verdict:** Changes requested.

The containment logic is on the right side of the boundary: grouping and file/group id construction live in core, the LSP and filesystem work stays in frontend, and the builder emits the frozen `Expansion`/`Node`/`Edge` types rather than a shadow graph. The remaining architecture problems are that namespace grouping falls back to directory paths, the grouping policy is not bound to a graph, and the integration helper reads source independently of the expansion it enriches.

### Findings

1. **Namespace grouping silently mixes in directory groups**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/ContainmentBuilder.kt:135-144`
     (`groupNode`), asserted by
     `analysis-engine/core/src/test/kotlin/dev/codenb/core/ContainmentBuilderTest.kt:162-198`.
   - Problem: In `NAMESPACE` mode a blank package falls back to the file's
     directory (`namespace.ifBlank { directory.ifBlank { ROOT_KEY } }`). A single
     graph can therefore contain package groups such as `app.order` and
     path-derived groups such as `src/main/kotlin/app`, which is the mixing that
     `docs/neighbour-model.md:17-18` and the ticket note at
     `docs/tasks/007-containment-and-grouping.md:43-44` forbid. The nested-path
     test currently encodes the mixed behavior instead of preventing it. It also
     makes grouping policy depend on an individual file's contents rather than
     on the graph.
   - Suggested fix: In `NAMESPACE` mode map a blank package to one stable
     default-package group (`ROOT_KEY` or a dedicated key) and never derive a
     namespace group from the path; reserve the relative path for `DIRECTORY`
     mode. Update the nested-path test to assert that a package-less nested file
     lands in the default-package group under namespace grouping while directory
     grouping still uses its directory, and add a package-less file fixture.

2. **GroupingKind is not bound to the graph, so one graph can accumulate two group sets**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/ContainmentBuilder.kt:15-19`
     (`apply` takes `groupingKind` per call) and `:121-123` (file id independent
     of grouping); composition point in
     `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:23-28`,
     `:87-140` (`merge`, `mergeMetadata`).
   - Problem: Nothing ties the selected `GroupingKind` to a `GraphId`. The same
     graph can be merged first with namespace containment and later with
     directory containment. Because the file node id does not include the
     grouping kind, the store receives the same file node with two different
     group parents, keeps the incoming parent, and never removes the older
     `CONTAINS` edge. The result is a file connected to two groups and a view
     that mixes grouping policies, violating the per-graph selection and the
     acceptance criterion that every entity has exactly one group.
   - Suggested fix: Make the grouping kind an immutable property of the graph or
     session that supplies expansions, or include it in the graph identity, and
     reject an expansion whose containment policy differs from the graph's
     policy. A store-side validation is one option, but the important part is
     that the choice is fixed before the first containment expansion. Add a test
     that merges namespace and directory expansions under one graph id and
     asserts the incompatible one is rejected rather than added.

3. **The integration helper always parses source and can derive location facts from different text than the expansion**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/ContainmentPipeline.kt:25-41`
     and `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinContainmentProvider.kt:9-21`.
   - Problem: `ContainmentPipeline.locations` unconditionally calls
     `Files.readString` and `KotlinContainmentProvider.location`, which always
     parses `package`, even when the selected grouping is `DIRECTORY`. The ticket
     states that directory grouping needs no source parsing. More importantly,
     the helper re-reads disk after `NeighbourExpander` used the LSP document
     text, so an open/unsaved document or a virtual document can yield a
     different package, and a changed or deleted file can make containment fail
     where the ticket-005 expansion succeeded. The helper therefore does not
     preserve the expansion's facts; it silently substitutes disk facts. The
     hardcoded Kotlin provider also means another frontend cannot supply its
     path/package rules through its descriptor.
   - Suggested fix: Split location acquisition by grouping mode: `DIRECTORY` gets
     the URI/relative path with no file read, while `NAMESPACE` takes the package
     from the same text/`FileFacts` used to build the expansion (carry the
     location facts on the expansion or expose a provider callback during
     expansion). Keep the language provider behind the frontend descriptor so
     another frontend does not have to edit the shared pipeline. Add tests for
     directory mode not reading source and for namespace extraction using the
     expansion's document text.

### Concur

- Core owns the grouping keys, containment edges, and file/group ids; the
  Kotlin-specific package and relative-path extraction stays in the frontend.
- Core imports only `dev.codenb.api` and JDK collections. There is no LSP4J,
  `java.nio.file`, or other filesystem dependency in core, and
  `FileLocation.normalizePath` performs string normalization only.
- The builder returns the frozen `Expansion`, `Node`, and `Edge` types and feeds
  the existing `GraphStore` through its normal merge path; no parallel graph
  model or second analysis backend is introduced.
- The builder preserves the ticket-005 reference edges, graph id, revision, and
  seed id, adding only parents, containment nodes, containment edges, and file/
  group aggregations.
- Aggregation uses the canonical undirected `EdgeKey`, keeps directed origins
  and counts, deduplicates repeated observations, and suppresses self-edges at
  the entity, file, and group levels.
- File and group ids are graph-scoped, deterministic for the same relative path
  or namespace, and the group id includes the grouping kind, so unrelated modes
  do not collide by id.
- The new source and test files contain no comments.
- No build, test, formatter, or `just` command was run for this review; the
  green handover is assumed.

### Dissent

- Finding 1: `NAMESPACE` grouping must not fall back to path-derived groups; a
  graph uses one grouping policy.
- Finding 2: The grouping policy must be fixed per graph, and a conflicting
  containment expansion must not leave a file with multiple group parents in the
  store.
- Finding 3: The enrichment helper must not require source parsing for directory
  grouping or reconstruct namespace facts from a different source than the one
  used by the expansion.

## Round 2

**Verdict:** Approved.

All three Round 1 findings are addressed. `NAMESPACE` grouping now uses only the
supplied namespace and maps a blank package to the root group, `GroupingPolicy`
binds one grouping kind per graph and the builder rejects a conflicting kind, and
the frontend pipeline obtains namespace text from `ExpansionWithFiles` while
directory grouping performs no source read or provider call. The changes keep
the core/frontend boundary intact and introduce no new architecture problems.

### Finding disposition

1. **Namespace fallback — Concur (addressed).** `ContainmentBuilder.groupNode`
   now uses `namespace.ifBlank { ROOT_KEY }` for `NAMESPACE` and derives a group
   from the relative directory only for `DIRECTORY`
   (`ContainmentBuilder.kt:144-150`). The nested-root test and the new
   root-package-in-different-directories test assert one shared root namespace
   group, while directory grouping still uses per-directory groups.
2. **Per-graph grouping — Concur (addressed).** `GroupingPolicy` binds a
   `GraphId` to one `GroupingKind` and rejects a different kind for the same
   graph (`GroupingPolicy.kt:12-27`). Both `ContainmentBuilder.apply` and
   `ContainmentPipeline.apply` bind before building, and tests cover repeated
   binding, independent graphs, and rejecting a per-call mismatch.
3. **Source acquisition — Concur (addressed).** `ContainmentPipeline.locations`
   calls the namespace provider only in `NAMESPACE` mode and only with text from
   `ExpansionWithFiles.fileTexts`; `DIRECTORY` uses the relative path alone and
   works with an empty text map (`ContainmentPipeline.kt:52-80`). Tests assert
   that directory grouping neither reads texts nor calls the provider and that
   namespace grouping uses the supplied document text rather than disk. The
   provider is now a `NamespaceProvider` interface, and
   `KotlinContainmentProvider` is just one implementation.

### New findings

None.

### Concur

- `ExpansionWithFiles` remains a frontend-only wrapper; core still sees only the
  frozen `Expansion`. `NeighbourExpander.expand` delegates to
  `expandWithFiles(...).expansion`, and the new test confirms the same expansion
  plus the open-document text.
- Core still has no LSP4J or filesystem imports; path/URI handling and namespace
  extraction stay in frontend.
- The builder still composes with the existing api types and `GraphStore` merge
  path without a parallel graph model, and the per-graph policy now lives in
  core.
- Node/edge kinds and ids are unchanged and remain graph-scoped; file/group
  aggregation, directed origins, deduplication, and self-edge suppression are
  intact.
- The new and modified source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the green
  handover is assumed.

### Dissent

- None.
