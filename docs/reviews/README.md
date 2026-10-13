# Review reports

Periodic architecture and security reviews are stored here, one file per review:

`YYYY-MM-DD-<milestone>-architecture.md` / `YYYY-MM-DD-<milestone>-security.md`

They are produced by the `architecture-reviewer` / `security-reviewer` agents (`.claude/agents/`). Every actionable finding gets a GitHub issue labeled `review-finding`, linked from the report. The owner fills in the "residual risks accepted" section.

Per-PR code and security reviews are **not** stored here. They live as comments on the PR, and their status is tracked in the PR body's *Review log* table.
