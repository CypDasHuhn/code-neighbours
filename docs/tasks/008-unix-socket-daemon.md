---
name: Unix socket daemon
status: todo
depends-on: [002, 006]
reviewers: [api-fidelity, correctness]
---

# 008 — Unix socket daemon

## Goal

Expose one per-project, per-LSP graph over a Unix domain socket with NDJSON
JSON-RPC.

## Scope

- Socket path derived from `XDG_RUNTIME_DIR` (or `/tmp`), uid, root, and LSP id.
- Directory `0700`, socket `0600`.
- Methods: `initialize`, `graph/open`, `node/expand`, `node/get`, `graph/dot`,
  `shutdown`.
- Request ids, error objects, recoverable `cint/error` notifications.
- Per-project daemon lifecycle, stale socket cleanup, concurrent clients, and
  serialized LSP access.
- `--root`, `--lsp`, `--socket`, `--config` flags.
- Tests over a real Unix socket: framing, concurrent requests, unknown method,
  malformed request, shutdown, and permissions.

## Acceptance criteria

- Two clients can query the same daemon without corrupting responses.
- Malformed NDJSON produces an error response and does not kill the server.
- Shutdown removes the socket.
- `just test-engine` passes.
- No comments.

## Out of scope

HTTP, browser assets, live updates, and renderer navigation.

## Notes

The daemon is reused across sessions. The renderer server may start it on demand.
