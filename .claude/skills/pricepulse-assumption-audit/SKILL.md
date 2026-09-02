---
name: pricepulse-assumption-audit
description: Audit unverified codebase assumptions before a non-trivial PricePulse implementation plan. Use for feature work, bug fixes, interface changes, time-dependent behavior, persistence, HTTP contracts, or test strategy; skip routine documentation-only edits.
---

# PricePulse Assumption Audit

## Purpose

Prevent implementation plans from presenting unverified repository facts as
instructions. This skill complements PricePulse documentation and Superpowers
planning; it does not replace either one.

Use it after reading the relevant PricePulse documentation and before presenting
a plan for a non-trivial code change.

## Required evidence

For every plan assumption that names an existing type, collaborator, contract or
test behavior, inspect the source and record the evidence needed to safely plan
the change.

### Construction and dependencies

Verify, rather than infer:

- the actual construction path and visibility of named types, including private
  constructors and companion `invoke` factories;
- injected collaborators such as clocks, dispatchers, id generators, random
  sources and configuration; and
- the concrete values used by production and test wiring when time or state
  affects behavior.

Do not prescribe `Instant.now()` or another live dependency when the code already
uses an injected, controllable collaborator. Do not prescribe a callable
reference until its compilation path and validation behavior are confirmed.

### Interface impact

Before adding or changing a method, search for all affected:

- production implementations;
- adapters and decorators;
- fakes, recording stores and other test doubles;
- dependency-injection bindings; and
- direct callers and contract tests.

List every affected implementation in the plan. If the search cannot establish
the complete set, add a discovery task instead of claiming that all implementers
will compile.

### Preconditions and tests

When a planned change introduces or relies on a precondition, determine whether
existing tests cover both the accepted and rejected paths. Include a focused
negative test when the rejected input is meaningful to the contract.

For time-sensitive behavior, derive test cutoffs from the same injected clock or
controlled time source that the production code uses. Never make a test depend on
the wall clock unless real elapsed time is the behavior being tested.

## Plan output

Add a compact **Assumption audit** section before the implementation steps:

| Assumption | Evidence inspected | Planning consequence |
| --- | --- | --- |
| Example: operation age uses injected clock | `OperationStore`, production binding, `MutableClock` test fixture | Calculate cutoffs from injected time in production and tests |

Then ensure the plan:

- names every file expected to change, including test doubles;
- distinguishes confirmed facts from unresolved questions;
- contains tests for new preconditions and important failure paths; and
- uses the exact observed construction and dependency paths.

Do not add a separate artifact for a small audit unless the requested workflow
already requires a specification, ADR or plan file.

## Mismatches found during implementation

If implementation disproves a plan assumption:

- make a local, necessary correction when it preserves approved behavior,
  contracts and scope;
- record the deviation with the evidence, files affected and added/updated tests;
- update the remaining plan before continuing; and
- stop for approval if the correction changes a product decision, public contract,
  persistence model, security boundary or architectural responsibility.

A deviation is not a failure when it replaces a disproved assumption with evidence.
Repeated deviations in the same area mean the next plan needs a deeper audit.
