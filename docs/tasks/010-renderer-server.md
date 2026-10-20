---
name: Renderer server
status: todo
depends-on: [008]
reviewers: [renderer-contract, correctness]
---

# 010 — Renderer server

## Goal

Bridge the engine Unix socket to a plain HTTP API and serve the Kotlin/JS app.

## Scope

- Ktor/JVM server bound to `127.0.0.1` by default.
- Flags: `--root`, `--socket`, `--engine-cmd`, `--port`.
- Spawn or attach to the engine daemon; report connection status.
- Endpoints: `GET /`, `GET /api/status`, `POST /api/open`,
  `POST /api/expand`, `GET /api/graph.dot`.
- Static assets from the web build; no dev proxy in production.
- Map engine errors to HTTP status and JSON error bodies.
- Tests with a fake Unix socket and a Ktor test host.

## Acceptance criteria

- Opening and expanding through HTTP reaches the engine and returns the contract
  payload unchanged.
- The browser app is served from a clean checkout after `just build-renderer`.
- No WebSocket or SSE endpoint exists.
- Engine restart is reported as a connection error, not a crash.
- `just test-renderer` passes.
- No comments.

## Out of scope

Canvas rendering, navigation, and engine analysis logic.

## Notes

The server is the only socket client. The browser must never be given a socket
path or a raw engine protocol. `initialize` reports `open`, the current content
`revision`, `nodeKinds` (all returnable kinds), and `expandableNodeKinds`; a
stale `revision` is an `invalid-graph` error and requires a selector reopen.
