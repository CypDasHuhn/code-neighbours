## Round 1

**Verdict:** Changes requested.

The implementation has the right overall shape: entity nodes get one file parent and one group parent, file and group reference edges are derived from directed entity observations and canonicalized by the frozen edge key, self-edges are suppressed at file and group level, counts and origins stay consistent, and ordering is deterministic. The remaining correctness problems are a blank-namespace fallback that mixes directory groups into namespace grouping and makes the empty namespace unstable, directory grouping that still reads and parses source, and a grouping mode that is selected per call instead of being enforced per graph.

### Findings

1. **Blank namespace falls back to the directory, mixing grouping modes and breaking namespace stability**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/ContainmentBuilder.kt:135-149`; test `analysis-engine/core/src/test/kotlin/dev/codenb/core/ContainmentBuilderTest.kt:163-198`.
   - Problem: In `NAMESPACE` mode, `namespace.ifBlank { directory.ifBlank { ROOT_KEY } }` turns a blank package into the file's directory. Two root-package files in different nested directories therefore get different group ids even though they share the same empty namespace, violating the acceptance criterion that group ids are stable for the same namespace. It also creates directory-derived groups inside a namespace graph, contrary to the model's rule that a graph is either directory-based or namespace-based, not both. The group id is still prefixed with `GROUP/NAMESPACE`, so the mode id prefix does not prevent the mixing. `nestedDirectoriesAndRootFilesUseStableRootAndDirectoryGroups` currently asserts the incorrect fallback.
   - Suggested fix: In `NAMESPACE` mode use `ROOT_KEY` whenever the namespace is blank; use the relative directory only in `DIRECTORY` mode. Update the nested-path test so the namespace case expects `graph/GROUP/NAMESPACE/.`, and add a test with two root-package files in different directories that expects a single root namespace group.

2. **Directory grouping still reads and parses every file**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/ContainmentPipeline.kt:25-41`; `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinContainmentProvider.kt:9-21`.
   - Problem: `locations` unconditionally calls `Files.readString` and `KotlinSourceText.packageName` for every entity file, including when the requested grouping kind is `DIRECTORY`. The ticket requires directory grouping to need no source parsing. As written, directory grouping fails if a source file is missing or unreadable and is unnecessarily coupled to Kotlin package parsing, even though the relative path is sufficient for the group key.
   - Suggested fix: Pass the grouping kind into `locations`; in `DIRECTORY` mode derive only the relative path and leave `namespace` blank; read and parse source only in `NAMESPACE` mode. Add a test that directory grouping works when the source file is absent or unreadable, or use an injected source reader and assert it is not invoked for directory grouping.

3. **Grouping mode is selected per call rather than enforced per graph**

   - Location: `ContainmentBuilder.apply` at `analysis-engine/core/src/main/kotlin/dev/codenb/core/ContainmentBuilder.kt:14-19`; `ContainmentPipeline.apply` at `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/ContainmentPipeline.kt:12-23`; rule in `docs/tasks/007-containment-and-grouping.md:41-44` and `docs/neighbour-model.md:17-18`.
   - Problem: Both entry points accept a grouping kind per invocation and neither carries nor validates a graph-level choice. Calling the helper with `NAMESPACE` for one expansion and `DIRECTORY` for another expansion of the same `GraphId`, then merging both into `GraphStore`, produces a graph containing both namespace and directory groups, which the ticket explicitly forbids. The mode-specific id prefix avoids key collisions but does not prevent the mixed view.
   - Suggested fix: Bind the grouping policy to the graph, for example a graph-scoped policy object or `ContainmentBuilder` instance holding one kind, and reject a conflicting kind for an existing graph. Add a test that a second expansion for the same graph with a different kind is rejected or reuses the bound kind.

### Concur

- Every entity in the output is assigned exactly one file parent, every file exactly one group parent, and one `CONTAINS` edge is emitted for each entity-to-file and file-to-group relationship; the invariant test walks the parents and containment edges for all entities.
- Containment endpoints are canonicalized through `Edge.of` as required by the frozen `Edge` contract; logical containment direction remains observable through `Node.parent` and the tests key on canonical edge identities.
- File references are aggregated from the directed origins of incoming `REFERENCES` edges, group references are derived from those file observations, same-file and same-group observations are skipped, and one canonical edge collects both directions without double counting.
- Counts equal the sum of the retained origins for reference edges, containment counts stay at one with empty origins, origins are deduplicated within each edge, and edge, origin, and neighbour ordering is deterministic.
- Missing locations, non-file URIs, out-of-root paths, absolute paths, and redundant path segments are rejected or normalized explicitly, and the provider tests cover the non-file, out-of-root, and normalization cases.
- File ids are stable from the normalized relative path, and non-blank namespace and directory group ids are stable and mode-distinct; reversed location input produces the same result.
- The frontend/core boundary is respected: `ContainmentBuilder` and `FileLocation` perform no IO and no language parsing, while the frontend provider and pipeline read files and extract Kotlin packages; `ContainmentPipeline` is a tested integration helper.
- The tests cover cross-file references, same-file references, same-group references in both modes, namespace versus directory grouping, nested paths, root files, missing locations, origin/count consistency, and deterministic output.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the handover is assumed green.

### Dissent

- Finding 1: a blank namespace must map to the root namespace group, not to a directory-derived group, and two files with the same empty namespace must share one group id.
- Finding 2: directory grouping must not read or parse source, and must not fail when source is unavailable.
- Finding 3: the grouping mode must be bound to and enforced per graph so one graph cannot contain both namespace and directory groups.

## Round 2

**Verdict:** Approved.

The three Round 1 findings are addressed: blank namespaces now map to the stable root namespace group, directory grouping no longer reads or parses source and namespace grouping uses the supplied document texts, and a `GroupingPolicy` binds one grouping kind per graph and rejects a conflicting call. The new `ExpansionWithFiles` path carries the exact loaded file texts, including an already-open document's text, and the focused tests exercise the root namespace, policy conflict, directory-without-source, supplied-text, missing-text, and open-document cases. No new correctness problems were found.

### New findings

None.

### Concur

- Blank namespace handling at `ContainmentBuilder.kt:146-150` now returns `ROOT_KEY` for `NAMESPACE` regardless of directory; `nestedDirectoriesAndRootFilesUseStableRootAndDirectoryGroups` and `rootPackageFilesInDifferentDirectoriesShareOneNamespaceGroup` verify that root-package files in different directories share one stable namespace group while directory grouping still uses the relative directory.
- `GroupingPolicy.bind` at `GroupingPolicy.kt:12-27` is graph-scoped and synchronized, and both `ContainmentBuilder.apply` and `ContainmentPipeline.apply` bind through it before producing groups; `groupingPolicyBindsOneKindPerGraph`, `groupingPolicyKeepsDifferentGraphsIndependent`, and `containmentBuilderRejectsAPerCallKindMismatchForTheSameGraph` cover the acceptance behavior.
- `ContainmentPipeline.locations` at `ContainmentPipeline.kt:52-80` computes only the workspace path and relative path for `DIRECTORY` grouping and does not read or parse source; `directoryGroupingNeedsNoTextsAndDoesNotCallTheNamespaceProvider` supplies no texts, uses nonexistent source paths, and a provider that throws, and still succeeds.
- For `NAMESPACE` grouping the pipeline uses `ExpansionWithFiles.fileTexts` through the injected `NamespaceProvider`; `namespaceGroupingUsesProvidedTextInsteadOfDisk` proves the provided text wins over differing disk text, and `namespaceGroupingRejectsMissingDocumentText` covers a missing text.
- `ExpansionWithFiles` at `ExpansionWithFiles.kt:5-8`, populated at `NeighbourExpander.kt:40-48` and `NeighbourExpander.kt:142-146`, carries the exact `FileFacts.text` for every loaded file, and `NeighbourExpanderFilesTest` verifies that an already-open document's text, not the disk text, is carried and that `expand` delegates to `expandWithFiles`.
- The earlier containment, aggregation, self-edge, count/origin, determinism, URI/path, and frontend/core boundary behaviors remain intact.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the handover is assumed green.

### Dissent

- None.
