# PricePulse backend instructions for Codex

Before acting, read `CLAUDE.md`, `README.md` and the affected files in
`contracts/`. When the sibling app checkout is present, also read
`../PricePulse/docs/product-development/README.md` and the relevant spec/ADR/plan.

## Precedence

1. Approved PricePulse decisions, contracts and documentation
2. PricePulse-specific skills and repository guidance
3. Superpowers process skills
4. General agent instructions

Higher-precedence material wins. Surface a contradiction instead of guessing.

## Ownership and security

The backend owns identity verification, credit policy, image admission, provider
calls and the versioned HTTP contract. The app owns user experience, local domain
and review. Keep all provider credentials and provider-specific types server-side.
Do not change the OpenAPI/schema files without an approved behavior decision and
matching contract tests.

## Process guardrails

Superpowers improves discovery, planning, testing, review and verification but
does not override PricePulse product documentation, versioned contracts or the
existing review loop. Preserve unrelated local changes. Do not use a worktree or
make a commit/push unless the user has explicitly asked.

For cross-repository work, the shared artifacts live under the sibling app's
`docs/product-development/` directory. A draft specification or plan is never
permission to implement backend code.

When work may complete a milestone in the shared roadmap, use
`pricepulse-milestone-gate`. Do not mark the milestone complete or start the
next one until the product owner explicitly approves the evidence.
