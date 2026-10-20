# Reviews

One directory per ticket: `docs/reviews/<ticket-id>/<role>.md`, one file per
reviewer per ticket. See [../workflow.md](../workflow.md) for the pipeline and
the review dimensions.

A report opens with `## Round 1` and a verdict, then findings (location, problem,
suggested fix) and a concur/dissent list, then a `## Round 2` section only when
round 1 produced findings. Findings carry no severity labels; a finding is work.
Reviewers never re-report an addressed finding.
