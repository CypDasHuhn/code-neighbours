## Round 1

**Verdict:** Changes requested.

The contract is close to complete: endpoints and keys are canonical, self-edges
are rejected on the edge types, identifiers and spans are validated, and the
JSON codec is strict. The remaining issues are an unvalidated origin/count
aggregate, a wire-format test gap, and a containment self-reference that the
`Node` type still permits.

### Findings

1. **Origin metadata is not validated as an aggregate**

   - Location: `analysis-engine/api/src/main/kotlin/dev/codenb/api/Edge.kt:43-60`.
   - Problem: `Edge` accepts duplicate origins for the same ordered pair and
     never checks the relationship between `count` and the retained origin
     counts. Two identical `EdgeOrigin(a, b, n)` entries are accepted, and
     `count` may be anything positive regardless of the origin counts. The
     fixtures already assume the intended invariant (`1 + 2 = 3` in
     `ContractJsonTest`, `2 + 3 = 5` in `EdgeContractTest`), but nothing
     enforces it. A consumer that aggregates origins for counts or dot output
     can therefore double-count or produce metadata that contradicts the edge.
   - Suggested fix: Reject duplicate `(origin.from, origin.to)` pairs and, when
     `origins` is non-empty, require `count == origins.sumOf { it.count }`. If
     `count` is intentionally allowed to be independent or larger than the
     retained origins, document that contract and add a test showing it. Add
     tests for duplicate origins and a count mismatch.

2. **Wire field names and identifier shapes are not pinned by tests**

   - Location: `analysis-engine/api/src/test/kotlin/dev/codenb/api/ContractJsonTest.kt:74-150`.
   - Problem: Most serialization tests only assert `decode(encode(value)) ==
     value`. That proves self-consistency but not the acceptance requirement of
     stable, explicit field names. Renaming `qualifiedName`, `startLine`,
     `origins`, or a field on any DTO would still pass, as would changing
     `GraphId`/`NodeId` from a JSON string to an object. Only the absent
     `Node` fields are asserted by name.
   - Suggested fix: Assert exact JSON or the encoded key set for representative
     `Node`, `Edge`, `EdgeOrigin`, `EdgeKey`, `Expansion`, `GraphInfo`, and
     `EngineError` payloads. Explicitly assert that `GraphId` and `NodeId` encode
     as JSON strings and that edge fields are named `kind`, `from`, `to`, and
     `count`.

3. **`Node` allows itself as its own parent**

   - Location: `analysis-engine/api/src/main/kotlin/dev/codenb/api/Node.kt:6-15`.
   - Problem: `Node(id = x, parent = x)` is accepted. The parent pointer encodes
     containment, and the neighbour model requires self-edges to be suppressed
     at every level. The edge-key tests cover `Edge`, `EdgeOrigin`, and
     `EdgeKey`, but no validation or test covers the containment relationship
     carried by `Node.parent`.
   - Suggested fix: Add `require(parent != id)` to the `Node` initializer and a
     test that a self-parent node is rejected.

### Concur

- `Edge` and `EdgeKey` canonicalize endpoints by node-id value, so reversed
  inputs produce the same edge key and equal edges; the tests exercise both
  `Edge.of` and `EdgeKey.of`.
- `Edge`, `EdgeOrigin`, and `EdgeKey` reject self-edges, and the direct
  constructors reject non-canonical endpoints.
- Edge and origin counts reject zero and negative values.
- `SourceSpan` rejects negative coordinates and inverted spans while allowing
  zero-length same-position spans, which matches LSP ranges.
- Blank `GraphId`, `NodeId`, and `EngineError.code` values are rejected.
- `GraphJson` sets `encodeDefaults`, `explicitNulls`, strict parsing, and
  `ignoreUnknownKeys = false`; null optionals are emitted explicitly and
  unknown keys are rejected in the tested path.
- Round-trip coverage exists for every contract type, including enums, keys,
  origins, expansion, graph info, and engine errors.
- The api module has no LSP, file-system, or renderer dependency, and the
  authored source and tests contain no comments.

### Dissent

- Finding 1: duplicate origins and inconsistent origin/count totals must be
  rejected or the intended relationship must be made explicit and tested.
- Finding 2: stable wire names and identifier shapes need direct assertions,
  not only round-trip equality.
- Finding 3: a node must not be its own containment parent.

## Round 2

**Verdict:** Approved. All three Round 1 findings are addressed, and the added
validation does not introduce a new correctness problem.

### Finding disposition

- **Finding 1 — Concur (addressed).** `Edge.init` rejects duplicate directed
  origins (`Edge.kt:60-61`), requires at least one origin for `REFERENCES`,
  `FILE_REFERENCES`, and `GROUP_REFERENCES` (`Edge.kt:62-64`), and requires
  `count` to equal the origin total whenever origins exist (`Edge.kt:65-67`).
  Tests cover duplicate origins, missing origins for every reference kind, and
  count mismatches for reference and containment edges. `Edge.of` still
  canonicalizes endpoints while preserving directed origin metadata, and
  `CONTAINS` remains valid without origins.
- **Finding 2 — Concur (addressed).** `WireFormatTest` pins exact key sets and
  values for `Node`, the span, `EdgeOrigin`, `Edge`, `EdgeKey`, `Expansion`,
  `GraphInfo`, and `EngineError`. Identifiers are asserted to encode as JSON
  strings, and error-code serial names are pinned. The `api` dependency change
  exposes the serialization runtime without weakening strict decoding.
- **Finding 3 — Concur (addressed).** `Node.init` rejects `parent == id`, and
  tests cover self-parent entities, files, and groups. The additional per-kind
  requirements match the model: entities require `fileUri`, `qualifiedName`, and
  `signatureFingerprint`; files require `fileUri`; groups may omit them.

### New findings

None. The per-kind `Node` validation is consistent with the entity identity tuple
and file/group meanings in `docs/neighbour-model.md`, and fields the model treats
as optional remain optional. `GraphInfo` now uses lists with a canonical
enum-order rule: canonical order is accepted, unsorted and duplicate lists are
rejected, and the encoded order is pinned. The required exact protocol version
and fixed error-code set are also consistent with the frozen v1 contract.
Reversed-edge equality, canonical keys, origin direction, span validation, and
blank-id validation remain intact, and no comments were added.

### Concur

- All Round 1 findings are resolved without removing the original contract
  guarantees.
- The new validation is not over-constraining for the values the model permits.
- Round-trip, wire-format, origin/count, self-parent, kind-order, and
  protocol-version tests exercise the contract rather than only the
  implementation.

### Dissent

- None.
