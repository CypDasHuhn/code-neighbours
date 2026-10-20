## Round 1

**Verdict:** Changes requested.

The contract has the right vocabulary and the right high-level split: node and
edge kinds match the model, edges are canonicalised by `(kind, min, max)`,
self-edges are rejected, and `GraphJson` is strict rather than polymorphic. The
findings below are places where the frozen surface still permits ambiguous
states or does not expose the model it claims to encode.

### Findings

1. **Reference counts and directed origins can disagree**

   - Location: `analysis-engine/api/src/main/kotlin/dev/codenb/api/Edge.kt:42-59`
     (`Edge` init) and `Edge.kt:65-78` (`Edge.of`).
   - Problem: `Edge.count` is independent of `origins`. The model says the
     underlying directed observations are retained for metadata/counts and that
     incoming and outgoing references both count. As written, the same edge key
     can be encoded as `count = 5` with no origins, with one origin of 2, or
     with origins summing to 3. A reference edge can therefore lose all
     direction metadata, duplicate directed origins are accepted, and a
     consumer cannot tell whether `count` is total, incoming, or outgoing. That
     is exactly the kind of ambiguity a frozen contract should exclude.
   - Suggested fix: Define `count` as the total of the observations and enforce
     `count == origins.sumOf { it.count }` whenever origins are present; require
     at least one origin for `REFERENCES`, `FILE_REFERENCES`, and
     `GROUP_REFERENCES` (or move reference metadata into a dedicated type);
     reject duplicate directed origins. Add contract tests for empty and
     mismatched origins.

2. **Node identity fields do not express the model's identity tuple**

   - Location: `analysis-engine/api/src/main/kotlin/dev/codenb/api/Node.kt:6-15`
     and `Ids.kt:15-22`; model identity rule at
     `docs/neighbour-model.md:43-56`.
   - Problem: The model's entity identity is
     `graphId / fileUri / qualifiedName / kind / signatureFingerprint`. `Node`
     exposes `file` without saying whether it is a URI or a filesystem path, and
     it omits the signature fingerprint entirely; overloads therefore differ
     only in the opaque `id`, and `unstable` is exposed without the identity
     component that caused it. If the id is intended to be the sole identity,
     the partial `file`/`qualifiedName` fields still imply a reconstructable
     identity that is not actually present.
   - Suggested fix: Add `signatureFingerprint: String?` (or a typed
     `EntityIdentity` containing graph id, file URI, qualified name, kind, and
     fingerprint) for `ENTITY` nodes, and rename `file` to `fileUri` (or
     `filePath`) with one defined form. Alternatively, make `NodeId` explicitly
     the sole identity and remove the partial identity fields so consumers do
     not infer identity rules. Whichever choice is made, it must be reflected in
     the frozen types and JSON now.

3. **The serialization runtime is not exported with the contract**

   - Location: `analysis-engine/api/build.gradle.kts:6`.
   - Problem: `kotlinx-serialization-json` is declared as `implementation`, but
     `GraphJson.json` returns `kotlinx.serialization.json.Json` and the inline
     `encode`/`decode` functions expose the serialization API. A module that
     depends on `:api` does not get that dependency on its compile classpath, so
     the frozen contract is not usable by `core`, `cli`, or `server` without
     each consumer re-declaring it.
   - Suggested fix: Declare the dependency as
     `api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")`; keep the
     existing `testImplementation(kotlin("test"))`.

4. **`GraphInfo` kind sets do not have a stable JSON order**

   - Location: `analysis-engine/api/src/main/kotlin/dev/codenb/api/GraphInfo.kt:11-12`.
   - Problem: `nodeKinds` and `edgeKinds` are `Set`s. `kotlinx.serialization`
     emits a set in iteration order, but the `Set` type does not guarantee an
     order, so a hash-backed producer can emit different arrays for the same
     graph. This makes the frozen contract's JSON non-deterministic and weakens
     any content hash or cache key derived from it.
   - Suggested fix: Use `List<NodeKind>`/`List<EdgeKind>` with uniqueness
     validation and a defined order, or a sorted set; add a contract test that
     asserts the encoded order.

5. **`protocolVersion` is optional on input**

   - Location: `analysis-engine/api/src/main/kotlin/dev/codenb/api/GraphInfo.kt:13`
     and `Protocol.kt:3`.
   - Problem: Because `protocolVersion` has a default, JSON that omits the field
     decodes as the current version. The codec is otherwise strict
     (`ignoreUnknownKeys = false`), but a missing version field is silently
     accepted, so version negotiation cannot detect a malformed or older
     initialize result.
   - Suggested fix: Make `protocolVersion` required on decode (remove the
     default) and validate that it equals `PROTOCOL_VERSION`, or add an explicit
     decode check. Keep `encodeDefaults = true` so it is always written.

6. **Error codes are unconstrained strings**

   - Location: `analysis-engine/api/src/main/kotlin/dev/codenb/api/EngineError.kt:7`.
   - Problem: `EngineError.code` is any non-blank string and `details` is an
     untyped string map, although errors are part of the frozen socket contract.
     The architecture already names `unsupported-capability`; without a defined
     set, a renderer must hard-code strings and a typo or rename is
     undetectable.
   - Suggested fix: Introduce an `EngineErrorCode` enum or value type with the
     v1 codes, retaining an explicit `UNKNOWN`/extension path if forward
     compatibility is required, and add contract tests for the v1 codes.

### Concur

- `NodeKind` (`ENTITY`, `FILE`, `GROUP`), `GroupingKind` (`DIRECTORY`,
  `NAMESPACE`), and `EdgeKind` (`CONTAINS`, `REFERENCES`, `FILE_REFERENCES`,
  `GROUP_REFERENCES`) match the model. `GraphInfo.groupingKind` correctly makes
  grouping a per-graph choice.
- Edges are canonically stored as `(kind, min, max)`: `Edge.of` and
  `EdgeKey.of` agree, reversed endpoints produce the same key, and `Edge`,
  `EdgeKey`, and `EdgeOrigin` all reject self-edges. This correctly suppresses
  self-edges at every level.
- `EdgeOrigin` keeps absolute `from`/`to` direction alongside a count, so the
  canonical edge can remain undirected for navigation while retaining directed
  observations. The intent matches the model; only the count/origin
  relationship in Finding 1 is under-specified.
- `Expansion` carries the graph id, revision, requested node, neighbours, and
  connecting edges, which matches the node-local expansion shape in the
  architecture (`docs/architecture.md:155-160`).
- `GraphJson` is strict and explicit: defaults and nulls are encoded, unknown
  keys are rejected, input is non-lenient and non-coercing, and there is no
  polymorphic guessing. The round-trip, null, unknown-key, edge-key, and
  self-edge tests cover the requested contract cases.
- `Node` includes the model's required `id`, `kind`, `label`, optional `span`,
  and `parent`, plus `unstable`; `GraphInfo` carries the graph, root, LSP id,
  grouping, and protocol version; `Expansion` carries the content revision.
  Finding 2 concerns the remaining identity fields, not these required ones.
- The api module has no LSP, filesystem, renderer, or storage dependency; it
  imports only `kotlinx.serialization`. `GraphInfo.root` and `Node.file` are
  path data required by the model/architecture, not filesystem APIs.
- The validation that is present is sensible and not over-constraining: blank
  ids, non-positive counts, non-canonical endpoints, mismatched origins,
  negative span coordinates, and reversed spans are all rejected. The remaining
  gaps are the ambiguities listed above, not excessive strictness.
- The api source and tests contain no comments.

### Dissent

- Finding 1: reference edge `count` and `origins` must be made consistent, and
  direction metadata must not be optional for reference edges.
- Finding 2: node identity fields must either expose the full model identity
  (`fileUri` plus signature fingerprint) or stop implying a reconstructable
  identity beyond the opaque id.
- Finding 3: the serialization runtime is part of the contract's public surface
  and must be exported as an `api` dependency.
- Finding 4: `GraphInfo` must encode kind sets in a defined order.
- Finding 5: `protocolVersion` must be required on input, not defaulted.
- Finding 6: engine error codes must be a defined v1 set rather than free-form
  strings.

## Round 2

**Verdict:** Approved. All six Round 1 findings are addressed, and the fixes
introduce no new contract problems.

### Finding disposition

- **Finding 1 — Concur (addressed).** `Edge` now rejects duplicate directed
  origins, requires at least one origin for `REFERENCES`, `FILE_REFERENCES`,
  and `GROUP_REFERENCES`, and requires `count` to equal the sum of origin counts
  whenever origins are present (`Edge.kt:60-67`). `CONTAINS` may still omit
  origins, which is consistent with the model's reference-count intent.
  `EdgeContractTest` covers missing reference origins, duplicate origins, and
  count mismatches.
- **Finding 2 — Concur (addressed).** `Node` now exposes `fileUri` and
  `signatureFingerprint` (`Node.kt:11-12`) and enforces the per-kind
  requirements: `ENTITY` requires file URI, qualified name, and fingerprint;
  `FILE` requires file URI; `GROUP` requires neither
  (`Node.kt:19-27`). It also rejects a self-parent. `NodeContractTest` covers
  the missing-field cases and complete nodes.
- **Finding 3 — Concur (addressed).** The api module now declares
  `kotlinx-serialization-json` with the `api` configuration
  (`analysis-engine/api/build.gradle.kts:6`), so consumers of `:api` inherit
  the serialization runtime exposed by `GraphJson`.
- **Finding 4 — Concur (addressed).** `GraphInfo` uses `List<NodeKind>` and
  `List<EdgeKind>` and validates uniqueness and enum order
  (`GraphInfo.kt:11-21`). `GraphInfoTest` covers unsorted and duplicate lists,
  and `WireFormatTest` pins the encoded order.
- **Finding 5 — Concur (addressed).** `protocolVersion` no longer has a default
  (`GraphInfo.kt:13`), so a missing field fails decoding, and the initializer
  requires the value to equal `PROTOCOL_VERSION` (`GraphInfo.kt:22`).
  `GraphInfoTest` covers both the missing and mismatched cases.
- **Finding 6 — Concur (addressed).** `EngineErrorCode` is a serializable enum
  with explicit stable `@SerialName` values, and `EngineError.code` is that enum
  (`EngineError.kt:6-33`). `WireFormatTest` pins every v1 serial name, and
  `ContractJsonTest` round-trips every code.

### New findings

None. The changed Node fields, edge aggregate validation, `GraphInfo` list
validation, required protocol version, enum error codes, and `api` dependency
exposure are consistent with the neighbour model and do not introduce a new
contract ambiguity.
