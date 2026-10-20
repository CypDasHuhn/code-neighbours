---
name: Repository and build scaffold
status: done
depends-on: []
reviewers: [architecture]
---

# 001 — Repository and build scaffold

## Goal

Create the two Gradle builds and module skeletons so every later ticket has a
home and the tree is green from the start.

## Scope

- `analysis-engine/` Gradle root, Kotlin/JVM 21, Kotlin 2.4.10, modules:
  `api`, `core`, `lsp`, `frontend`, `cli`, `server`.
- `renderer/` Gradle root with modules: `shared` (Kotlin Multiplatform JVM + JS),
  `server` (Kotlin/JVM, Ktor), `web` (Kotlin/JS IR, browser).
- Gradle wrappers for both builds.
- Root `justfile` recipes: `build-engine`, `test-engine`, `build-renderer`,
  `test-renderer`, `test`, `lint`.
- `.gitignore` additions for Kotlin/JS build output and node artifacts.
- Root README and docs links stay accurate.

## Acceptance criteria

- `just build-engine` succeeds.
- `just build-renderer` succeeds, including the Kotlin/JS browser bundle.
- `just test` runs the empty/placeholder test suites successfully.
- Both builds have wrappers and do not depend on a globally installed Gradle.
- No module contains domain logic beyond a buildable placeholder.
- No comments in source files.

## Out of scope

Graph types, LSP calls, daemon behavior, HTTP endpoints, and UI.

## Notes

The engine and renderer are separate builds. They share no source module; the
socket and HTTP contracts are the boundary. The renderer `shared` module holds
only renderer-side transport DTOs.
