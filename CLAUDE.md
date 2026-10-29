# GifItUp — Claude Code additions

The tool-neutral working agreement lives in `AGENTS.md`: the hard rule, security rules, git & PR workflow, risk tiers and review process, architecture rules, environment notes and commands. Claude Code imports it into context automatically:

@AGENTS.md

**This file only adds what is specific to Claude Code and never overrides `AGENTS.md`; on any difference `AGENTS.md` wins.** `docs/session-brief.md` stays the session-start summary (hard rules, current milestone, pointers); in a Claude session this file and the brief are already in context — the import above makes `AGENTS.md` loaded too.

## Claude-specific mechanics

- **Reviewer agents:** the `code-reviewer` and `security-reviewer` subagents live in `.claude/agents/` and are launched as separate subagent sessions from Claude Code. Give a reviewer only the PR number — it must form its own view from the diff, not from the author's reasoning. Their files are the single source for the review checklists (*What to check*, *Focus areas*) and the Review log comment format. PRs authored by any other tool get the same reviews, launched from a Claude Code session.
- **Permission backstops:** the deny list in `.claude/settings.json` and the `pre-push` hook in `.githooks/` (refuses any push to `main`; Claude sessions turn it on via the SessionStart hook) back the hard rule in AGENTS.md; they are backstops, not its boundary. Never write allow rules into the gitignored `settings.local.json`; when a permission prompt appears, the owner never picks "don't ask again" (#98 SEC-12).
- **Attribution:** every GitHub post a Claude session creates carries the marker `_Posted by Claude Code via scripts/gh-rest.sh._` (or a "posted by Claude Code" line) where markup can't hide it — the first line of comments, and in PR bodies so `pr-edit` can later edit only PRs that carry it. Other agents mark their posts with their own tool's attribution (AGENTS.md, *AI agents on this repo*).
- **Cloud sessions (Claude Code on the web):** no GitHub GraphQL, so `gh pr create`/`comment`/`view` don't work — write only via `bash scripts/gh-rest.sh` as AGENTS.md's *Git & PR workflow* section specifies, read with GET-only `gh api`, and use the reduced test set from AGENTS.md's *Environment notes* (`./gradlew jvmTest :cli:test ktlintCheck detekt`, plus the `cargo` checks when `crypto/` changes; CI covers wasmJs, Android, `:server:test` and `:e2e:test`). Sessions with a local checkout and the full `gh` CLI use `gh pr create`/`gh pr comment` as before.
- **Git hooks:** Claude sessions enable `.githooks` automatically (SessionStart hook). Verify with `git rev-parse --git-path hooks` → `.githooks`; `git config` itself is denied for the agent.
