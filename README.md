# Code Neighbours

> See what lives next to your code.

Code Neighbours builds a reference graph from a codebase with LSP and lets you
walk it. An entity's neighbours are the file that contains it, its directory or
namespace, and every entity that references it or is referenced by it.

## Human Note

Unsurprisingly most of this is vibecoded. I had the idea, but other then that,
there is not much work by me. This note here is the last Human-written lines in
this repository.

## Components

| dir                                   | what                                                                                                                                                 |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| [`analysis-engine/`](analysis-engine) | Kotlin/JVM engine. LSP client, entity discovery, lazy neighbour expansion, graph cache, Unix-socket daemon, CLI, Graphviz export.                    |
| [`renderer/`](renderer)               | Kotlin renderer split into a JVM HTTP server and a Kotlin/JS browser app. The server talks to the engine over a Unix socket; the browser talks HTTP. |
| [`docs/`](docs)                       | Architecture, neighbour model, workflow, tickets, and review reports.                                                                                |

## Flow

```
seed entity
     │
     ▼
LSP definitions / references / semantic tokens
     │
     ▼
per-LSP neighbour graph (entity, file, directory or namespace)
     │
     ├──► Graphviz dot
     │
     └──► Unix socket ──► renderer server ──► HTTP ──► Kotlin/JS graph view
```

The graph is expanded lazily from the seed. Each visited node is cached. There
is no live update loop; a new session starts from a new seed.

## Docs

- [Architecture](docs/architecture.md)
- [Neighbour model](docs/neighbour-model.md)
- [Workflow](docs/workflow.md)
- [Tickets](docs/tasks/README.md)
- [Reviews](docs/reviews/README.md)
- [Manual test](docs/manual-test.md)
