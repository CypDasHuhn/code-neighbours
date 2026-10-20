## Round 1

**Verdict:** Changes requested.

The scaffold has the right overall shape: two independent Gradle roots, the six
engine modules, the renderer shared/server/web split, Kotlin 2.4.10, JVM 21
targets, and a wired Kotlin/JS browser distribution. The findings below are
about build reproducibility and the repository/build interface rather than the
module boundaries.

### Findings

1. **Kotlin/JS lockfile is ignored**

   - Location: `.gitignore:4` (`kotlin-js-store/`), with
     `renderer/kotlin-js-store/yarn.lock` present in the tree.
   - Problem: The ignore rule excludes the Gradle-generated yarn lockfile.
     That file pins the Kotlin/JS/webpack toolchain dependencies for
     reproducible builds. Ignoring it means a fresh clone resolves dependency
     versions afresh and the lockfile can never be committed. The ticket asked
     for ignores for generated build output and node artifacts, not for the
     lockfile.
   - Suggested fix: Remove `kotlin-js-store/` from `.gitignore`, keep
     `node_modules/` and generated `build/` output ignored, and commit
     `renderer/kotlin-js-store/yarn.lock`. Keep `.kotlin-js-store/` only if that
     legacy directory is actually generated.

2. **JDK 21 is configured non-portably and inconsistently**

   - Location: `renderer/gradle.properties:3`
     (`org.gradle.java.installations.paths=/usr/lib/jvm/java-21-openjdk`) and
     `analysis-engine/build.gradle.kts:18-27` (JVM target and Java compatibility
     only, with no toolchain).
   - Problem: The renderer pins an absolute JDK path that exists only on this
     machine and distribution, so the build is not portable. The engine has no
     toolchain declaration, so it silently uses whatever JDK launches Gradle and
     only works when that JDK is 21. The two builds should express the same JDK
     21 requirement in the same portable way.
   - Suggested fix: Add `jvmToolchain(21)` (or an equivalent Java toolchain
     block) to the engine root/subprojects, remove the absolute installation
     path from `renderer/gradle.properties`, and let Gradle auto-detect or
     provision the toolchain. If a local path must be overridden, source it from
     an environment or CI setting rather than a checked-in absolute path.

3. **`lint` is a no-op**

   - Location: `justfile:25-26`.
   - Problem: `just lint` only echoes a message and exits successfully. It
     cannot enforce the ticket's no-comments rule or any style, and a green lint
     result gives the caller no signal. The repository `.editorconfig` already
     contains ktlint settings, so the intent to lint exists.
   - Suggested fix: Wire ktlint (or an equivalent formatter/comment check) into
     both builds and have `lint` run it. If linting is intentionally deferred,
     make the recipe fail with a clear message and name the deferring ticket
     instead of silently succeeding.

4. **Documented `just test-lsp` recipe is absent**

   - Location: `docs/workflow.md:61-62` compared with `justfile`.
   - Problem: The workflow names `just test-lsp` as an engine command, but the
     justfile defines no such recipe, so the documented command fails. The `lsp`
     module already has a placeholder test, so this is currently a one-line gap.
   - Suggested fix: Add `test-lsp: cd analysis-engine && ./gradlew :lsp:test`,
     or defer it explicitly to ticket 003 and update the workflow wording so the
     documented commands match the justfile.

### Concur

- `analysis-engine/` and `renderer/` are separate Gradle roots with their own
  wrappers and no shared source module.
- The engine module set is complete (`api`, `core`, `lsp`, `frontend`, `cli`,
  `server`) and the dependency direction follows the architecture: `api` is the
  base, `core` builds on it, `lsp`/`frontend` provide language facts, and
  `cli`/`server` are hosts.
- The renderer split is sensible for the boundary: `shared` is Kotlin
  Multiplatform JVM + JS, `server` is JVM/Ktor, and `web` is Kotlin/JS IR
  browser code depending on `shared`.
- Kotlin 2.4.10 is declared in both builds, JVM 21 is targeted, and the
  Kotlin/JS browser distribution is wired through `jsBrowserDistribution`,
  `web.js`, and the copied `index.html`.
- The required `build-engine`, `test-engine`, `build-renderer`, `test-renderer`,
  `test`, and `lint` recipes exist and are runnable.
- Placeholders are appropriate: no graph, LSP, socket, HTTP, or UI domain logic
  has leaked into the scaffold, and the authored source/build files contain no
  comments.

### Dissent

- Finding 1: the yarn lockfile under `kotlin-js-store/` must not be ignored.
- Finding 2: JDK 21 must be configured portably and consistently across both
  builds.
- Finding 3: `lint` must perform a real check rather than always succeeding.
- Finding 4: the documented `just test-lsp` command must exist or the workflow
  must be corrected.

## Round 2

**Verdict:** Approved. All four Round 1 findings are addressed, and the fixes
introduced no new architecture or contract problems.

### Finding disposition

- **Finding 1 — Concur (addressed).** `.gitignore` no longer contains
  `.kotlin-js-store/` or `kotlin-js-store/`. `git check-ignore` reports nothing
  for `renderer/kotlin-js-store/yarn.lock`, so the lockfile is trackable, while
  `node_modules/` and `build/` remain ignored.
- **Finding 2 — Concur (addressed).** The absolute
  `org.gradle.java.installations.paths` entry is gone from
  `renderer/gradle.properties`. The engine now declares a Java toolchain at
  `analysis-engine/build.gradle.kts:23-27`, and `renderer/shared` and
  `renderer/server` retain `jvmToolchain(21)`. No absolute JDK path remains in
  build configuration; the only occurrence is the historical Round 1 finding
  text in this report.
- **Finding 3 — Concur (addressed).** Both builds apply ktlint 14.2.0 to their
  subprojects, and `justfile:28-30` runs `ktlintCheck` in both roots. Existing
  ktlint reports cover the engine and renderer modules and are empty, so the
  check is real and passed for the current sources.
- **Finding 4 — Concur (addressed).** `justfile:25-26` defines `test-lsp` and
  runs `./gradlew :lsp:test`, matching the workflow's documented engine command.

### New findings

None. The changes are build-tooling and configuration changes only. Module
boundaries, the engine/renderer separation, the renderer shared/server/web split,
the Kotlin/JS distribution, and the socket/HTTP boundary are unchanged.
