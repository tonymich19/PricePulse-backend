# PricePulse backend agent bootstrap

This file is the top-level entry point for Claude Code in the backend repository.
Before planning or changing anything, read this file, `README.md`, the relevant
versioned contract in `contracts/`, and — when the sibling app checkout is
available — `../PricePulse/docs/product-development/README.md`.

Before finalizing a non-trivial code plan, use `pricepulse-assumption-audit`.
When work may complete a milestone in the sibling app's
`docs/product-development/roadmap.md`, use `pricepulse-milestone-gate` before
planning or starting the next milestone.

## Decision hierarchy

Use this order whenever instructions or processes overlap:

1. Approved PricePulse decisions, contracts and documentation
2. PricePulse-specific skills and repository guidance
3. The Superpowers development process
4. General agent defaults

The higher level always wins. Do not silently reconcile a conflict: identify the
two sources and ask for a decision before implementation.

## Backend ownership

This repository owns authenticated receipt analysis, credits, provider invocation
and the versioned HTTP boundary. `contracts/openapi.json` and
`contracts/receipt-analysis-result.v1.schema.json` are the canonical wire-format
sources. A cross-repository specification may describe behavior, but it does not
replace or duplicate these contracts.

OpenAI and other provider secrets remain server-only. Never move a credential,
provider model identifier or provider response model into the mobile app.

## Superpowers usage

Superpowers supplies process, not product policy. Use brainstorming to refine an
unapproved design, writing-plans after design approval, and its testing, review
and verification practices during implementation. It must not override the
versioned contracts, security boundary, established review loop or approved
PricePulse decisions.

The working tree may contain unrelated local work. Preserve it. Do not create a
worktree, branch, commit or push unless the user explicitly asks for that action.

## Cross-repository delivery rule

For a feature shared with the app, begin with a specification under
`../PricePulse/docs/product-development/specs/`, an ADR for a durable decision,
and an approved plan under `../PricePulse/docs/product-development/plans/`.
Each repository is built and validated independently; a draft document never
authorizes a contract or source change.
