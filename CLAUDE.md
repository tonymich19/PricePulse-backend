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

### CURRENT_IMPLEMENTED_CAPABILITIES

These exist in this repository today and are the only ones you may assume when
reading or changing code:

- authenticated receipt analysis;
- credits;
- provider invocation;
- the existing versioned HTTP boundary.

`contracts/openapi.json` and `contracts/receipt-analysis-result.v1.schema.json`
are the canonical wire-format sources. A cross-repository specification may
describe behavior, but it does not replace or duplicate these contracts.

### APPROVED_BUT_GATED_CAPABILITIES

Approved at GATE 1 on 2026-09-16 by ADR-013, ADR-014 and ADR-015 and the
specification `price-intelligence-foundation-v1`, all under
`../PricePulse/docs/product-development/`. **None of this is implemented.** Do not
read the documents as description of existing code, and do not write code that
assumes any of it exists:

- Price Intelligence Foundation (`application/priceintelligence`);
- the backend-owned canonical `PriceObservationStore`;
- the MarketProduct catalog and its canonical `ProductKey`;
- `Merchant`, `Store` and `PriceRegion`;
- `UserTrackedProduct`.

**Implementation is gated by the M4 milestone gate** in
`../PricePulse/docs/product-development/roadmap.md`. No slice of this capability
may begin until the product owner closes Milestone 4 through
`pricepulse-milestone-gate`. An approved ADR or specification is not permission to
implement.

Every price source investigated so far remains `COMMERCIAL_USE_NOT_VALIDATED`. A
source in that state may never serve production data; commercial status is a
fail-closed gate, and an absent value denies activation.

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
