---
name: pricepulse-milestone-gate
description: Pause when PricePulse work may complete a roadmap milestone, present the evidence, and ask the product owner whether to mark it done before planning or starting the next milestone. Use only for a potentially completed milestone, not an ordinary slice within one.
---

# PricePulse Milestone Gate

## Purpose

Keep delivery milestones under product-owner control. This skill complements
the roadmap, specifications, plans and Superpowers verification; it does not
replace any of them.

Use it after implementation and verification when the work may satisfy the
exit criterion of a milestone in
`../PricePulse/docs/product-development/roadmap.md`. Do not use it merely
because a small task, plan step or repository-specific slice has ended.

## Check the milestone with evidence

1. Read the active milestone and its exit criterion in the roadmap, plus its
   referenced spec, ADR and contract when applicable.
2. Compare the actual evidence with every part of the exit criterion. A passing
   build, a local code change or completion in only one repository is not by
   itself evidence that a cross-repository milestone is complete.
3. State clearly which criteria are satisfied, which are missing, and any
   known limitation, deviation or production dependency. Never infer a real
   provider or device result from unit tests.

## Owner decision gate

If the evidence could complete the milestone, stop and ask this exact
decision in concise terms:

> Com base na evidência apresentada, você considera o Marco <número e nome>
> concluído e autoriza registrar sua conclusão antes de iniciarmos o próximo
> marco?

Do not mark the milestone complete, plan the next milestone, modify its spec,
or begin its implementation until the product owner gives an explicit answer.

- If the owner approves, record the approval and the supporting evidence in
  the roadmap according to the project’s documentation conventions. Then begin
  discovery for the next milestone; use brainstorming only for decisions that
  remain open, followed by the normal spec/ADR/plan flow.
- If the owner declines or asks for more evidence, keep the current milestone
  active. Clarify the missing work and do not advance the roadmap.

## Boundaries

- Never let a process skill, a task list or an agent’s confidence override an
  approved product decision or a versioned contract.
- Do not merge, commit, push, deploy or make an external call merely because a
  milestone appears complete. Those actions retain their normal authorization.
- Never reopen accepted ADR decisions during the gate. Surface a genuine
  contradiction or new decision separately.
