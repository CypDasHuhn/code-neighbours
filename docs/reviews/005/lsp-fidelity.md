## Round 1

**Verdict:** Changes requested.

The overall split is sound: references and semantic tokens are the only code facts, incoming locations are mapped to entity spans, outgoing candidates are resolved through definitions, workspace membership filters the targets, and document opening/closing is scoped to the expansion. The findings below concern the target Kotlin LSP's constructor symbol shape, optional token modifiers, one excluded token type, and test coverage required by the ticket.

### Findings

1. **Constructor seeds request references at a position that is not the constructor name**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:45-48`; test coverage in `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/NeighbourExpanderTest.kt`.
   - Problem: The seed symbol is found by full-range equality, then `textDocument/references` is requested at `seedSymbol.selectionRange.start`. The constructor fixtures already used by this project model the target Kotlin LSP shape as `name = <class name>` with `range == selectionRange` (`KotlinEntityDiscoveryTest.kt`, `fullRangeDs`). For a primary or secondary constructor, `selectionRange.start` is therefore the `class`/`constructor` keyword or an annotation start, not the constructor or class-name identifier. References at that position can resolve to the containing class or return nothing, so expanding a constructor entity can silently omit incoming constructor references. All current incoming tests use function seeds, so this shape is not exercised.
   - Suggested fix: Derive a usable reference position for constructor symbols (for example the class-name occurrence in the primary-constructor header, or the `constructor` keyword for a secondary constructor) instead of trusting a full-range `selectionRange`, or handle constructors explicitly as a capability-limited entity. Add target-shaped primary and secondary constructor fixtures with call sites and assert the incoming edges and counts.

2. **Declaration-token filtering depends entirely on optional `declaration`/`definition` modifiers**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:57-76`, `206-215`; test tokens and legend at `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/FakeLspSession.kt:100-117` and `NeighbourExpanderTest.kt:549-567`.
   - Problem: Candidate tokens are filtered by token type and by the presence of `declaration`/`definition` modifiers. Modifier support is server-defined and optional; a server can advertise semantic tokens with a legend that omits `declaration`. In that shape, expanding a class or object seed treats the declaration names of its members, parameters, and type parameters as reference candidates. Their definitions resolve to child entities, producing `REFERENCES` edges such as class → member even though that relationship is containment. The fake legend always includes `declaration`, and every test declaration token is explicitly marked, so the failure case is invisible.
   - Suggested fix: Exclude declaration-name positions independently of modifiers by comparing each candidate token start against the discovered declaration/selection ranges inside the seed span, and keep the modifier check as an additional filter. Add a class-seed test whose legend omits `declaration` and whose member declaration tokens carry no modifier, asserting no class → member reference edge.

3. **All `operator` tokens are discarded, including user-defined operator references**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:64`, `206-215`.
   - Problem: `EXCLUDED_TOKEN_TYPES` contains `"operator"`. In Kotlin an operator invocation (`+`, `in`, `[]`, `==`, and so on) is a reference to an operator function, and `textDocument/definition` can resolve it to an in-workspace entity. Excluding the token type unconditionally drops those outgoing neighbours even when the definition resolves to an entity.
   - Suggested fix: Remove `"operator"` from the blanket exclusion and rely on definition plus in-workspace entity mapping to discard punctuation that does not resolve. Add a test with a user-defined `operator fun plus` and an invocation through `+` that expects an outgoing edge.

4. **The ticket-required overload cases are absent**

   - Location: `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/NeighbourExpanderTest.kt`; ticket scope at `docs/tasks/005-neighbour-expansion.md:24-25`.
   - Problem: The tests cover incoming, outgoing, external, local, private, unresolved, duplicate, and self cases, but there is no overload case. Neither references on one overload nor a definition result containing multiple overload locations is exercised, and the `LocationLink` path in `definitionLocations` is untested. Overloads are where entity ids and edge coalescing are most likely to collapse or multiply incorrectly.
   - Suggested fix: Add two overloads with the same qualified name and distinct fingerprints, make incoming references target one overload, and make a definition result return both overload locations. Assert the intended neighbour set and that each edge count/origin reflects the resolved locations rather than collapsed entity ids. Include a `LocationLink` result and assert its `targetSelectionRange` is used.

5. **Semantic-token decoding tests only exercise same-line tokens**

   - Location: `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/NeighbourExpanderTest.kt:119-127`, `549-567`.
   - Problem: The token builder sorts tokens within single-line entity declarations, so every generated token has `deltaLine == 0`. The `deltaLine != 0` branch, which resets `character` to the absolute `deltaStart`, is never exercised. A decoder that accumulated `deltaStart` across new lines, or otherwise mishandled line transitions, would still pass. The legend used to encode the fake data is also the same object used to decode it, so a reordered or shortened server legend is not represented.
   - Suggested fix: Add a multi-line entity whose reference tokens appear on later lines, with assertions that definitions are requested at the decoded absolute positions and that the expected edges are produced. Use a legend whose token-type and modifier order differs from the client-advertised lists, and include a modifier bit beyond the shortest name list.

### Concur

- Incoming references are requested with `includeDeclaration = false`, and each returned location start is mapped to the smallest discovered entity span in its file; references outside the workspace, in non-file URIs, or not enclosed by an entity are dropped, and self-observations are suppressed.
- The semantic-token decoder follows the LSP 5-integer encoding and uses the server-supplied legend for both token type and modifier bit positions; the delta-line/delta-start calculation is structurally correct, tokens are restricted to the seed span, and ordinary keywords/comments/literals are skipped.
- Definition results include both plain `Location` and `LocationLink`, prefer `targetSelectionRange`, and resolve targets through the target file's document-symbol tree and discovered entities. External, unresolved, local, private, non-file, and self targets are therefore excluded for the ordinary shapes exercised by the tests.
- Workspace membership is derived from `WorkspaceFiles.discover` with the frontend include/exclude patterns, and every location is checked against that set before opening or querying a file.
- Document lifecycle is expansion-local: only files without an open document are opened and recorded, and exactly those are closed in reverse order in `finally`, including failure paths. Pre-opened documents are left open, as covered by `closesOnlyDocumentsOpenedByTheExpansion`.
- Reference observations are deduplicated by directed entity pair, merged into a canonical `REFERENCES` edge, and counted as the sum of origin counts. Edge and neighbour ordering is deterministic, self-edges are suppressed, and the seed is excluded from `neighbours`.
- `LspSession` is a narrow interface over the LSP operations the expander needs, with both production and fake implementations; `LspClient` now implements it, and LSP4J remains confined to the `lsp` and `frontend` modules.
- The new source and test files contain no comments. No build, test, formatter, or `just` command was run for this review.

### Dissent

- Finding 1: constructor seeds must not use a full-range constructor `selectionRange` as the references position.
- Finding 2: declaration tokens must be excluded from outgoing candidates without relying only on optional modifiers.
- Finding 3: user-defined operator references must not be dropped by the blanket `operator` token-type exclusion.
- Finding 4: overload and `LocationLink` cases required by the ticket must be covered.
- Finding 5: semantic-token tests must exercise multi-line delta encoding and a non-identical server legend.

## Round 2

**Verdict:** Changes requested.

Findings 3, 4, and 5 are fully addressed. Finding 1 is addressed for the basic primary and secondary constructor seed shapes, and finding 2 is addressed for declarations that are discovered entities. Two constructor/class disambiguation shapes remain, and the declaration-position filter still misses non-entity declarations inside child entities.

### Finding disposition

1. **Constructor seed reference position — Concur (addressed for the basic shapes); residual new findings 1 and 2.** `KotlinEntityDiscovery` now records a `referencePosition`, primary constructors use the containing class name, secondary constructors use the `constructor` keyword, and `NeighbourExpander` looks the seed up by node id and requests references at that position (`KotlinEntityDiscovery.kt:132-140`, `209-225`; `NeighbourExpander.kt:45-49`). The bodiless primary and secondary constructor tests exercise both positions.
2. **Declaration-token exclusion independent of modifiers — Concur (addressed for entity declarations); residual new finding 3.** `declarationPositionsInside` filters declaration names even when the legend has no `declaration` modifier, and `ignoresMemberDeclarationTokensWithoutDeclarationModifiers` covers a member function and member property.
3. **Operator tokens — Concur (addressed).** `operator` is no longer in `EXCLUDED_TOKEN_TYPES`, and `expandsUserDefinedOperatorReferences` resolves a `+` token to the operator entity.
4. **Overload and `LocationLink` tests — Concur (addressed).** Incoming references on one overload, multiple definition locations, `LocationLink` target selection, and overload identity are covered. The remaining class-link ambiguity is new finding 1.
5. **Multi-line deltas and distinct legend — Concur (addressed).** `semanticTokens` now encodes with the supplied legend, `decodesMultiLineTokensWithAServerSpecificLegend` uses later-line tokens with a reordered legend and a fourth modifier bit, and `ignoresTokensStraddlingTheSeedSpan` covers token-length filtering.

### New findings

1. **A `LocationLink` to a class resolves to the primary constructor**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:200-224`, `354-365`; tests at `NeighbourExpanderTest.kt:567-619`.
   - Problem: The `constructorSelection` branch returns a constructor when either its `selectionSpan` equals the link's selection span or its `referencePosition` equals the selection start. A primary constructor's `referencePosition` is the containing class name. A normal `LocationLink` whose target is the class and whose `targetSelectionRange` is the class-name identifier therefore matches the constructor's `referencePosition` and returns the constructor before the non-constructor preference runs. With both a class and primary constructor entity present, a class reference becomes a constructor neighbour. The added `LocationLink` test only targets a constructor, so the class-link shape is untested.
   - Suggested fix: Resolve the link's selection span against all entities first, preferring an exact non-constructor `selectionSpan`/`referencePosition` match, and only fall back to a constructor when the target range is uniquely constructor-shaped (for example the constructor parameter list or keyword). Do not treat `constructor.referencePosition == class name` as proof that the link targets the constructor. Add a `LocationLink` to the class with `targetSelectionRange` at the class name and assert the class entity, not the constructor, is returned.

2. **A primary constructor reference position can be found inside a leading annotation or KDoc**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:209-225`.
   - Problem: For a primary constructor, `referencePosition` searches `source.indexOf(parentSymbol.name, parentStart)`, where `parentStart` is the start of the class declaration range. The target Kotlin LSP includes leading annotations and KDoc in that range, and the search uses raw source rather than masked text. A class annotation such as `@Named("Widget")` or a leading `/** Widget ... */` comment makes the first occurrence of the class name land in the annotation/comment, so references are requested inside a string or comment rather than at the class-name identifier. The new primary-constructor test has no leading annotation or KDoc.
   - Suggested fix: Find the parent declaration keyword with the existing masked scanner and search for the class name after that keyword, or use a real class-name selection span when present. Add a primary-constructor fixture with a leading annotation containing the class name and a KDoc mentioning it, and assert the references request is made at the class-name identifier.

3. **Declaration positions inside a child entity do not cover non-entity declarations**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:67-72`, `227-241`; test at `NeighbourExpanderTest.kt:621-684`.
   - Problem: `declarationPositionsInside` collects only discovered entity nodes. Parameter, local-variable, and type-parameter declarations inside a member entity are not entities and are therefore absent from the set. When a class seed is expanded with a legend that omits `declaration`, a child member's declaration token is filtered, but its parameter and local declaration tokens pass. Their definitions resolve to the non-entity location; `resolve` then picks the smallest enclosing entity, which is the child member, producing a false class → member `REFERENCES` edge. The existing modifier-free test only includes member-function and member-property declaration tokens.
   - Suggested fix: Derive declaration positions from the full document-symbol tree within the seed span, including symbols that are not entities, or reject a resolved target whose own declaration is not the entity being returned. Add a class-seed test with a member function parameter and local variable, no declaration modifiers, and assert no class → member edge.

### Concur

- The revised seed lookup by node id, primary class-name position, secondary `constructor` keyword position, and constructor/class span handling are correct for the tested primary and secondary constructor seed shapes.
- Operator references, overload handling, `LocationLink` target-selection use, and multi-line legend decoding are now covered and behave as intended.
- Workspace membership, non-file and external filtering, self-edge suppression, directed origin counts, deterministic edge/neighbour ordering, and expansion-scoped document close behavior remain sound.
- `LspSession` is still a clean LSP-only boundary, LSP4J remains confined to `lsp`/`frontend`, and the new source and tests contain no comments. No build, test, formatter, or `just` command was run for this review.

### Dissent

- New finding 1: a `LocationLink` to a class must not be resolved to the primary constructor merely because they share the class-name reference position.
- New finding 2: a primary constructor reference position must not be found in a leading annotation or KDoc that precedes the class keyword.
- New finding 3: declaration-position filtering must include non-entity declarations inside child entities so parameter/local declaration tokens cannot create false reference edges.

## Round 3

**Verdict:** Changes requested.

The three Round 2 findings are addressed for the cases they name. One new regression remains in plain `Location` resolution: full-declaration definition ranges are now rejected or resolved to a coincident constructor. The non-entity declaration path is also missing a direct type-parameter fixture.

### Finding disposition

1. **Class-target `LocationLink` — Concur (addressed).** `resolveOutgoing` now tries non-constructor entities first, matching either the selection or reference position, and only then considers a constructor shape. `resolvesClassLocationLinkToClassNotItsCoincidentConstructor` links to the class name and asserts the class, not the constructor.
2. **Constructor link shape — Concur (addressed).** `hasConstructorShapeAt` requires the link selection to start at the constructor keyword or inside the constructor parameter span, and the constructor-link test now uses the parameter list rather than the class name.
3. **Primary constructor reference position — Concur (addressed).** `constructorCandidate` finds the parent keyword on masked text and searches for the class name after `keyword.end`; `findsPrimaryConstructorReferencePositionAfterAnnotationAndKdoc` covers both a KDoc and an annotation string containing the class name.
4. **Non-entity parameter/local/type-parameter filtering — Concur (addressed for parameter/local); new finding 2 covers the missing type-parameter fixture.** `resolveOutgoing` rejects a plain `Location` whose position is not the resolved entity's reference or selection position, and `ignoresNonEntityParameterAndLocalDefinitionTargetsInClassSeed` covers a member parameter and local variable. Type parameters follow the same rejection path but are not directly fixtured.
5. **New tests — Concur.** Class links, constructor parameter-list links, annotated/KDoc primary constructors, and modifier-free parameter/local tokens are all covered; the remaining gaps are the two findings below.

### New findings

1. **Plain `Location` definitions with full-declaration ranges are rejected or resolved to the primary constructor**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:191-229`; tests at `NeighbourExpanderTest.kt:1187-1252`, `1309-1362`.
   - Problem: After `resolveBySpan`, a plain `Location` is accepted only when its start equals the entity's `referencePosition` or the start of its `selectionSpan`. An LSP `Location` may identify the target by its full declaration range, starting at an annotation, the `class` keyword, or the `fun` keyword rather than at the name. For a function target whose range starts at `fun`, the entity is found and then dropped by the guard, losing a valid outgoing edge. For a class with a coincident primary constructor, a class `Location` starting at the shared declaration start is resolved by `resolveBySpan` to the constructor and accepted because the constructor's selection span starts at that position, so a class reference becomes a constructor neighbour. All definition fixtures use name-selection ranges, so neither shape is covered.
   - Suggested fix: Resolve plain `Location`s through the document-symbol tree rather than exact reference/selection equality: find the innermost declaration containing the location start, accept it when that declaration is the entity (regardless of whether the range started at the name, keyword, or annotation), and reject it when the innermost declaration is a non-entity. Add plain-`Location` tests for a class and a function whose range starts at the declaration keyword or an annotation, asserting the class/function entity and never the constructor.

2. **No direct type-parameter declaration token test**

   - Location: `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/NeighbourExpanderTest.kt:1426-1490`; requirement at `docs/tasks/005-neighbour-expansion.md:24-25` and the Round 3 verification list.
   - Problem: The modifier-free declaration-token fixture covers a parameter and a local variable but not a type-parameter declaration. The current rejection path should handle `T` in `fun <T> member(value: T)` identically, but nothing asserts that a type-parameter definition does not become a class → member edge.
   - Suggested fix: Extend the modifier-free class-seed fixture with a member type parameter, a type-parameter declaration token, and a definition back to it; assert no neighbour or edge is produced.

### Concur

- Class-target links, constructor keyword/parameter-list links, annotated/KDoc primary constructor positions, operator references, overloads, multi-line token deltas, and distinct legends are all implemented and exercised as described in Round 2.
- Workspace membership, non-file and external filtering, self-edge suppression, origin counts, deterministic ordering, and expansion-scoped document close behavior remain sound.
- `LspSession` remains a clean LSP-only boundary, LSP4J stays confined to `lsp`/`frontend`, and the new source and tests contain no comments. No build, test, formatter, or `just` command was run for this review.

### Dissent

- New finding 1: plain `Location` resolution must not depend on the location starting exactly at the entity name, and a full-range class location must not become its coincident primary constructor.
- New finding 2: the modifier-free non-entity declaration fixture should include a type-parameter token, not only a parameter and local variable.

## Round 4

**Verdict:** Approved.

Both Round 3 findings are addressed, and the new resolution paths match the LSP shapes they target. No new LSP-shape problems were found.

### Finding disposition

1. **Plain `Location` full-declaration ranges — Concur (addressed).** `resolveOutgoing` now resolves a location range against entity selection/reference positions and chooses the innermost entity containing the range start. `resolvesPlainFunctionLocationStartingAtFunctionKeyword` uses a `Location` whose range starts at `fun`, and `resolvesPlainClassLocationStartingAtClassKeywordBeforeConstructor` uses the full class declaration range. Both produce the expected entity edge.
2. **Full-range class location versus primary constructor — Concur (addressed).** Non-constructor entities are considered before constructors, and the full-range class fixture with a coincident primary constructor returns the class and excludes the constructor.
3. **Parameter/local/type-parameter `Location`s — Concur (addressed).** The plain-location path cannot match an entity when the location range only covers a non-entity parameter, local, or type-parameter name. `ignoresNonEntityParameterLocalAndTypeParameterDefinitionTargetsInClassSeed` now covers all three shapes and asserts no neighbours or edges.
4. **Constructor `LocationLink` shape — Concur (addressed).** `matchesConstructorLink` accepts only the exact constructor keyword position, the exact constructor selection span, or the exact parameter-list span. `ignoresLocationLinkToAnIndividualConstructorParameter` covers the negative case, while `resolvesLocationLinkDefinitionToConstructor` keeps the exact parameter-list positive case.
5. **New tests — Concur.** The added fixtures cover function-keyword ranges, class-keyword ranges before a coincident constructor, type-parameter declarations, and individual-constructor-parameter links.

### Concur

- Incoming reference mapping, workspace and non-file filtering, local/private target rejection, self-edge suppression, origin counts, deterministic ordering, and expansion-scoped document lifecycle remain sound.
- Semantic-token decoding, operator references, overload handling, multi-line deltas, and server-specific legends remain correct and covered.
- `LspSession` stays a clean LSP-only boundary, LSP4J remains confined to `lsp`/`frontend`, and the new source and tests contain no comments. No build, test, formatter, or `just` command was run for this review.

### New findings

None.

### Dissent

None.
