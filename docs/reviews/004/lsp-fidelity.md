## Round 1

**Verdict:** Changes requested.

The traversal shape is sound: it walks containers, prunes function-scoped locals and private containers, retains `internal`/`protected`, builds hierarchical qualified names, and keeps LSP4J in the frontend. The findings below are LSP-shape gaps that the current fixtures do not expose, plus identity and boundary issues that affect the same code.

### Findings

1. **Data classes and top-level operator functions are dropped**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:89-120`; tests at `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/KotlinEntityDiscoveryTest.kt:44-64`.
   - Problem: The target Kotlin LSP reports a `data class` as `SymbolKind.Struct` and a top-level `operator fun` as `SymbolKind.Operator`. `entityKind` handles neither, so it returns `null`, `collect` returns before visiting children, and both the declaration and every member of a data class disappear. This violates the entity definition for top-level classes and functions.
   - Suggested fix: Map `SymbolKind.Struct` to `CLASS` and `SymbolKind.Operator` to `FUNCTION`/`METHOD` based on `insideType`. Add unit fixtures using `Struct` and `Operator`, and verify them against real `data class` and top-level `operator fun` sources.

2. **Constructor handling assumes a name identifier the Kotlin LSP does not provide**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:55-87`, `140-153`, `205-216`; test at `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/KotlinEntityDiscoveryTest.kt:123-177`.
   - Problem: The target Kotlin LSP maps primary and secondary constructors to `SymbolKind.Constructor` with `name` equal to the containing class name and no name identifier, so `selectionRange` normally falls back to the whole constructor range. The code derives the visibility prefix from `range.start` to `selectionRange.start`, which is empty, so a `private constructor` is not filtered. The signature scan starts at the end of the constructor range, so it normally yields `no-signature#N`; the qualified name becomes `app.Outer.Outer` rather than a constructor name. The test uses a fabricated `name = "constructor"` and a selection range around that name, so it does not exercise the server shape. Init blocks (`<class initializer>`) are correctly rejected today, but the generic path cannot distinguish them from constructors.
   - Suggested fix: Handle `SymbolKind.Constructor` explicitly. Skip `<class initializer>`/init blocks, read visibility from the whole constructor declaration text, derive a stable label and qualified name (`constructor` or `<init>`) instead of the class name, and build the signature from the parameter list rather than `selectionRange.end`. Add tests with `name = "Outer"`, `range == selectionRange` covering `private constructor(...)`, and public overloaded constructors; assert that private is excluded, public constructors are included, and overloads get distinct non-empty fingerprints.

3. **The declaration-text signature scan truncates valid signatures and omits the receiver**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:140-153`; test at `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/KotlinEntityDiscoveryTest.kt:315-338`.
   - Problem: The target LSP's document-symbol mapper supplies `detail = null`, so this fallback is the production path. The scan starts after `selectionRange.end` and stops at the first `{`, `=`, `\n`, or `\r`. It therefore turns `typealias Name = String` into an empty signature, truncates `fun render(a: Int = 0, b: String)` at the first default `=`, returns empty or partial text for multiline parameter lists, and omits the receiver and type parameters before the name. Distinct overloads such as `fun String.parse()` and `fun Int.parse()`, or two default-argument overloads with different later parameters, collapse to duplicate handling despite differing declaration text.
   - Suggested fix: Extract the declaration header with a balanced-delimiter scan that allows newlines, ignores delimiters inside strings/comments/annotations, and stops only at the top-level body `{` or expression-body `=`; include the receiver, type parameters, and typealias target. Add `detail = null` tests for typealias, default parameters, multiline parameters, and extension overloads.

4. **The visibility and keyword scan reads annotations, KDoc, and string literals**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:66-68`, `97-103`, `241-243`.
   - Problem: `prefix` spans source from `range.start` to `selectionRange.start`, and the target LSP's declaration range includes annotations and any KDoc/comments. `PRIVATE.containsMatchIn(prefix)` drops a public declaration such as `@Suppress("private") class Visible` or a declaration whose leading comment says "private". The same over-broad matching misclassifies `@Suppress("companion") class Foo` as `COMPANION_OBJECT` and `@Suppress("typealias") class Foo` as `TYPE_ALIAS`. Annotation arguments and comments do not prove visibility or declaration kind.
   - Suggested fix: Strip comments and string literals from the header, or tokenize the modifier list and declaration keyword so `private`, `companion`, and `typealias` match only in modifier/keyword position. Add tests with annotations or leading comments containing those words before a public declaration.

5. **The missing-signature fallback is document-order based, not declaration-derived**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:26-40`; see also `docs/neighbour-model.md:54-56`.
   - Problem: `no-signature#N` is assigned per qualified name in traversal order. Moving, inserting, or removing an earlier same-name declaration changes the id of an unchanged declaration, and ordinals are shared across different kinds with the same qualified name. The model requires the declaration range/content to be folded into the fingerprint when recovery fails; line/column are forbidden in ids, but a document ordinal is not a stable substitute. Because the target LSP always supplies null detail, this path is common for typealiases, constructors, and inferred-type properties.
   - Suggested fix: Derive the fallback from the declaration's own source text or a normalized/hash value of that text, without line or column, and mark it unstable. If an ordinal is unavoidable, include the full identity tuple and verify uniqueness. Add a test that moves an earlier missing-signature declaration and asserts the later entity's id is unchanged.

6. **Package extraction can read comments/strings and mishandles inline comments**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:158-164`, `240`.
   - Problem: `PACKAGE.find(source)` returns the first line beginning with `package` anywhere, including a block comment or multiline string before any declaration. The capture `[^\s;]+` also includes an inline comment when there is no whitespace, so `package app/* note */` yields `app/*`; all qualified names and ids then use the wrong package. Backticked package identifiers are not handled.
   - Suggested fix: Locate the package directive only in the file header, skipping comments and string literals, and parse a valid possibly-backticked qualified identifier with an optional trailing comment. Add tests for no package, a commented-out package line, an inline comment, and a backticked package.

7. **Duplicate-id disambiguation can still emit duplicate ids**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/EntityIdentity.kt:23-44`.
   - Problem: The collision check only considers the original ids; suffixes are appended without checking the resulting set. For descriptors with fingerprints `x`, `x#0`, and `x` (same file, qualified name, and kind), the first duplicate becomes `x#0`, colliding with the already-unique third descriptor, so the returned list still contains duplicate `NodeId`s. The no-signature fallback already uses `#N`, so that suffix space is not collision-free.
   - Suggested fix: Generate final ids against the complete set, incrementing a suffix until the candidate is unused, or encode the ordinal in an unambiguous component. Add a test where a duplicate suffix equals another descriptor's fingerprint.

8. **Core knows a Kotlin-only entity kind, and companion detection by name is not scoped**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/EntitySymbolKind.kt:3-14`; `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:102-107`.
   - Problem: `COMPANION_OBJECT` is a Kotlin-only concept in the language-agnostic core; later JSON/TS/Vue/C# frontends must ignore or fake it, and the core id kind embeds it. Separately, `symbol.name == "Companion"` classifies any object named `Companion` as a companion, including a regular top-level `object Companion` with no containing type. The name is a fallback for an unnamed companion, not proof of companionship.
   - Suggested fix: Map companion objects to `OBJECT` in core and keep any companion distinction in frontend/presentation metadata. Restrict the name fallback to `insideType` (and/or a `companion object` prefix). Add tests for a top-level regular `object Companion`, a nested regular object named `Companion`, and an unnamed companion.

9. **Tests use synthetic symbol shapes and do not cover the boundaries above**

   - Location: `analysis-engine/frontend/src/test/kotlin/dev/codenb/frontend/KotlinEntityDiscoveryTest.kt:512-542` and the cases above; `analysis-engine/core/src/test/kotlin/dev/codenb/core/EntityIdentityTest.kt`.
   - Problem: The helper passes a `detail` whenever one is supplied, but the target Kotlin LSP always emits `detail = null`; most fixtures therefore never exercise the production fallback for classes, typealiases, properties, companions, or constructors. The constructor fixture uses a name and selection range the server does not produce. There are no fixtures for `SymbolKind.Struct`/`Operator`, private constructors, unnamed companions, multiline or default signatures, annotation/KDoc `private`, package edge cases, or duplicate-suffix collisions. The suite passes while real data classes, operators, and constructors are dropped or misidentified.
   - Suggested fix: Add fixtures shaped like the target server (`detail = null`, `Struct`/`Operator`, class-named constructors with full-range selection, unnamed companion name `Companion`) and tests for every boundary in findings 1-8. A marked LSP integration test against a real Kotlin file is also appropriate for the server-shape cases.

### Concur

- Container-only recursion excludes function-scoped locals, locals in init blocks, and members of non-container declarations; private containers are pruned before recursion, so public-looking children do not leak.
- `internal` and `protected` top-level and member declarations are retained; anonymous object literals (null name), init blocks (`<class initializer>`), and names containing `$`/starting with `<` are excluded as intended.
- Named and unnamed companion objects and their members are traversed; primary-constructor `val`/`var` parameters appear as `SymbolKind.Variable` and map to properties; property accessors are not traversed because the property is not a container.
- Qualified names join the package and symbol hierarchy; ids use graph/file/qualified name/kind/fingerprint and contain no line or column; the line-shift test confirms spans do not affect ids, and normal duplicate ids are marked unstable and suffixed.
- Source spans pass LSP positions through unchanged; the offset math uses UTF-16 indices and handles CRLF line endings.
- LSP4J is confined to `frontend`/`lsp`, and core has no LSP4J imports, although the Kotlin-only `COMPANION_OBJECT` kind breaches the language-agnostic intent.
- The authored source, tests, and build files contain no comments.

### Dissent

- Finding 1: data classes and top-level operator functions must be entities.
- Finding 2: constructor visibility, names, and signatures must follow the real Kotlin LSP shape.
- Finding 3: signature extraction must not truncate valid overload text or omit receivers.
- Finding 4: visibility and keyword matching must ignore annotations, comments, and string literals.
- Finding 5: missing-signature identity must not depend on document order.
- Finding 6: package extraction must not read comments or strings and must handle inline comments and backticks.
- Finding 7: duplicate disambiguation must guarantee unique ids.
- Finding 8: core must stay language-agnostic, and companion detection must be scoped.
- Finding 9: tests must use the target server's symbol shapes and cover these boundaries.

## Round 2

**Verdict:** Changes requested. All nine Round 1 findings are addressed for the ordinary Kotlin shapes they target. Three new boundary problems remain around legal backticked identifiers and package header detection; the signature, visibility, and constructor changes are otherwise correct.

### Finding disposition

1. **Struct/Operator mapping and container recursion — Concur (addressed).** `baseKind` maps `Struct` to `CLASS` and `Operator` to `FUNCTION`/`METHOD`, and `Struct` classes remain containers (`KotlinEntityDiscovery.kt:178-193`, `252-260`). `discoversTopLevelDeclarationsWithTargetSymbolKinds` covers a data class with a member and a top-level operator.
2. **Constructor target shape — Concur (addressed).** Class-named primary/secondary constructors are handled separately, private primary/secondary constructors are excluded, `<class initializer>` init blocks are skipped, labels/qualified names use `constructor`, and signatures come from the parameter list (`KotlinEntityDiscovery.kt:65-79`, `130-176`). `includesPublicConstructorsAndExcludesPrivateAndInitConstructors` covers public, private, internal, protected, and init cases. A residual name-filter edge is new finding 2.
3. **Balanced signature scan — Concur (addressed).** `headerEnd` allows newlines, tracks parentheses/brackets/angles, stops only at the body/expression boundary, and `signature` starts at the declaration keyword so receivers, defaults, multiline parameters, and typealias targets are retained (`KotlinEntityDiscovery.kt:84-101`, `91-116`, `212-226`). The new tests cover default, multiline, receiver, expression-body, and typealias signatures. A residual backticked-name edge is new finding 1.
4. **Comment/string-aware visibility and keyword scanning — Concur (addressed).** `mask`/`maskComments` handle line, nested block, string, raw-string, and char literals; `hasPrivateModifier` skips annotations and matches modifiers only before the declaration keyword (`KotlinSourceText.kt:37-63`, `146-223`). `ignoresPrivateCompanionAndTypeAliasWordsInAnnotationsAndComments` and the annotated-private test cover the reported false positives. A residual backticked-identifier edge is new finding 1.
5. **Declaration-derived missing-signature fallback — Concur (addressed).** The fallback is a SHA-256 of the declaration header text (`unstable-<hex>`), not a document ordinal; it contains no line/column and is stable under list reordering (`KotlinEntityDiscovery.kt:228-250`). `usesDeclarationDerivedFallbackThatSurvivesReordering` covers reordering.
6. **Package extraction — Concur (addressed).** `packageName` scans the masked header, skips file annotations, handles comments/strings, parses dotted/backticked segments, and ignores inline comments (`KotlinSourceText.kt:120-144`, `333-375`). The package test covers default, commented-out, block-comment, inline-comment, backticked, and file-annotation cases. A residual leading-BOM edge is new finding 3.
7. **Duplicate-id disambiguation — Concur (addressed).** `EntityIdentity.nodes` seeds `used` with unique base ids and retries suffixes until the candidate is unused; the first duplicate keeps the base id and all duplicates are marked unstable (`EntityIdentity.kt:23-43`). `disambiguationSkipsSuffixesThatCollideWithOtherDescriptors` covers the collision.
8. **Core language neutrality and scoped companion detection — Concur (addressed).** `COMPANION_OBJECT` is gone from core; companions map to `OBJECT`, and companion/object handling is no longer name-special-cased (`EntitySymbolKind.kt:3-14`, `KotlinEntityDiscovery.kt:178-193`). `distinguishesTopLevelNestedAndUnnamedCompanions` covers top-level, nested regular, and unnamed companion objects.
9. **Target-shape and boundary tests — Concur (addressed).** The suite now covers `Struct`/`Operator`, class-named constructors with full-range selection, private/init constructors, annotations/comments, package edge cases, declaration-derived fallback, duplicate suffixes, receivers, default/multiline signatures, body edits, CRLF/Unicode spans, and companion shapes. The remaining gaps are tied to the new findings below.

### New findings

1. **Legal backticked identifiers are still treated as code by the header/scanner layer**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:84-101`, `130-176`; `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinSourceText.kt:58-63`, `91-116`, `146-223`, `237-255`.
   - Problem: `mask`, `maskComments`, `tokens`, `headerEnd`, `keywordOffset`, `hasPrivateModifier`, and `firstParentheses` understand comments and strings but not backticked identifiers. Kotlin allows many punctuation characters inside backticks, and these valid names are seen as syntax:
     - ``class `typealias` { class Nested }`` makes `isTypeAlias` true from the class name (`KotlinEntityDiscovery.kt:87-89`), so the class is typed `TYPE_ALIAS`, is not treated as a container, and `Nested` disappears. A function or property named `` `typealias` `` is likewise given the wrong kind, and a parameter/type named `` `typealias` `` can trigger the same misclassification.
     - Overloads of ``fun `a{b`(...)`` or ``fun `a=b`(...)`` stop at the `{`/`=` inside the name in `headerEnd`, so their signatures truncate before the parameter list and they fall into duplicate/unstable handling. A name containing `(` can leave the parenthesis depth unbalanced and fold body text into the signature.
     - ``class `private`(val value: Int)`` makes primary-constructor visibility scanning see the class name as a `private` modifier, dropping the public constructor.
     - A name containing `'` or `"` can start a char/string mask inside the name and blank the rest of the header.
   - Suggested fix: Add a Kotlin-aware scan that recognizes backticked identifiers and treats their contents as opaque for comment/string masking, delimiter balancing, keyword lookup, and modifier scanning, while retaining the original text for signature output. Gate `isTypeAlias` to `baseKind == CLASS` and require `typealias` in declaration-keyword position, outside backticks. Add tests for a class named `` `typealias` `` with a nested member, overloaded functions named `` `a{b` `` and `` `a=b` ``, a class named `` `private` `` with a public primary constructor, and a name containing `(`.

2. **Name-based init and synthetic filters reject legal backticked declarations**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:262-265`, `138-140`.
   - Problem: `isInitSymbol` treats any constructor named `init` or starting with `<init>` as an init block. The target Kotlin LSP names constructors after their containing class and names init blocks `<class initializer>`, so a valid class named `` `init` `` has constructor name `init` and all its constructors are dropped. The extra `<init>` alias also discards constructors from a server that uses that name. Separately, `isNamedEntity` rejects any name containing `$`, but a valid backticked name such as `` `foo$bar` `` is a user declaration, not a synthetic one; the declaration and its nested members are dropped. The tests use `"init"`/`"<init>"` only with init-block ranges and an unbackticked `Generated$Access`, so they do not catch these false negatives.
   - Suggested fix: Identify init blocks by the target `<class initializer>` name or by verifying an `init { ... }` span in the source; do not reject constructors by class name or `<init>`. Distinguish synthetic `$` names from legal backticked names using the source/selection range or a known compiler-generated pattern, not the bare `$` character. Add tests for a class named `` `init` `` with public/private constructors, a constructor symbol named `<init>`, and a class/function named `` `foo$bar` `` with a nested member.

3. **Package extraction still rejects a leading UTF-8 BOM**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinSourceText.kt:120-144`.
   - Problem: `packageName` skips only `Char.isWhitespace()` and `@file:` annotations. A valid file beginning with a UTF-8 BOM (`\uFEFF`) is not whitespace, so the scanner returns an empty package even when `package app` follows; every qualified name and id in the file is then unqualified. The same header scan also fails on a leading `.kts` shebang line if such a script carries a package directive.
   - Suggested fix: Skip a leading BOM and, for scripts, a first-line shebang before scanning the header. Add tests with a BOM-prefixed package and a script shebang.

### Concur

- The nine Round 1 findings are addressed in the ordinary and target-shaped cases; the container walk, visibility filter, constructor shape, signature scan, package header handling, declaration-derived fallback, duplicate disambiguation, core neutrality, and test coverage all improve consistently.
- Ids still contain no line or column, the fallback hash is deterministic, and the duplicate retry loop now guarantees uniqueness against the complete result set.
- LSP4J remains confined to `frontend`/`lsp`; core has no Kotlin-only kind and no LSP4J imports.
- The new source and tests contain no comments, and no build or test command was run for this review.

### Dissent

- New finding 1: backticked identifiers must be opaque to the scanner; otherwise legal names can change kind, lose members, lose constructors, or collapse overload signatures.
- New finding 2: init detection and synthetic-name filtering must not reject legal backticked names such as `init` and `foo$bar`.
- New finding 3: package extraction must skip a leading BOM (and a script shebang) before reading the package directive.

## Round 3

**Verdict:** Changes requested. The Round 2 findings and the later correctness fixes are present: backticks are opaque, typealias detection is gated for ordinary declarations, init/synthetic filtering follows the target names, package extraction handles BOM/shebang/comments/inline comments/backticks/grouped annotations, constructors keep the target shape, and the angle-depth boundary is gone. One new LSP-shape/identity problem remains: declaration-keyword and typealias detection read `::class` inside leading annotations as the declaration keyword.

### Finding disposition

1. **Backticked identifiers opaque across scanning — Concur (addressed).** `mask`/`maskComments` skip backtick spans, `tokens` emits a backticked name as one word, and `headerEnd`, `matchingBracketGroup`, `matchingParenthesis`, `skipAnnotation`, `firstParentheses`, and `startsWithInitBlock` all preserve or skip backticks (`KotlinSourceText.kt:8-42`, `79-131`, `169-247`, `261-302`, `344-397`, `465-471`). `treatsBacktickedIdentifiersAsOpaque` covers keyword names, braces, equals, parentheses, quotes, constructors, and nested members (`KotlinEntityDiscoveryTest.kt:620-692`).
2. **Typealias detection gated — Concur for ordinary and backticked declarations; residual annotation case is New Finding 1.** The gate requires `baseKind == CLASS` and checks the `class`/`typealias` keyword (`KotlinEntityDiscovery.kt:86-98`); a backticked `` `typealias` `` class is no longer a typealias (`KotlinEntityDiscoveryTest.kt:621-692`).
3. **Constructor names and synthetic filtering — Concur (addressed).** `isInitBlock` checks only `<class initializer>` or a declaration-scoped `init {` (`KotlinEntityDiscovery.kt:295-305`; `KotlinSourceText.kt:443-463`), so constructors named `init` and `<init>` are retained, while a real nested init block does not drop its constructor. `isNamedEntity` retains backticked `$` names and excludes unbackticked `<...>`/`$` names (`KotlinEntityDiscovery.kt:267-293`). `handlesBacktickedInitAndDollarNames` and `keepsConstructorContainingALocalClassWithAnInitBlock` cover the shapes (`KotlinEntityDiscoveryTest.kt:694-838`).
4. **Package extraction — Concur (addressed).** A BOM and script shebang are skipped before the header scan; comments and strings are masked; backticked segments parse; inline comments stop the name; and grouped `@file:[...]` annotations are balanced before the package directive (`KotlinSourceText.kt:135-167`, `304-441`). The package tests cover all of these forms (`KotlinEntityDiscoveryTest.kt:768-910`).
5. **Constructor target-server shape and signatures — Concur (addressed).** Class-named primary/secondary constructors use the parameter list as the signature, visibility is read from the declaration header, duplicate overloads stay distinct, and `<class initializer>` blocks are excluded (`KotlinEntityDiscovery.kt:65-79`, `132-181`; `KotlinEntityDiscoveryTest.kt:254-392`).
6. **Other LSP-shape and identity surfaces — Concur with the exception of New Finding 1.** Ids remain line/column-free, duplicate disambiguation is collision-safe, spans pass LSP coordinates through, LSP4J stays in `frontend`, and no comments were added.

### New findings

1. **Declaration-keyword and typealias detection read `::class` in annotations as the declaration keyword**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:86-103`, `200-215`; `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinSourceText.kt:65-77`.
   - Problem: `isTypeAlias` calls `firstKeyword(..., CLASS_OR_TYPE_ALIAS)` over the whole declaration range, and `declarationKeyword` uses `keywordOffset` to return the first matching token anywhere in that range. The target LSP's declaration range includes leading annotations (the basis of the Round 1 annotation finding). A normal annotation argument such as `@OptIn(ExperimentalStdlibApi::class)` leaves a real `class` token inside the annotation, before the declaration keyword:
     - `@OptIn(ExperimentalStdlibApi::class) typealias Name = String` is classified as `CLASS`, not `TYPE_ALIAS`, because the first `class`/`typealias` token is the annotation's `class`.
     - `@OptIn(ExperimentalStdlibApi::class) private class Hidden` (and a private `enum class`) produces `keywordStart` at the annotation's `class`; `hasPrivateModifier(start, keywordStart)` then stops before the `private` modifier and the private declaration is emitted.
     - Even for public classes and enums the signature starts at the annotation's `class`, so the fingerprint absorbs annotation text such as `class) class C` instead of beginning at the declaration keyword.
     Existing annotation tests use string arguments (`@Suppress("private")`, `@Deprecated("old")`), whose contents are masked, so the `::class` token is not covered.
   - Suggested fix: Locate the declaration keyword after annotations and modifiers (reuse `skipAnnotation` token handling) and determine typealias-ness from that keyword rather than the first `class`/`typealias` token in the header. Add tests for an annotated typealias and an annotated private class/enum with a `Foo::class` annotation argument, and assert the typealias kind, private exclusion, and that public signatures begin at `class`/`enum`/`typealias`.

### Concur

- The Round 2 backtick opacity, init/synthetic filtering, BOM/shebang package handling, and the correctness angle-boundary, nested-init, and grouped `@file` fixes are all present and behave as reported.
- Constructor names (`init`, `<init>`), class-named primary/secondary constructors, parameter-list signatures, and private/internal/protected constructor filtering follow the target LSP shape.
- Ids remain free of line and column, use the graph/file/qualified-name/kind/fingerprint tuple, and the duplicate retry loop still guarantees uniqueness.
- LSP4J remains confined to `frontend`; core stays language-neutral; source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review.

### Dissent

- New Finding 1: declaration-keyword and typealias detection must skip annotation arguments so a `::class` literal cannot misclassify a typealias, hide a `private` modifier, or fold annotation text into a signature.
- The Round 2 findings and the later correctness fixes are concurred as addressed.

## Round 4

**Verdict:** Changes requested. The Round 3 annotation/keyword finding is fixed: annotations and their balanced arguments are skipped before keyword lookup, typealias detection uses the real keyword, visibility and signatures start at that keyword, and the new test covers annotated typealias, annotated private class/enum, annotated public class/enum, and annotation-free signatures. One new regression remains in the stricter keyword scanner: declarations with a `context(...)` receiver/parameter clause before the actual keyword return no keyword, which disables the visibility check and puts annotation/context text back into the signature.

### Finding disposition

1. **Annotations, use-site targets, balanced arguments, comments, strings, and backticks skipped before the keyword — Concur (addressed).** `declarationKeyword` skips `@`, use-site targets, grouped `[...]` annotations, dotted annotation names, and balanced parenthesized arguments through `skipAnnotation`/`afterBalanced` (`KotlinSourceText.kt:72-96`, `280-324`). It runs on masked text, so comments and strings are blanked, and `tokens` keeps backticked spans as single opaque words (`KotlinSourceText.kt:8-42`, `188-266`).
2. **`isTypeAlias`/`declarationKeyword` use the real keyword — Concur for annotations (addressed).** `collect` gets the declaration token from `declarationKeyword(... declarationKeywords(baseKind))`, classifies typealias only when that token is `typealias`, and uses its start for visibility and signatures (`KotlinEntityDiscovery.kt:86-102`, `199-210`). `@OptIn(Foo::class)` no longer supplies the `class` token; the typealias is `TYPE_ALIAS` and the class/enum keywords are correct (`KotlinEntityDiscoveryTest.kt:586-651`).
3. **Visibility and signatures start at the real keyword — Concur for annotated declarations (addressed).** Annotated private class/enum are excluded, annotated public class/enum are included, and the test asserts no `@OptIn`, `Suppress`, or `Foo::class` text in fingerprints (`KotlinEntityDiscoveryTest.kt:636-650`). Annotation-free signatures continue to start at `class`/`enum`/`typealias`/`fun`/`val` in the existing tests.
4. **New tests — Concur.** `usesTheRealDeclarationKeywordAfterAnnotations` covers the requested annotated typealias, private class/enum, public class/enum, and annotation-free signature behavior (`KotlinEntityDiscoveryTest.kt:586-651`); the remaining suite keeps the annotation-free signature cases.

### New findings

1. **A `context(...)` declaration prefix makes keyword lookup return null, disabling visibility and reintroducing annotation text in fingerprints**

   - Location: `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinSourceText.kt:72-96`, `510-542`; `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:87-102`.
   - Problem: `declarationKeyword` returns `null` as soon as it sees a word that is neither the expected declaration keyword nor a member of `MODIFIERS`. `context` is not in that set, and its balanced parameter/receiver list is not skipped. Valid Kotlin declarations can begin with a context clause before the declaration keyword, for example `private context(Logger) fun hidden()` or `context(Logger) private fun hidden()` (context parameters apply to functions, properties, and classes). In both orders the scanner reaches `context` after skipping `private` or reaches it first, returns `null`, and `collect` falls back to `keywordStart = start`:
     - `hasPrivateModifier(masked, start, start)` scans an empty range, so a private context function/property/class is emitted as an entity.
     - `signature` and `fallbackText` start at the symbol range start instead of the real `fun`/`val`/`class`, so a leading annotation such as `@OptIn(Foo::class)` re-enters the fingerprint even though Round 3 fixed that for ordinary declarations, and an annotation-free context signature includes the context clause rather than starting at the declaration keyword.
     Before this change `keywordOffset` searched the whole header for the keyword, so these declarations did get a real keyword start and a `private` modifier before it was detected. There is no context-clause test.
   - Suggested fix: Treat a `context` clause as a declaration prefix alongside annotations and modifiers: after skipping annotations and modifiers, when the token is `context`, skip its balanced parenthesized argument list and continue the keyword/modifier scan. Keep the annotation-skip behavior so `Foo::class` inside annotations cannot be mistaken for the keyword. Add tests for a private and a public context function/property, including a leading `@OptIn(Foo::class)` annotation, and assert the private declaration is excluded, the public one is included, and the signature starts at `fun`/`val` with no annotation or `context(...)` text.

### Concur

- The Round 3 annotation-token fix is correct for annotated typealiases, private classes/enums, and public classes/enums; the new test exercises the target declaration range with annotations included.
- Backticked identifiers, comments, strings, use-site targets, grouped annotation brackets, and nested parentheses in annotations remain opaque to keyword/visibility scanning.
- Constructor handling, package extraction, synthetic/init filtering, spans, and collision-safe duplicate identity are unchanged and still consistent with the target LSP shape.
- LSP4J remains confined to `frontend`; core stays language-neutral; the current source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review.

### Dissent

- New Finding 1: `context(...)` clauses must be skipped as declaration prefixes so keyword detection cannot fall back to the range start, leak a private declaration, or put annotation/context text into the identity fingerprint.
- The Round 3 finding is concurred as addressed for declarations without a context clause.

## Round 5

**Verdict:** Approved. The Round 4 context regression is fixed. `declarationKeyword` recognizes a `context` prefix, requires and skips its balanced parenthesized receiver/parameter list, and continues scanning annotations and modifiers until the real declaration keyword. Private context declarations are excluded, public ones are included, context and annotation text no longer enter signatures, and the new tests cover the requested shapes and modifier orders. No new LSP-shape or identity problems were found.

### Finding disposition

1. **`declarationKeyword` skips `context(...)` before modifiers and the real keyword — Concur (addressed).** The scanner checks the `context` token, requires a following `(`, advances with `afterBalanced(tokens, index, "(", ")")`, and continues the loop (`KotlinSourceText.kt:72-104`, `518-552`). Because the loop continues, it handles both `context(Foo) private fun ...` and `private context(Foo) fun ...`, as well as annotations interleaved before the context clause.
2. **Private context declarations excluded, public included — Concur (addressed).** `collect` uses the returned keyword start, so `hasPrivateModifier(start, keywordStart)` now scans the full prefix from annotations/context through the declaration keyword (`KotlinEntityDiscovery.kt:86-101`). The test asserts `hidden`, `hiddenFirst`, and `HiddenClass` are absent and `visible`, `VisibleClass`, and `annotatedVisible` are present (`KotlinEntityDiscoveryTest.kt:586-628`).
3. **Context text does not enter signatures — Concur (addressed).** Signatures and fallback text start at the real keyword (`fun`/`class`), so context clauses and leading annotations are excluded. The test asserts each signature starts at the declaration keyword and that no fingerprint contains `context(` or `@OptIn` (`KotlinEntityDiscoveryTest.kt:620-624`).
4. **New tests — Concur.** `skipsContextReceiverPrefixesBeforeTheDeclarationKeyword` covers private/public context functions and classes, both modifier orders (`context(...) private` and `private context(...)`), and a leading `@OptIn(Foo::class)` annotation (`KotlinEntityDiscoveryTest.kt:586-628`).

### Concur

- The Round 4 finding is addressed for context functions and classes; the annotation/typealias and visibility/signature behavior from Round 3 remains intact.
- Context clauses with nested parentheses, comments, strings, backticks, and annotations remain opaque because the scanner uses the already-masked token stream and balanced-token skipping.
- Constructor handling, package extraction, synthetic/init filtering, spans, and collision-safe duplicate identity are unchanged and consistent with the target LSP shape.
- LSP4J remains confined to `frontend`; core stays language-neutral; the current source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review.

### New findings

None.

### Dissent

None. The Round 4 finding is concurred as addressed, and no new LSP-shape or identity problems were found.
