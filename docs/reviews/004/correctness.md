## Round 1

**Verdict:** Changes requested.

The discovery/identity split is sound in shape: traversal follows containers,
private/local exclusion works for ordinary modifiers, names are package-qualified,
ids use the required tuple without source coordinates, and duplicate declarations
are disambiguated. The remaining correctness problems are in the fallback
signature scan, the unrestricted visibility prefix, the order-based
missing-signature fallback, package extraction from comment text, a residual
duplicate-id collision, and test coverage of those boundaries.

### Findings

1. **Multiline and default-argument signatures are truncated before their
   distinguishing text**

   - Location:
     `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:148-153`.
   - Problem: When `detail` is blank, the declaration-text fallback stops at the
     first `{`, `=`, `\n`, or `\r`. A multiline parameter list therefore yields a
     fingerprint such as `(`, and any default parameter yields text only up to
     the first `=` inside the parameter list, such as `(a: Int `. Distinct
     overloads such as `fun convert(\n input: Int,\n)` and
     `fun convert(\n input: String,\n)`, or
     `fun render(a: Int = 0, b: String)` and
     `fun render(a: Int = 0, b: Int)`, collapse to the same fingerprint. They are
     then treated as duplicate ids, marked unstable, and given order-dependent
     suffixes even though the declaration text clearly distinguishes them. This
     breaks the requirement to derive the fingerprint from declaration text and
     makes ids unstable across reordering.
   - Suggested fix: Scan the declaration after `selectionRange.end` with a small
     balanced-delimiter scan rather than line/character stops. Allow newlines,
     ignore `=` inside parentheses, brackets, generics, annotations, and default
     values, and stop only at the top-level body `{`/`=` or declaration end. Add
     multiline and default-parameter overload tests.

2. **The visibility prefix treats `private` in annotations or comments as a
   modifier**

   - Location:
     `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:66-69`,
     `241`.
   - Problem: `PRIVATE.containsMatchIn(prefix)` searches all source from
     `range.start` to `selectionRange.start`. That range can contain annotations
     and any comment the server includes in the symbol range. A public declaration
     such as `@Suppress("private") class Visible` or a declaration with an
     annotation argument containing the word `private` is therefore dropped. The
     filter is supposed to exclude only when the text proves private visibility,
     but annotation/string/comment text does not prove it.
   - Suggested fix: Isolate the modifier/declaration header and ignore string
     literals, comments, and annotation arguments, or accept `private` only when
     it appears in modifier position. Add a test with a public declaration whose
     annotation or leading comment contains `private`.

3. **The entirely missing-signature fallback is document-order based instead of
   declaration-derived**

   - Location:
     `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:26-40`;
     see also `docs/neighbour-model.md:54-56` and `docs/architecture.md:127-130`.
   - Problem: When no signature can be recovered, the fingerprint is
     `no-signature#N`, keyed by qualified name and assigned in document order.
     Reordering two same-named declarations with missing signatures, or inserting
     another such declaration before them, changes the ids of declarations whose
     text did not change. The model requires the declaration range/content to be
     folded into the fingerprint when recovery fails. Raw line/column cannot be
     used because ids must never contain them, but the current document ordinal
     is not a stable substitute.
   - Suggested fix: Derive the fallback from the declaration's source text or a
     normalized/hash value of that text rather than from list position, or reserve
     and verify unique ordinals across the whole file. Add a test that moves a
     missing-signature declaration without changing it and asserts its id is
     unchanged.

4. **Package extraction can read text from comments or inline comments as the
   package name**

   - Location:
     `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:158-164`,
     `240`.
   - Problem: `PACKAGE.find(source)` takes the first line beginning with
     `package` anywhere in the file. A block comment before the real package
     directive containing a line such as `package wrong` produces the wrong
     namespace. The capture `[^\s;]+` also includes trailing inline comments when
     there is no whitespace, so `package app/* note */` yields `app/*` instead of
     `app`. Every qualified name and id in the file is then wrong. There is no
     package-specific test for these cases.
   - Suggested fix: Ignore comment and string contents when locating the package
     directive, or restrict extraction to the leading file header and a valid
     package identifier (including backticked identifiers) with an optional
     trailing comment. Add tests for no package, a commented-out package line,
     and a package with an inline comment.

5. **Duplicate-id disambiguation can still emit colliding ids**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/EntityIdentity.kt:23-44`.
   - Problem: The collision check only considers the original ids. Suffixes are
     then appended without checking the whole result set. For descriptors with
     fingerprints `x`, `x`, and `x#0` for the same qualified name and kind, the
     first duplicate becomes `x#0` and collides with the already-unique third
     descriptor. Two overloads end up with the same id even though duplicate
     handling was intended to prevent exactly that, and a graph store that
     deduplicates by id would merge or reject them.
   - Suggested fix: Generate final ids against the complete set, for example by
     incrementing a suffix until the candidate id is unused, or encode the
     ordinal in an unambiguous component rather than appending raw text to a
     fingerprint. Add a test where a duplicate suffix equals another
     declaration's fingerprint.

6. **Tests do not exercise the fallback, visibility, package, or span boundaries
   above**

   - Location: `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/KotlinEntityDiscoveryTest.kt`;
     `analysis-engine/core/src/test/kotlin/dev/codenb/core/EntityIdentityTest.kt`.
   - Problem: The overload test covers only single-line declarations without
     default parameters. The private/string test places the word `private` after
     `selectionRange.start`, so it does not validate annotation/comment prefix
     handling. There is no test for multiline signatures, default-argument
     overloads, a public declaration annotated with private text, no package or
     commented package text, missing-signature reordering, duplicate-suffix
     collisions, private nested declarations or constructors, or span conversion
     across CRLF and non-BMP characters. Several claimed behaviors are therefore
     verified only for the simplest input.
   - Suggested fix: Add direct tests for each boundary described in Findings 1-5,
     plus a removed-package case and CRLF/Unicode span cases. Assert full ids and
     that the ids are unchanged after line shifts and body-only edits.

### Concur

- Container-only recursion excludes function-scoped locals and members of
  non-container declarations; the local, top-level, member, nested, companion,
  and constructor cases in the current tests follow the hierarchy.
- `private` declarations are pruned before recursion, so private containers do
  not leak their public-looking children; `internal` and `protected` declarations
  are retained for ordinary modifier prefixes.
- Qualified names join the package and symbol hierarchy correctly for the tested
  cases, including companion objects, and the empty-package path in `qualify`
  produces an unqualified name.
- Ids include graph, file, qualified name, kind, and fingerprint and contain no
  line or column; repeated discovery of the same symbols is deterministic, and
  the line-shift test confirms spans do not affect ids.
- Normal duplicate ids are marked unstable and receive distinct suffixes, and
  the identity core independently marks duplicates unstable.
- Span conversion passes the LSP range coordinates through unchanged, and the
  fallback offset calculation strips CR/LF and clamps positions to line ends.
- The authored source and tests contain no comments.
- The explicit LSP4J dependency in the frontend build supplies the
  `DocumentSymbol` types used by the new discovery code.

### Dissent

- Finding 1: multiline and default-argument declarations can collapse to one
  fingerprint and lose stable overload identity.
- Finding 2: annotation/comment text can cause a public declaration to be
  excluded, violating the conservative visibility rule.
- Finding 3: missing-signature ids depend on document order rather than a stable
  property of the declaration.
- Finding 4: package extraction can produce the wrong namespace from comment
  text or an inline comment.
- Finding 5: duplicate disambiguation can still return the same id for two
  declarations.
- Finding 6: the tests do not exercise the boundary cases above, so the claimed
  behavior is not fully verified.

## Round 2

**Verdict:** Changes requested.

The Round 1 findings and the lsp-fidelity boundary cases are addressed, including
multiline/default/receiver signatures, annotation/comment/backtick-aware
visibility, declaration-derived fallback, package comments/inline comments/
backticks/BOM/shebang, collision-safe duplicate ids, and constructor shapes. Three
new correctness problems remain in the balanced header scan, init-block
detection, and one legal package-header form.

### Finding disposition

1. **Multiline/default-argument/receiver signatures — Concur with a residual
   scanner problem (New Finding 1).** `headerEnd` now spans newlines, tracks
   nested delimiters, stops at top-level body boundaries, and the signature starts
   at the declaration keyword so receivers, defaults, and typealias targets are
   retained (`KotlinEntityDiscovery.kt:87-103`, `217-231`;
   `KotlinSourceText.kt:105-134`). The default, multiline, receiver, expression
   body, and typealias tests cover the Round 1 cases (`KotlinEntityDiscoveryTest.kt:516-583`).
   The angle-bracket depth still has an expression-operator failure described
   below.
2. **Annotation/comment/string/backtick-aware visibility and keyword scanning —
   Concur.** `mask`/`maskComments` now treat backticked identifiers as opaque, and
   `tokens`, `keywordOffset`, `firstKeyword`, `hasPrivateModifier`, and
   `firstParentheses` preserve backtick tokens as single words
   (`KotlinSourceText.kt:8-103`, `172-250`). `hasPrivateModifier` skips annotations
   and only accepts a plain `private` modifier before the declaration keyword. The
   tests cover annotation/comment false positives, backticked keyword names,
   backticked constructor names, and backticked names containing punctuation
   (`KotlinEntityDiscoveryTest.kt:585-692`).
3. **Declaration-derived missing-signature fallback — Concur.** The fallback is a
   SHA-256 hash of the declaration header text (`unstable-<hex>`), contains no
   line or column, and is unchanged when symbols are reordered
   (`KotlinEntityDiscovery.kt:38-51`, `233-255`;
   `KotlinEntityDiscoveryTest.kt:906-928`).
4. **Package extraction with comments/inline comments/backticks/BOM/shebang —
   Concur with a residual header form (New Finding 3).** The scanner masks comments
   and strings, skips a leading BOM and shebang, skips simple `@file:` annotations,
   and parses dotted/backticked package names (`KotlinSourceText.kt:120-170`,
   `307-418`). The package tests cover the Round 1 and lsp-fidelity cases
   (`KotlinEntityDiscoveryTest.kt:768-837`).
5. **Collision-safe duplicate ids — Concur.** `EntityIdentity.nodes` now seeds
   `used` with unique base ids and increments suffixes until the candidate is
   unused; the `x`, `x`, `x#0` case produces three distinct ids
   (`EntityIdentity.kt:23-43`; `EntityIdentityTest.kt:78-95`).
6. **Tests for prior boundaries — Concur.** The suite now covers
   `Struct`/`Operator`, class-named constructors, private/init constructors,
   annotations/comments, package edge cases, fallback reordering, duplicate
   suffixes, receivers, default/multiline signatures, body edits, CRLF/Unicode
   spans, backticked names, BOM, and shebang. The remaining gaps are tied to the
   new findings below.

### New findings

1. **Angle-depth tracking treats `<` comparisons in default expressions as
   generic openings**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinSourceText.kt:105-134`;
     `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:87-103`.
   - Problem: `headerEnd` increments `angles` for every `<` and decrements only
     for `>`, regardless of expression context. In a valid declaration such as
     `fun isBelow(value: Boolean = 1 < 2): Boolean = value` or
     `class Box(val value: Boolean = 1 < 2) { fun member() = 1 }`, the comparison
     leaves `angles` positive. The scan therefore fails to stop at the top-level
     expression-body `=` or class body `{`, and the signature fingerprint absorbs
     the body or class members. Editing only the body then changes the entity id,
     violating the stable-identity requirement, and a class signature can contain
     its members.
   - Suggested fix: Do not use bracket-angle depth for finding the declaration
     body boundary; parentheses and brackets are sufficient because a top-level
     `{`/`=` cannot occur inside a Kotlin type argument. Alternatively make angle
     tracking type-context aware. Add tests with a `<` comparison in a function
     default and in a class constructor default, and assert that body edits do not
     change the ids.

2. **`isInitBlock` scans the entire constructor range for any `init {` sequence**

   - Location:
     `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:132-180`,
     `295-308`.
   - Problem: The fallback check treats a constructor as an init block when any
     token sequence `init` followed by `{` appears anywhere in its symbol range.
     A valid secondary constructor whose body declares a local class or object
     with an init block therefore disappears as if the constructor itself were an
     init block. For example,
     `class C { constructor(x: Int) { class Local { init { println(x) } } } }`
     drops `C.constructor`. The same fallback also risks dropping a primary
     constructor when the server's constructor range encloses the class body.
   - Suggested fix: Identify an init block only when its own declaration begins
     with `init` (after annotations/modifiers) and is followed by `{`, or when the
     target name is `<class initializer>`; do not scan the whole constructor range
     for nested init blocks. Add a test for a public constructor containing a
     local class with an init block.

3. **Package extraction still rejects grouped file annotations**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinSourceText.kt:138-170`,
     `307-341`.
   - Problem: `skipFileAnnotation` accepts a single `@file:Name` with optional
     parenthesized arguments but not Kotlin's grouped use-site syntax. A valid file
     header such as `@file:[JvmName("X") Suppress("Y")]` followed by
     `package app` stops at `[`, so `packageName` returns empty and every entity
     loses its package. The BOM and shebang cases are fixed, but this header form
     is not covered.
   - Suggested fix: After the `file:` target, skip a balanced bracketed annotation
     group (including nested parentheses) before looking for the package directive,
     and add a grouped `@file:[...]` package test.

### Concur

- The Round 1 traversal, visibility, qualified-name, and no-line/column identity
  behavior remains intact; backticked names no longer aid synthetic-name
  filtering incorrectly, and `init`/`<init>` names no longer reject valid class
  constructors.
- Comments, ordinary/raw strings, char literals, nested block comments, and
  backticked identifiers are now opaque to masking and keyword/modifier scans in
  the tested paths.
- The missing-signature fallback is content-derived, deterministic, marked
  unstable, and contains no line or column.
- Package comments, inline comments, backticked segments, BOM, shebang, and simple
  file annotations are handled; no-package files remain unqualified.
- Duplicate-id disambiguation is collision-safe against the complete result set,
  and duplicate entities remain marked unstable.
- Constructor visibility, labels, parameter-list signatures, overloads, and
  init/synthetic exclusion work for the target-shaped cases in the tests.
- Source spans still pass LSP coordinates through unchanged, with CRLF/Unicode
  coverage, and the authored source and tests contain no comments.
- No build or test command was run for this review; the handover is assumed green.

### Dissent

- New Finding 1: the header scan must not let `<` comparisons suppress the body
  boundary and fold body text into signatures.
- New Finding 2: init-block detection must be scoped to the declaration itself,
  not any nested `init {` in the constructor range.
- New Finding 3: grouped `@file:[...]` headers must preserve the following package
  directive.
- The six Round 1 findings are otherwise concurred as addressed.

## Round 3

**Verdict:** Approved.

The three Round 2 findings are fixed and covered by tests. The header scan no
longer uses angle depth, init-block detection is scoped to the declaration start,
and grouped `@file:[...]` headers now skip the bracket group before reading the
package directive. No new correctness problems were found.

### Verification

- **Header body boundary — Concur.** `headerEnd` now tracks only parentheses and
  brackets and stops at the first top-level `{` or, for functions and properties,
  `=` (`KotlinSourceText.kt:105-131`). Default-value comparisons such as
  `fun isBelow(value: Boolean = 1 < 2): Boolean = value` and
  `class Box(val value: Boolean = 1 < 2) { ... }` no longer suppress the body
  boundary. `stopsAtBodyBoundariesWhenDefaultsContainComparisons` verifies that
  body edits leave the ids unchanged, that the function signature excludes its
  body, that the class signature excludes its members, and that the constructor
  signature retains the comparison
  (`KotlinEntityDiscoveryTest.kt:781-803`).
- **Init-block detection — Concur.** `isInitBlock` now delegates to
  `startsWithInitBlock`, which skips annotations and modifiers at the start of the
  symbol and accepts `init` only when it is the first declaration token followed
  by `{`; it no longer scans the whole range for a nested sequence
  (`KotlinSourceText.kt:443-463`; `KotlinEntityDiscovery.kt:295-305`).
  `keepsConstructorContainingALocalClassWithAnInitBlock` verifies that a public
  secondary constructor with a local class containing `init { ... }` is retained
  with the expected signature (`KotlinEntityDiscoveryTest.kt:805-831`).
- **Grouped file annotations — Concur.** `skipFileAnnotation` recognizes a
  bracketed group after `file:` and uses `matchingBracketGroup` to skip balanced
  parentheses and brackets, including backticked identifiers, before returning to
  the package scan (`KotlinSourceText.kt:304-372`). The grouped
  `@file:[JvmName("X") Suppress(format(1, 2))]` case now yields `app.Widget`
  (`KotlinEntityDiscoveryTest.kt:902-908`).
- **Tests — Concur.** The three regression tests directly exercise the reported
  boundaries, including id stability under body-only edits, removal of the old
  false init-block classification, and package recovery after a grouped file
  annotation. The prior suite for backticks, constructors, package headers,
  fallback identity, duplicate ids, and CRLF/Unicode spans remains present.

### New findings

None. The angle-depth failure, constructor-range init scan, and grouped file
annotation failure are addressed without introducing a new correctness problem in
the checked paths.

### Concur

- Round 1 and the lsp-fidelity findings remain addressed for the current
  implementation.
- Backticked identifiers, comments, strings, and char literals remain opaque to
  masking, keyword lookup, and modifier scanning.
- Missing-signature fallback is still declaration-derived, deterministic, marked
  unstable, and free of line/column data.
- Package extraction still handles comments, inline comments, backticked segments,
  BOM, shebang, and simple file annotations, now including grouped file
  annotations.
- Duplicate-id disambiguation remains collision-safe against the complete result
  set.
- Constructor visibility, labels, overload signatures, init/synthetic exclusion,
  and IDs remain correct for the covered target shapes.
- Source spans, CRLF/Unicode handling, and the no-comments requirement remain
  intact.
- No build or test command was run for this review; the handover is assumed green.

### Dissent

- None.
