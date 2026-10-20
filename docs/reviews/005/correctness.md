## Round 1

**Verdict:** Changes requested.

The expander has the right overall shape: incoming locations are mapped to the smallest containing entity, outgoing candidates go through definitions and target entities, workspace and visibility filtering are applied, and observations are merged into canonical edges with deterministic ordering. The remaining correctness problems are ambiguous range-keyed entity resolution, silent handling of a null semantic-token result, a seed-only revision, start-only token containment, and missing required boundary tests.

### Findings

1. **Range-keyed entity resolution cannot distinguish entities with coincident spans**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:45-47`, `179-191`, `264-282`.
   - Problem: `findSeedSymbol` selects the first symbol whose full `range` equals the seed span, and `declaredEntityContaining` selects the first entity whose span equals the innermost symbol's range. When a class and its primary constructor share a span, the wrong entity is chosen. The project's own fixtures allow this: for a bodiless declaration such as `class InternalPrimary internal constructor(val value: Int)`, the class range ends where the line ends and the constructor range ends immediately after `)`, which is the same offset. Because the class symbol is visited first, expanding a constructor seed finds the class symbol and queries references on the class rather than the constructor; a definition resolving to the constructor maps back to the class entity. `smallestEntityContaining` has the same tie for references inside constructor parameters. This creates or omits the wrong entity edge.
   - Suggested fix: Resolve symbols through the discovered entity identity rather than span equality alone, for example by retaining a node-to-symbol mapping during discovery, or by disambiguating equal spans using the symbol kind, selection range, and qualified name. Add a test with a bodiless primary constructor where the class and constructor spans coincide, for both a constructor seed and a definition resolving to that constructor.

2. **A null semantic-token result silently returns a partial expansion**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:57-77`.
   - Problem: When `semanticTokensFull` returns null, the `if (tokens != null)` branch is skipped and the expansion returns successfully with only incoming neighbours. A missing capability is only detected when the session throws; a null result from a capable session is indistinguishable from unavailable tokens and produces exactly the silently partial graph the ticket forbids. There is no test where null tokens coexist with incoming neighbours.
   - Suggested fix: Treat a null token result for a non-empty seed entity as an explicit unavailable/unsupported-capability failure, or expose and check token support before returning. Add a test with incoming references and a null token result that asserts the error rather than a partial expansion.

3. **The revision covers only the seed file, not the files that contributed the expansion**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:78`, `96-98`, `171-177`.
   - Problem: `Expansion.revision` is `sha256` of the seed file's text, although the returned neighbours and edges can depend on source files (incoming references) and target files (definitions) that are not the seed file. A consumer caching this expansion by revision cannot detect a change in one of those contributing files, which contradicts the architecture's cache key of content hashes of the files that contributed a result and task 006's invalidation requirement. Only the single-file case is tested, where this cannot be observed.
   - Suggested fix: Compute a deterministic combined revision from the sorted content hashes of every `FileFacts` that contributed to the expansion, or expose the contributing hashes alongside the expansion. Add a test where only the other file changes and assert the revision changes.

4. **Token containment is checked at the start only**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:62-65`, `200-204`, `284-303`.
   - Problem: `DecodedToken` discards the semantic-token length, and the candidate filter uses only `seedSpan.contains(it.start)`. A token that begins inside the entity span but extends beyond its end is therefore treated as an in-entity reference. The implementation cannot enforce the stated rule that candidate tokens are inside the entity span, only that they start inside it.
   - Suggested fix: Retain the token length and test both an end-exclusive start and end against the seed span. Add a test where a token straddles the entity boundary and assert that it is not used as an outgoing candidate.

5. **Required overload coverage and several error/boundary paths are untested**

   - Location: `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/NeighbourExpanderTest.kt`; ticket scope at `docs/tasks/005-neighbour-expansion.md:24-25`.
   - Problem: The fake-LSP tests cover incoming, outgoing, external, local, private, unresolved, duplicate, self, same-file, and multi-file cases, but the ticket explicitly requires overload coverage and there is none. The `LocationLink` branch in `definitionLocations` is also untested, as are a null token result, a missing file, an incoming non-file or out-of-workspace target, a reference at a file boundary, and the constructor span collision from Finding 1. These are the branches most likely to produce wrong edges or a partial expansion.
   - Suggested fix: Add overload tests where references target one overload and where a definition returns multiple overload locations, a `LocationLink` result, a null-token case, a missing-file error case, incoming non-file/out-of-workspace locations, and a reference on the first or last line of a file.

### Concur

- URI handling normalizes absolute paths, treats non-file schemes as absent, and checks every location against the discovered workspace set before opening or querying it.
- Document ownership is expansion-local: pre-opened documents are reused and left open, newly opened documents are recorded and closed in reverse order in `finally`, and the unsupported-capability failure path leaves no document open.
- Document facts are cached per normalized path, and the open-document text is preferred over disk text so positions, symbols, entities, and the revision stay consistent with what the server sees.
- Semantic-token delta-line and delta-start decoding follows the LSP 5-integer encoding and uses the server legend; the start-position math is correct apart from the missing length in Finding 4.
- `SourceSpan.contains` implements end-exclusive containment correctly for positions, and the smallest-span selection is correct for ordinary nested entities with distinct spans.
- Observations are deduplicated by directed entity pair, incoming and outgoing observations merge into one canonical `REFERENCES` edge, origin counts sum to the edge count, and origins remain directed and distinct.
- Self-references are suppressed in both directions, the seed is excluded from `neighbours`, neighbours are deduplicated, and edge/origin/neighbour ordering is deterministic.
- The unsupported-capability exception propagates without caching or returning a partial expansion, and the new source and test files contain no comments.
- No build, test, formatter, or `just` command was run for this review; the handover is assumed green.

### Dissent

- Finding 1: entity lookup must not select a class for a constructor merely because their spans coincide.
- Finding 2: a null semantic-token result must not be returned as a successful incoming-only expansion.
- Finding 3: the expansion revision must cover every file that contributed neighbours or definitions.
- Finding 4: candidate-token containment must include the token end, not just its start.
- Finding 5: the ticket-required overload case and the listed error/boundary paths must be tested.

## Round 2

**Verdict:** Changes requested.

The Round 1 fixes are largely present: seed lookup is now by node id, constructor reference positions and coincident spans are modelled explicitly, null token results fail, token lengths are checked at both ends, revisions include observed contributor files, and the required overload/error/boundary tests were added. Two correctness problems remain: a `LocationLink` that targets a class is still resolved to its coincident primary constructor, and the revision still omits files that were consulted but yielded no entity.

### Finding disposition

1. **Range-keyed entity resolution — Concur with a residual `LocationLink` problem (New Finding 1).** `expand` now finds the seed entity by `node.id`, and `DiscoveredEntity` carries the symbol, full span, selection span, kind, and reference position. Primary and secondary constructor seed tests pass. The constructor branch in `resolve` still misclassifies class `LocationLink` targets when a primary constructor shares the class span.
2. **Null semantic tokens — Concur.** A null result for a non-empty seed span now raises `LspUnsupportedCapabilityException`, and `failsWhenSemanticTokensAreNullDespiteIncomingReferences` covers the partial-expansion case.
3. **Revision — Partially addressed (New Finding 2).** Observed source and target files are now contributed and the revision is deterministic over their sorted hashes. Files consulted to decide that a location has no entity are still omitted.
4. **Token containment — Concur.** `DecodedToken` retains the length, `containsToken` checks the end-exclusive start and end against the seed span, and `ignoresTokensStraddlingTheSeedSpan` exercises a token crossing the boundary.
5. **Required tests — Concur for the requested cases.** Overloads, multiple overload locations, `LocationLink`, null tokens, both missing-file shapes, non-file/out-of-workspace locations, first/last-line references, coincident class/constructor spans, and primary/secondary constructor seeds are covered. The class-target `LocationLink` case from New Finding 1 is still missing.

### New findings

1. **A `LocationLink` targeting a class is resolved to its coincident primary constructor**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:200-210`, `354-362`.
   - Problem: `definitionTargets` passes `targetSelectionRange` to `resolve` as `constructorSelection` for every `LocationLink`. The constructor branch then matches a constructor whose `referencePosition` equals the selection start. A primary constructor's `referencePosition` is the class name, and the fixtures also make the class and constructor selection spans identical when their full spans coincide. A link whose selection targets the class therefore matches the constructor before general resolution and produces a `REFERENCES` edge to the constructor instead of the class. The plain-`Location` class case is handled by the non-constructor reference-position check, but the `LocationLink` path never reaches it. Existing link tests target only a member and a constructor.
   - Suggested fix: For link targets, match a constructor only when the link selection exactly identifies the constructor (or carry and compare the link's `targetRange` and symbol hierarchy), and let a class selection fall through to the non-constructor resolution. Add a `LocationLink` test targeting a class whose primary constructor shares its span and assert the class edge, not the constructor edge.

2. **The revision still omits consulted files that produced no entity**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:125-143`, `173-189`.
   - Problem: `contributing` is populated only when `observe` adds an edge (plus the seed file). A file loaded for a reference location or definition target that resolves to no entity is not included. Such a file still affected the result: changing it could add or remove an entity at that position and therefore add or remove an edge, but the revision would not change and a cache keyed on the expansion revision would serve a stale result. The existing external/unresolved tests and `ignoresDefinitionToAMissingWorkspaceFile` exercise consulted-without-edge files, but the revision test only edits a file that already contributes a caller entity.
   - Suggested fix: Include every loaded/consulted `FileFacts` in the combined revision, for example from `files.values` or a separate consulted set, rather than only files that produced observations. Add a test where a second file is consulted but yields no edge, edit that file, and assert the revision changes.

### Concur

- Round 1 Findings 2, 4, and 5 are addressed as described above; Finding 1 is addressed for seed lookup and plain locations but not for class-target `LocationLink` results.
- Seed identity is resolved through the discovered node id rather than an exact range match, and constructor reference positions are computed for primary and secondary constructor shapes.
- Null token results no longer return an incoming-only expansion, and all opened documents are still closed on the failure path.
- Token decoding now retains the length and uses end-exclusive containment; multi-line delta decoding and a server-specific legend are covered.
- Observed contributor files are deduplicated by path and combined deterministically into the revision.
- Incoming and outgoing observations, counts, origins, self-suppression, neighbour deduplication, and deterministic ordering remain correct.
- The new tests cover the ticket-required overload case and the previously missing error and boundary paths, and the authored source and tests still contain no comments.
- No build, test, formatter, or `just` command was run for this review; the handover is assumed green.

### Dissent

- New Finding 1: a class-target `LocationLink` must not resolve to the primary constructor merely because their spans or name positions coincide.
- New Finding 2: the revision must include files that were loaded and consulted even when they yielded no entity, or a later change in such a file can leave a stale cached expansion.
- Round 1 Findings 2, 4, and 5 are concurred as addressed; Findings 1 and 3 are concurred only for the cases not covered by the new findings.

## Round 3

**Verdict:** Changes requested.

The Round 2 findings are addressed: class-target `LocationLink` results resolve to the class before its coincident constructor, the revision now covers every loaded/consulted file, and the new class-link, annotation/KDoc, non-entity-target, and consulted-file revision tests are present. One new correctness problem remains in the `LocationLink` path: a link selection inside a constructor parameter list is treated as the constructor itself, so a non-entity parameter target can still create a false edge. The new non-entity test covers plain `Location` results only.

### Finding disposition

1. **Class-target `LocationLink` — Concur.** `resolveOutgoing` checks a non-constructor entity's selection/reference position before the constructor shape, and `resolvesClassLocationLinkToClassNotItsCoincidentConstructor` verifies the class result and the absence of the constructor edge.
2. **Outgoing target must hit the selection/reference position — Concur for plain `Location` results; the `LocationLink` path still bypasses this guard (New Finding 1).** The final `takeIf` rejects parameters and locals for plain locations, and `ignoresNonEntityParameterAndLocalDefinitionTargetsInClassSeed` covers that shape. `resolveOutgoing` returns early from its link branches before that check.
3. **Revision includes every consulted file — Concur.** `combinedRevision` now hashes `files.values`, which includes files loaded only to decide that a location has no entity, and `revisionChangesWhenAConsultedFileYieldsNoEdge` verifies the consulted-file case.
4. **New tests — Concur for class link, constructor annotation/KDoc, plain non-entity targets, and consulted-file revisions.** The missing case is a `LocationLink` whose target selection is a constructor parameter rather than the full parameter list.

### New findings

1. **A `LocationLink` targeting a constructor parameter is resolved to the constructor**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:191-212`, `254-261`; test gap in `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/NeighbourExpanderTest.kt`.
   - Problem: `hasConstructorShapeAt` accepts the constructor whenever the link selection start is merely contained in `parameterSpan`. A constructor parameter is not an entity, but a definition result such as a named argument reference whose `LocationLink.targetSelectionRange` is the parameter name (for example `value` in `class Widget(val value: Int)`) passes this test and returns the constructor. The `LocationLink` branch returns before the final `takeIf` check that rejects parameter/local positions for plain `Location` results, so the expansion creates a false seed-to-constructor edge. The existing non-entity test uses only plain locations, and the constructor link test uses the whole parameter list.
   - Suggested fix: Identify a constructor link only when the selection equals the constructor keyword, the full parameter span, or the constructor's own selection span, rather than any position contained in the parameter list. Apply the same selection/reference guard to link results that plain locations receive. Add a test where a `LocationLink` selects an individual constructor parameter and assert that no edge is created.

### Concur

- Class-target links, including a class whose primary constructor shares its span, now resolve to the class and not the constructor.
- Primary constructor reference positions are found after leading KDoc and annotation text, and secondary constructors use the constructor keyword; both tests pass.
- Plain definition locations now require the resolved entity's selection or reference position, so parameter and local targets in a class seed do not produce edges.
- The revision is deterministic over all loaded file hashes, including files consulted only to discover that no entity applies.
- Null semantic-token handling, token end containment, overload handling, missing/non-file/out-of-workspace references, constructor seeds, and document ownership remain correct for the covered shapes.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the handover is assumed green.

### Dissent

- New Finding 1: a `LocationLink` selection inside a constructor parameter list must not be treated as the constructor, because parameter targets are non-entities and create false reference edges.

## Round 4

**Verdict:** Changes requested.

The Round 3 findings are addressed: plain full-declaration locations are accepted through the target range, parameter/local/type-parameter targets are rejected, full-range class locations choose the class, and constructor links use exact keyword, selection-span, or parameter-list matching, with tests for each. One new regression remains in the plain `Location` path: a full-declaration primary-constructor range resolves to the containing class before the constructor is considered.

### Finding disposition

1. **Plain full-declaration ranges — Concur for functions and classes.** `resolvesPlainFunctionLocationStartingAtFunctionKeyword` and `resolvesPlainClassLocationStartingAtClassKeywordBeforeConstructor` cover the new range-based path; a primary-constructor full range is not covered (New Finding 1).
2. **Parameter/local/type-parameter targets — Concur.** `ignoresNonEntityParameterLocalAndTypeParameterDefinitionTargetsInClassSeed` covers all three plain-location cases, and the final contained-selection/reference filter rejects them.
3. **Full-range class locations — Concur.** The non-constructor candidate list is searched before constructors, so a full class range resolves to the class even when a primary constructor shares its span.
4. **`LocationLink` constructor/parameter behavior — Concur.** `ignoresLocationLinkToAnIndividualConstructorParameter` verifies that a parameter selection is rejected, and `resolvesLocationLinkDefinitionToConstructor` verifies that a full parameter-list selection resolves to the constructor.

### New findings

1. **A full-declaration plain `Location` for a primary constructor resolves to its class**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:208-226`, `229-242`; test gap in `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/NeighbourExpanderTest.kt`.
   - Problem: For a plain `Location` whose range is a primary constructor's full declaration header, the non-constructor candidate filter accepts the containing class because the header contains the class's selection and reference positions. `smallestContaining` then returns that class before the constructor candidates are examined. This happens even when the class has a body and the constructor's `fullSpan` is strictly smaller than the class span, so the target range itself identifies the constructor. Secondary constructors are unaffected because the class name is outside their keyword range, but primary constructors are misattributed. No test exercises `Location(uri, constructor.range)`.
   - Suggested fix: Prefer an entity whose `fullSpan` exactly equals the target range before falling back to contained selection/reference matching, so a class full range picks the class and a constructor header range picks the constructor; keep the class preference only when both spans are identical. Add a test with a class that has a body and a plain full-range `Location` targeting its primary constructor, asserting the constructor edge and no class edge.

### Concur

- Full-declaration plain locations now resolve functions and classes through the target range rather than requiring the location start to equal the selection start.
- Plain parameter, local, and type-parameter targets are rejected because no entity's selection or reference position lies inside their ranges.
- Class-target `LocationLink` results resolve to the class, and individual constructor-parameter selections are rejected while full parameter-list and keyword selections resolve to the constructor.
- Revision, null-token handling, token containment, overload handling, missing/non-file/out-of-workspace references, constructor seeds, and document ownership remain correct for the covered shapes.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the handover is assumed green.

### Dissent

- New Finding 1: a plain full-declaration range that identifies a primary constructor must not resolve to the containing class when their full spans differ.

## Round 5

**Verdict:** Approved.

The Round 4 regression is fixed. `resolveOutgoing` now resolves an exact `fullSpan` match before any containment or selection scoring, so a class-with-body primary constructor header resolves to the constructor while a class full range resolves to the class. When class and constructor spans truly coincide, the exact-match path prefers the non-constructor, which preserves the established class behavior without breaking the constructor link tests.

### Verification

- **Exact `fullSpan` match first — Concur.** `resolveOutgoing` builds the exact-match set from the target range before link/selection matching and returns a non-constructor when one exists, otherwise the constructor (`NeighbourExpander.kt:195-199`). This covers constructor header spans, secondary constructor ranges, functions, classes, and members whose full range is the target.
- **Class-with-body primary constructor — Concur.** `resolvesPlainPrimaryConstructorHeaderLocationBeforeContainingClass` uses a class with a member so the class and constructor spans differ, passes the constructor `range` as a plain `Location`, and asserts the constructor edge with no class edge.
- **Coincident-span preference — Concur.** The exact-match set prefers a non-constructor only when spans are equal, so the coincident bodiless class/constructor case still resolves to the class; existing class-target tests continue to pass in the recorded suite.
- **Earlier behavior — Concur.** Class-target `LocationLink` resolution still checks a non-constructor selection/reference before constructor shape; individual constructor-parameter links are rejected by exact keyword/selection/parameter-span matching; full-declaration function and class locations still resolve; parameter, local, and type-parameter plain locations are still rejected.
- **Tests — Concur.** The recorded suite contains the new primary-constructor-header test and the prior class-link, constructor-link, parameter-rejection, type-parameter, full-declaration, revision, overload, null-token, and boundary tests.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the handover is assumed green.

### New findings

None.

### Concur

- Exact full-span matching resolves constructor header locations correctly while retaining the non-constructor preference for truly coincident spans.
- The class-target `LocationLink`, parameter/local/type-parameter rejection, full-declaration range, revision, null-token, token containment, overload, and document-ownership behaviors remain correct for the covered shapes.
- The new test directly exercises the regression reported in Round 4.

### Dissent

- None.
