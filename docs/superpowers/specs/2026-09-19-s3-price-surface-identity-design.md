# S3 — Price surface identity (`Merchant` / `Store` / `PriceRegion`) — Design

**Status: `APPROVED_BY_PO`** (2026-09-19, final spec review gate). Built on the product owner's decisions PO-S3-01 to
PO-S3-07 of 2026-09-19 (§3.2), which are normative and frozen. **S3 implementation: `NOT_STARTED`.**
Approval of this document authorizes no execution: implementation still needs a TDD plan approved by
the product owner, its preconditions and an explicit authorization.

Milestone 5, slice S3. **Backend only.** Parent documents, all approved and in the app repository
`../PricePulse`:

- delivery plan `docs/product-development/plans/price-intelligence-foundation-v1.md` §6 S3 (and §6 S4
  «Consome: S2, S3»);
- specification `docs/product-development/specs/price-intelligence-foundation-v1.md` §4, §6.1, §6.3,
  §6.6, §8 (invariants 4, 5, 10), §13;
- ADR-014 **D1–D8** (`Merchant`, `Store` and `PriceRegion` as explicit identities);
- ADR-013 (boundary of `PriceObservation`, consumer of this slice);
- ADR-015 **D1** (the backend owns the price-intelligence domain).

S1 and S2 are `COMPLETE`. S3 has no dependency on either: it does not use `MarketTextKey`,
`normalizePackageMeasure` or `ProductKey`. The M5 macro grill and the S3 deep grill of 2026-09-19
produced the findings reconciled here; the documents above were read at app commit `9ca8418`
(`docs/m5-s2-postmerge-reconciliation`) and backend `origin/main` `87182e5`.

---

## 1. Authority and sources

### 1.1 Precedence

1. The product owner decisions of §3.2 (later than, and refining, the parent plan and spec).
2. ADR-013, ADR-014, ADR-015 (normative; **not** edited by this slice, and not contradicted — §12.3).
3. The parent spec and the parent delivery plan. Where a PO decision departs from them, the departure
   is recorded in §12 as `PARENT_PLAN_RED_OVERRIDDEN_BY_PO` or `PARENT_CONTRACT_REFINED_BY_PO`; the
   parent documents are updated in a later documentation step, not by this slice.
4. Backend conventions observed on `origin/main` (§4.4), used for style only — never as authority for
   domain content.

### 1.2 Evidence used

| Evidence | What it establishes for S3 |
| --- | --- |
| POC A `evidence/carrefour-regionalization.md` | Carrefour prices per branch (`regionId` per branch, prices differ between two branches of the same city); branches identified through the official store selector with street/neighbourhood/city/UF |
| POC A `evidence/atacadao.md` | Atacadão prices per region: CEP → `regionId` → set of sellers (stores); distinct `regionId`s inside one metro carried identical prices; `sc=1` and `sc=2` return different catalogs and prices for the same location; 169 physical stores have no online surface |
| POC A `evidence/gpa-pda-extra.md` | Pão de Açúcar and Extra Mercado share one platform and are priced independently; GPA store scoping is inconclusive (`PRICE_SCOPE_UNKNOWN`) |
| POC A/B profiles | no investigated source exposes CNPJ |

No evidence exists in the repositories for any other platform (VipCommerce included); none is assumed.

## 2. Problem

`PriceObservation` (S4) states *where* a price applies: `merchantId`, `priceSurface`, `priceScope`
(parent spec §6.1) and `storeResolutionMethod` (§6.6). ADR-014 decided that `Merchant`, `Store` and
`PriceRegion` are explicit identities, that a `PriceRegion` is the surface that **determines** a price
(D2), that `priceScope` is declared, never inferred (D4), and that a `Store` is identified by evidence,
never by a similar name (D7).

The investigated sources use different granularities — Carrefour prices per branch, Atacadão per shared
region — and the failure S3 exists to prevent is a **fictitious or conflated surface**: a price
attributed to a surface that was not established, or two granularities treated as the same one
(parent spec invariant 5). S3 therefore gives S4 a closed, structural surface identity in which the
wrong states cannot be represented, and nothing more.

## 3. Scope

### 3.1 In scope

- `MerchantId` — opaque identity of a merchant;
- `StoreId` — merchant-scoped opaque identity of a store;
- `PriceRegionId` — merchant-scoped opaque identity of a price region;
- `PriceSurface` — closed sum of `StoreSurface(StoreId)` and `PriceRegionSurface(PriceRegionId)`,
  with computed `merchantId` and `scope`;
- `PriceScope` — `STORE`, `PRICE_REGION`, `CHAIN`, `UNKNOWN`;
- `StoreResolutionMethod` — provenance enum of the evidence that identified a store;
- construction invariants and structural equality;
- pure unit tests.

### 3.2 Product owner decisions (2026-09-19) — normative

| # | Decision |
| --- | --- |
| PO-S3-01 | `STORE` → `StoreSurface`; `PRICE_REGION` → `PriceRegionSurface`; `CHAIN` and `UNKNOWN` produce **no** `PriceSurface` and no `PriceObservation` in M5. No `ChainSurface`; `CHAIN` is never represented as a `PriceRegion`. Fail-closed for M5 |
| PO-S3-02 | S3 is **pure and minimal**: opaque ids, value objects, sealed types, invariants, structural relations, the resolution-method enum, and only the rules S4 needs. No registry, persistence, port, HTTP, adapter, source mapping, geography, person location, `ReferenceClass`, `ProductKey` resolution, `Purchase.storeName` link, CNPJ validation or membership history |
| PO-S3-03 | Identity model **B**: `StoreId(merchantId, value)` and `PriceRegionId(merchantId, value)` are merchant-scoped; `PriceSurface` holds only the id; `surface.merchantId` is derived from it. A change of banner/merchant is a new identity — accepted as fail-safe; no migration or history logic |
| PO-S3-04 | Store → PriceRegion membership is **not** in S3, in any form (§12.1) |
| PO-S3-05 | `StoreResolutionMethod` has **no** `UNRESOLVED` value (§12.2). An unresolved store is the outcome of a future resolution operation, not a value of a valid observation |
| PO-S3-06 | A `PriceRegion` is a canonical surface that determines a price; it may reflect a source dimension that is not strictly geographic (observed: Atacadão `sc=1`/`sc=2`). Documentation only — no field, no dimension enum, no vendor concept |
| PO-S3-07 | S3 defines **only structural equality**. No comparability predicate of any kind |

## 4. Non-goals

| Not S3 | Where it belongs / why |
| --- | --- |
| `Merchant`, `Store`, `PriceRegion` entities with attributes | no consumer in S4 (PO-S3-02) |
| Names, addresses, coordinates, CEP, UF, metro, city canonicalization | display metadata / geography — deferred (§13) |
| Store → PriceRegion membership (n:1, n:m, per source, over time) | deferred (PO-S3-04, §12.1) |
| Surface registry / catalog; minting of id values | future catalog (§13) |
| Mapping of source-native ids (`regionId`, `accountName`, `StoreId` of a source, `sc`) to canonical ids | S9 / catalog (§13); ADR-014 D8 |
| Store or region **resolution** (success/failure results) | S9 (PO-S3-05) |
| `PriceRegionResolutionMethod` | deferred to S4/S9 (§10.3) |
| CNPJ capture or validation | S9, if a source ever exposes one (§13) |
| Comparability, aggregation, reference class | S7/S8 (PO-S3-07) |
| `PriceObservation`, provenance record, `ObservationKey` | S4, S6 |
| Persistence, JDBC, migrations, HTTP, JSON Schema, serialization, string encoding of ids | S10, S12 |
| Person location, user store selection | S12 |
| `Purchase.storeName` → `Store` link | after M5 (ADR-014 D6) |
| Any change to S1/S2 code or contracts | protected (§15.3) |

## 5. Domain model

Package `com.tonycorreia.pricepulsebackend.application.priceintelligence.surface` (parent plan §4).
Conceptual Kotlin, **not authorized code**; final names are frozen at the plan's freeze point.

### 5.1 Files

| File | Holds |
| --- | --- |
| `surface/MerchantId.kt` | `MerchantId` |
| `surface/StoreId.kt` | `StoreId` |
| `surface/PriceRegionId.kt` | `PriceRegionId` |
| `surface/PriceSurface.kt` | `PriceSurface`, `StoreSurface`, `PriceRegionSurface` |
| `surface/PriceScope.kt` | `PriceScope` |
| `surface/StoreResolutionMethod.kt` | `StoreResolutionMethod` |

This is exactly the parent plan's «Cria» list for S3. The parent plan names one test file,
`surface/PriceSurfaceTest.kt`; the TDD plan may add test files (one per type), which is
`AGENT_DECIDABLE` (§16).

### 5.2 Types

```kotlin
/** Opaque identity of a merchant (a canonical banner/chain). Judged exactly as supplied. */
@JvmInline
value class MerchantId(val value: String) {
    init { require(value.isNotBlank()) }
}

/** Opaque identity of a physical store, scoped by its merchant. Complete identity on its own. */
data class StoreId(val merchantId: MerchantId, val value: String) {
    init { require(value.isNotBlank()) }
}

/**
 * Opaque identity of a price region, scoped by its merchant: a canonical surface that determines a
 * price. Not necessarily an administrative or geographic region. It is a canonical opaque identity:
 * it must not be defined by, derived automatically from, or assumed semantically equivalent to a
 * source-native region identifier. A coincidental equality of textual values has no domain meaning.
 */
data class PriceRegionId(val merchantId: MerchantId, val value: String) {
    init { require(value.isNotBlank()) }
}

/** The surface a price applies to. Exactly two forms in M5 (PO-S3-01). */
sealed interface PriceSurface {
    val merchantId: MerchantId   // computed from the contained id
    val scope: PriceScope        // computed from the variant

    data class StoreSurface(val storeId: StoreId) : PriceSurface {
        override val merchantId get() = storeId.merchantId
        override val scope get() = PriceScope.STORE
    }

    data class PriceRegionSurface(val priceRegionId: PriceRegionId) : PriceSurface {
        override val merchantId get() = priceRegionId.merchantId
        override val scope get() = PriceScope.PRICE_REGION
    }
}

/** Declared price granularity (ADR-014 D4). Only STORE and PRICE_REGION materialize as surfaces. */
enum class PriceScope { STORE, PRICE_REGION, CHAIN, UNKNOWN }

/** Evidence that identified a store (ADR-014 D7). Provenance, never identity. */
enum class StoreResolutionMethod { CNPJ, OFFICIAL_STORE_ID_PLUS_ADDRESS, OFFICIAL_LOCATOR }
```

Whether `StoreSurface`/`PriceRegionSurface` are nested in `PriceSurface` or top-level in the same file,
and the exact `require` messages, are `AGENT_DECIDABLE`. The shapes, fields, computed properties and
enum values above are frozen.

### 5.3 Id policy (applies to `MerchantId.value`, `StoreId.value`, `PriceRegionId.value`)

| Rule | Consequence |
| --- | --- |
| opaque `String` | no parsing, no structure inside the value, no UUID assumption |
| blank ⇒ invalid | `""` and whitespace-only values are rejected at construction. «Blank» is Kotlin `String.isBlank()` (`Char.isWhitespace`), so NBSP-only is blank; a ZWSP-only value is **not** blank and is accepted as supplied |
| no trimming | `"a "` is valid and different from `"a"` |
| case-sensitive | `"Loja1"` ≠ `"loja1"` |
| no Unicode normalization, no textual canonicalization | NFC and NFD spellings of the same text are different ids |
| no source-native semantics | the value is minted by a future catalog; S3 never derives it from a source identifier and attaches no meaning to it |
| no composed canonical string | `StoreId` is a pair, never `"merchant:store"`; no string form of any id exists in S3 |

The value is judged exactly as supplied, as S2 judged `gtin`, `brand` and `variant` (S2 SR-1, SR-3).
Accidental normalization — trimming, lowercasing, `Normalizer` — is forbidden and guarded (§15).

### 5.4 Why construction uses `require`

S3 has no entry point for external data: every id is built by a future catalog or by tests from
values that are already canonical. A blank value is therefore an internal invariant violation, guarded
with `require` in `init`, as `UserId`, `ContentHash` and `NormalizedPackageMeasure` already do. The
first slice that builds these ids from **external** data (S9 or the catalog) must validate before
construction and report expected failures as a result, never as an exception (S2 PO-11). That is a
constraint on the future slice, not a type S3 provides.

### 5.5 What the model deliberately does not contain

No `Merchant`, `Store` or `PriceRegion` entity; no name, address, CNPJ, coordinates, source id, native
region id or `sc`; no `priceRegionId` on `StoreId` and no `storeIds` on `PriceRegionId`; no resolution
method inside any id or surface; no `UNRESOLVED`; no `ChainSurface`; no comparability predicate; no
string form; no serialization annotation; no version field.

## 6. Invariants

| # | Invariant | Guaranteed by |
| --- | --- | --- |
| I-1 | Every id value is non-blank | `require` in `init` |
| I-2 | A `StoreId` and a `PriceRegionId` each belong to exactly one merchant | the `merchantId` field (ADR-014 D3, second clause) |
| I-3 | Every `PriceSurface` has a total `merchantId` | computed from the contained id |
| I-4 | Every `PriceSurface` has a total `scope`, and it is `STORE` or `PRICE_REGION` | computed per variant |
| I-5 | `StoreSurface.scope == STORE`; `PriceRegionSurface.scope == PRICE_REGION` | computed per variant; no stored scope, so no disagreement is representable |
| I-6 | No `PriceSurface` exists for `CHAIN` or `UNKNOWN` | the sealed type has no such variant |
| I-7 | A surface cannot be built from a name, an address or a source identifier | the only constructors take `StoreId` / `PriceRegionId` (ADR-014 D7: a similar name is never identity) |
| I-8 | `StoreResolutionMethod` never takes part in the identity of an id or a surface | it is a separate enum, not a field of either |
| I-9 | No vendor identifier appears in any type, field or package name | parent spec invariant 10; guard G-3 |

## 7. Equality semantics

| Rule | Consequence |
| --- | --- |
| `MerchantId` | equal iff `value` is equal, character for character |
| `StoreId` | equal iff `merchantId` **and** `value` are equal: the same `value` under two merchants is two stores |
| `PriceRegionId` | equal iff `merchantId` **and** `value` are equal |
| `StoreId` vs `PriceRegionId` | never equal, even with the same merchant and value (different types) |
| `StoreSurface` | equal iff the `StoreId`s are equal |
| `PriceRegionSurface` | equal iff the `PriceRegionId`s are equal |
| `StoreSurface` vs `PriceRegionSurface` | never equal, whatever the ids |
| `hashCode` | consistent with `equals`; generated by `value class` / `data class`, never hand-written |

**Only equality exists** (PO-S3-07). Two different surfaces of the same merchant — two stores, or a
store and a region — are unequal, and S3 says nothing else about them: not that they are comparable,
not that they are not. «Same merchant and same scope» is a necessary condition for comparability, not a
sufficient one, and deciding more belongs to S7/S8. The generated `toString()` is diagnostic output: it
is not an identity string and must never be parsed or persisted.

## 8. Fail-closed behavior

S3 represents insufficient input by **absence**: when a surface cannot be established there is no
`PriceSurface` value, and therefore no observation. No placeholder, «unresolved» or «unknown» surface
exists.

| Situation (future input, handled by S9/catalog) | What S3 offers | Consequence |
| --- | --- | --- |
| source declares `STORE` and the store is identified by an approved method | `StoreSurface(StoreId)` + a `StoreResolutionMethod` value | observation possible |
| source declares `PRICE_REGION` and the region is identified | `PriceRegionSurface(PriceRegionId)` | observation possible |
| source declares `CHAIN` (knows only the merchant) | nothing — no variant | no observation (PO-S3-01) |
| source declares `UNKNOWN` or scope unproven (GPA today) | nothing | no observation (PO-S3-01) |
| store cannot be identified by an approved method | no `StoreId`, hence no `StoreSurface` | no observation (PO-S3-05) |
| only a name or a similar domain is available | no constructor accepts it | no observation (ADR-014 D7) |
| blank id value | construction fails (`require`) | the future external-data slice must refuse before constructing (§5.4) |

## 9. `PriceScope` semantics

`PriceScope` keeps the four values ADR-014 D4 requires. `STORE` and `PRICE_REGION` are the only values a
`PriceSurface` ever reports. `CHAIN` and `UNKNOWN` exist so that a source can declare them in a future
slice (the source registry of S9, parent spec §7.3); in M5 a declaration of either leads to no surface.

`scope` is **computed** from the surface variant and never stored beside it. This satisfies ADR-014 D4
(«priceScope is an attribute of the observation and of the source; never derived from distance»): the
source's declared scope is what selects the variant, so the scope stays declared; it is merely not
stored twice. S4 exposes the observation's scope from its surface (§11).

## 10. `StoreResolutionMethod` semantics

### 10.1 Values

| Value | ADR-014 D7 wording | Evidence in the investigated sources |
| --- | --- | --- |
| `CNPJ` | «CNPJ da filial» (first preference) | none exposes it; kept because the ADR requires it |
| `OFFICIAL_STORE_ID_PLUS_ADDRESS` | «storeId oficial + endereço» | Atacadão: official `StoreId` with address in the store directory |
| `OFFICIAL_LOCATOR` | «store locator oficial com endereço compatível» | Carrefour: official store selector with street/neighbourhood/city/UF |

No other value exists. `UNRESOLVED` is removed (§12.2); no hypothetical value is added.

### 10.2 Semantics

- It is **provenance**: how a store identity was established for an observation. S4 carries it in
  `Provenance.storeResolutionMethod` (parent spec §6.6).
- It never participates in the equality or hash of `StoreId` or `PriceSurface`. The same store
  identified once by locator and once by CNPJ is the same `StoreId`.
- **No confirmation predicate is defined.** Every value is one of ADR-014 D7's approved means, so a
  predicate such as `isConfirmed` would be constantly true: dead code with no consumer. «Confirmed» is
  instead structural: a store that was not identified by an approved method has no value of this enum
  to carry (§12.2).

### 10.3 `PriceRegionResolutionMethod` — not in S3

The parent spec §6.6 and the parent plan's S4 provenance list name a `priceRegionResolutionMethod`, but
the parent plan's S3 «Cria» list does not include it and no value set is approved. It is **deferred to
S4/S9** (§13). The parent spec's sentence «`UNRESOLVED` é resposta válida» about that field is not
settled here; the slice that defines the field must reconcile it with the fail-closed rule of
PO-S3-01/PO-S3-05 (§12.4).

## 11. S3 → S4 contract (frozen)

**S4 can assume:**

1. `PriceSurface` has exactly two variants in M5: `StoreSurface` and `PriceRegionSurface`.
2. Every `PriceSurface` has a total `merchantId`.
3. Every `PriceSurface` has a total `scope`.
4. `surface.scope` is always `STORE` or `PRICE_REGION`.
5. There is no `CHAIN` surface.
6. There is no `UNKNOWN` surface.
7. Equality and hash of ids and surfaces are stable and structural (§7), usable inside a composite key.
8. `StoreResolutionMethod` is provenance, not identity, and has exactly three values.
9. `PriceRegionId` is a canonical opaque identity: it is not defined by, derived automatically from,
   or semantically equivalent to a source-native region id. A coincidental textual equality between
   the two has no domain meaning, and S3 has no rule against it.
10. S3 provides no comparability between different surfaces.

Consequences for S4's design (recorded, not decided here): the observation's `merchantId` and
`priceScope` can be read from `priceSurface` instead of being stored as independent fields, which keeps
the inconsistent pairs of parent spec §6.1 unrepresentable.

**S4 must not assume:** Store → PriceRegion membership; geography; display metadata; any source-native
mapping; the existence of `PriceRegionResolutionMethod`; a surface registry; a string form of any id;
that two unequal surfaces are comparable or incomparable.

## 12. Reconciliation with the parent contract

### 12.1 `PARENT_PLAN_RED_OVERRIDDEN_BY_PO` — Store → PriceRegion n:1

**Parent text.** Plan §6 S3 RED: «`Store → PriceRegion` é n-para-1»; parent spec §6.3: «`Store →
PriceRegion` é n-para-1».

**Decision (PO-S3-04).** The RED is not implemented in S3. S3 models no membership in any form:
no `Store.priceRegionId`, no `PriceRegion.storeIds`, no membership registry, no global n:1, no per-source
n:m.

**Why.** The Atacadão evidence does not support a global n:1: a CEP resolves to a `regionId` and a
seller set, distinct `regionId`s inside one metro carried identical prices, and `sc=1`/`sc=2` return
different prices for the same location — the price-determining surface is not the same thing as the
source's region key, and a store's membership may not be unique. S4 consumes no membership: an
observation carries one surface and nothing else.

**Status.** The concrete relation is `DEFERRED` (§13). ADR-014 D3 is not edited; see §12.3.

### 12.2 `PARENT_CONTRACT_REFINED_BY_PO` — no `UNRESOLVED` in `StoreResolutionMethod`

**Parent text.** Plan §6 S3 «Produz»: `StoreResolutionMethod` ∈ `CNPJ`, `OFFICIAL_STORE_ID_PLUS_ADDRESS`,
`OFFICIAL_LOCATOR`, `UNRESOLVED`; parent spec §6.6 lists the same four.

**Decision (PO-S3-05).** `UNRESOLVED` is removed from the S3 enum. A valid observation requires a
resolved surface; a store that could not be resolved yields no `StoreSurface` and no observation. If a
later slice (S9 in particular) needs to express «resolution failed», it does so in the **result of the
resolution operation** (success / failure), never as a value carried by a valid observation.

**Consequence for the parent RED «`Store` sem método de resolução aceitável não é confirmada».** It holds
structurally and needs no predicate: no unacceptable value exists, and an unidentified store has no
`StoreId`. The TDD plan proves it by the absence of the value and of any surface built without an id,
not by a boolean function.

### 12.3 ADR check — no normative conflict found

| ADR | Clause | S3 |
| --- | --- | --- |
| ADR-014 D1 | three explicit identities | `MerchantId`, `StoreId`, `PriceRegionId` — explicit and separate |
| ADR-014 D2 | `PriceRegion` is the price-determining surface, not geography | PO-S3-06 wording; no geography |
| ADR-014 D3 | «uma `Store` **pode** pertencer a uma `PriceRegion`» (n:1); a `PriceRegion` belongs to exactly one `Merchant` | the second clause is structural (I-2). The first is **not implemented, not contradicted**: S3 asserts nothing about membership. Any slice that later models membership must reconcile D3 with the Atacadão evidence, and an amendment of D3 would be a product-owner decision |
| ADR-014 D4 | `priceScope` declared, four values, never compared across chains as the same granularity | four values; scope computed from the declared variant (§9); no cross-surface rule exists in S3 (§7) |
| ADR-014 D5, D6 | `Purchase.storeName` unchanged; optional reversible link | untouched; link deferred |
| ADR-014 D7 | store identity by evidence, never by name | the three approved methods; no name constructor (I-7) |
| ADR-014 D8 | no source identifiers in domain types | guard G-3; no native id anywhere |
| ADR-013 | `PriceObservation` is the consumer | S3 does not depend on it |
| ADR-015 D1 | backend owns the domain | backend-only slice |

### 12.4 Other reconciliations

| Parent text | Reconciliation |
| --- | --- |
| Plan S3 acceptance: «o teste de "granularidades diferentes não se comparam" falha ao tentar comparar `STORE` com `PRICE_REGION`» | read under PO-S3-07 as: a `StoreSurface` and a `PriceRegionSurface` are never equal and never report the same scope. No comparability function exists to «try»; AC-9, AC-10 |
| Plan S3 RED «duas superfícies de escopo diferente não são comparáveis» | same reading; S3 guarantees inequality only |
| Plan S3 RED «nome parecido nunca produz identidade» | structural: no type has a name field or a name-based constructor (I-7, G-5) |
| Spec §6.1 `merchantId` and `priceScope` as fields independent of `priceSurface` | S3 makes both computable from the surface (§11); how S4 exposes them is S4's decision |
| Spec §6.6 `priceRegionResolutionMethod` («`UNRESOLVED` é resposta válida») | deferred with the type (§10.3); must be reconciled with PO-S3-01/05 by its owning slice |
| ADR-014 table: Carrefour's `PriceRegion` is «a própria filial» | Carrefour declares `STORE`, so its prices use `StoreSurface` (PO-S3-01); no `PriceRegionId` is materialized for a branch. The table remains a conceptual example |
| Macro grill «MerchantId derivable from every surface» | now demonstrable without a registry: the merchant is inside the id (PO-S3-03) |

## 13. Deferred decisions

| Decision | State | Owner / trigger |
| --- | --- | --- |
| Surface registry / catalog | `DEFERRED` — no owning slice | before S9/S10 need canonical ids |
| Native → canonical id mapping (`regionId`, `accountName`, source `StoreId`, `sc`) | `DEFERRED` | S9 / catalog |
| Minting policy of id values (and its versioning) | `DEFERRED` | catalog; S10 before persisting ids |
| Merchant catalog ownership and granularity (group vs banner, e.g. GPA vs Pão de Açúcar/Extra Mercado; Carrefour vs Atacadão) | `DEFERRED` | catalog |
| Geography (UF, metro, coordinates, city canonicalization) | `DEFERRED` — no owner | before S6's port / S8 |
| Store → PriceRegion membership | `DEFERRED` (PO-S3-04) | the first slice that needs it; ADR-014 D3 reconciliation |
| Membership history, banner change | `DEFERRED` (PO-S3-03) | after M5 |
| Display metadata (merchant, store, region names; addresses) | `DEFERRED` | catalog / S12 |
| CNPJ capture and validation | `DEFERRED` | S9, if a source exposes CNPJ |
| Person location / user's applicable surface | `DEFERRED` | S12 |
| `Purchase.storeName` → `Store` link | `DEFERRED` | after M5 (ADR-014 D6) |
| `PriceRegionResolutionMethod` (type, values, `UNRESOLVED` question) | `DEFERRED` | S4/S9 |
| Resolution result type (success/failure) | `DEFERRED` | S9 |
| Comparability / aggregation across surfaces | `DEFERRED` (PO-S3-07) | S7/S8 |
| Persistence, HTTP and string/serialized form of ids and surfaces | `DEFERRED` | S10/S12 — constraints in §13.1 |

### 13.1 Constraints on future persistence and HTTP (not a design)

- An encoding must keep the two surface kinds apart: a `StoreSurface` and a `PriceRegionSurface` with
  the same merchant and value are different.
- An encoding must keep the merchant inside `StoreId`/`PriceRegionId` equality.
- Storage and comparison must be **case-sensitive and exact** (no case-insensitive collation, no
  trimming, no normalization), or equality changes.
- No length bound is set by S3; one introduced by S10 must be a documented decision, not a truncation.
- Ids are opaque to consumers: an HTTP client (the app) must receive them as tokens it never parses.

## 14. Acceptance criteria

1. **AC-1 `MerchantId`.** Blank (`""`, spaces, NBSP-only) is rejected at construction; a non-blank value
   is kept exactly: no trimming (`"a "` ≠ `"a"`), case-sensitive (`"A"` ≠ `"a"`), no Unicode
   normalization (NFC ≠ NFD spelling); equal values are equal with equal hash.
2. **AC-2 `StoreId`.** Blank value rejected; the value is kept exactly (same cases as AC-1); two
   `StoreId`s with the same value and different merchants are unequal; equal merchant and value are
   equal with equal hash.
3. **AC-3 `PriceRegionId`.** Same as AC-2.
4. **AC-4 Id kinds.** A `StoreId` and a `PriceRegionId` with the same merchant and value are unequal.
5. **AC-5 `StoreSurface`.** `merchantId` equals the store id's merchant; `scope == STORE`.
6. **AC-6 `PriceRegionSurface`.** `merchantId` equals the region id's merchant; `scope ==
   PRICE_REGION`.
7. **AC-7 Surface equality.** Surfaces over structurally equal ids are equal with equal hash; over
   different ids (different value, or different merchant) are unequal.
8. **AC-8 Cross-kind.** A `StoreSurface` and a `PriceRegionSurface` built from the same merchant and
   value are unequal.
9. **AC-9 `PriceScope`.** Exactly the four values `STORE`, `PRICE_REGION`, `CHAIN`, `UNKNOWN` exist.
10. **AC-10 Materialization.** Over every `PriceSurface` variant, `scope` is `STORE` or `PRICE_REGION`;
    exhaustive `when` over `PriceSurface` has exactly two branches (the sealed hierarchy has two
    subtypes); no subtype reports `CHAIN` or `UNKNOWN`.
11. **AC-11 `StoreResolutionMethod`.** Exactly the values `CNPJ`, `OFFICIAL_STORE_ID_PLUS_ADDRESS`,
    `OFFICIAL_LOCATOR`; no `UNRESOLVED`.
12. **AC-12 Provenance is not identity.** No id or surface type has a field of type
    `StoreResolutionMethod`; the same `StoreId` used with two different methods yields equal surfaces.
13. **AC-13 Structural guards.** Every guard of §15.2 returns empty.
14. **AC-14 Protected paths.** Every path of §15.3 is byte-identical before and after S3.
15. **AC-15 Suite.** The full backend suite equals the pre-edit baseline plus the new tests, with no new
    failure (the baseline's known failures are the two Docker/Testcontainers tests,
    `PostgresReceiptAnalysisOperationStoreTest` and `PostgresSchemaContractTest`, to be re-captured on
    the branch before any edit).

## 15. Forbidden behavior and structural guards

### 15.1 Forbidden

A registry, repository, port or store; persistence or JDBC; HTTP or routes; serialization annotations or
libraries; geography or coordinates; source identifiers; vendor names; a dependency on `identity/`
(`ProductKey`, `MarketTextKey`, `UnitNormalization`) or on any future S4 package; a canonical or composed
string of an id; a comparability predicate; membership; `UNRESOLVED`; a `ChainSurface`; trimming,
case folding or normalization of id values; UUID generation.

### 15.2 Guards (run over `src/main/kotlin/.../application/priceintelligence/surface/`, case-insensitive
unless noted; each must return empty)

| # | Guard | Pattern (intent; the plan freezes the exact command) |
| --- | --- | --- |
| G-1 | no persistence / HTTP / serialization | `jdbc`, `java.sql`, `Connection`, `io.ktor`, `Route`, `Serializable`, `kotlinx.serialization`, `Json` |
| G-2 | no registry / port / repository | `Registry`, `Repository`, `Store(` as a type suffix other than `StoreId`/`StoreSurface`/`StoreResolutionMethod`, `interface .*Port`, `Map<.*Id` |
| G-3 | no vendor or source identifier (parent spec invariant 10) | `vtex`, `carrefour`, `atacadao`, `gpa`, `regionid`, `accountname`, `seller`, `sourceId` |
| G-4 | no geography | `latitude`, `longitude`, `coordinate`, `cep`, `postal`, `\buf\b`, `metro`, `city`, `geograph`, `address` |
| G-5 | no display metadata | `\bname\b`, `displayName`, `label`, `address` |
| G-6 | no S2 / S4 dependency | `import .*priceintelligence\.identity`, `import .*priceintelligence\.observation`, `ProductKey`, `MarketTextKey` |
| G-7 | no canonical string / accidental normalization | `trim`, `strip`, `lowercase`, `uppercase`, `Normalizer`, `override fun toString`, `asString`, `toKey`, `encode`, `UUID` |
| G-8 | no comparability predicate | `comparable`, `compareTo`, `sameGranularity`, `canCompare`, `equivalent`, `similar` |
| G-9 | no membership | `storeIds`, `priceRegionId` inside `StoreId.kt`, `membership`, `members` |
| G-10 | no `UNRESOLVED`, no chain surface | `UNRESOLVED`, `ChainSurface`, `UnknownSurface` |
| G-11 | no CNPJ validation | any `cnpj` occurrence other than the single enum constant `CNPJ` in `StoreResolutionMethod.kt` |

Patterns that collide with the frozen names (for example `Store` in `StoreId`) are refined in the plan so
that each guard tests its intent without false positives; a guard is never weakened to pass.

### 15.3 Protected paths — byte-identical (blobs on backend `origin/main` `87182e5`)

| Path | Blob |
| --- | --- |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/MarketTextKey.kt` | `3338193a18de468fb305edf9a21be5749af881ef` |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/UnitNormalization.kt` | `9323148d00dff697521a346815e5719b7a01b9d2` |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/Gtin.kt` | `3bcf6a11f72500b217450baaafe7215dee3b7f41` |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/AttributeSignature.kt` | `666021fdfa7d017b7f6c0f583c766c25f1b8332f` |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/ProductIdentityLevel.kt` | `d54aea7a0f42ac5521cd4636e643a5abae2d4a0c` |
| `src/main/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/ProductKey.kt` | `fc1285fdd735939416852da0508df292fd94c6d5` |
| `contracts/fixtures/text-key/parity-cases.v1.json` | `0e3f4f4f32a8382dae615e258cd10f40cb7ea1cc` |
| `contracts/fixtures/text-key/README.md` | `a17a83395cd089a7ac06d925b1862a4d5d857b81` |
| `src/test/kotlin/com/tonycorreia/pricepulsebackend/application/priceintelligence/identity/MarketTextKeyParityTest.kt` | `2493878791682634d55493746a2c282de8b2d84c` |

The plan re-verifies these against the `origin/main` commit the S3 branch is created from. Beyond them,
the plan's scope check allows changes only to the files of §5.1, their tests and the S3 plan/spec
documents; `contracts/`, `db/migration/`, `infrastructure/` and `openapi.json` are untouched.

## 16. Autonomy contract

Operational only; the harness itself is not specified here.

| Class | In S3 |
| --- | --- |
| `HUMAN_BLOCKER` | none known inside this frozen contract. Becomes one if a new finding changes a frozen domain type, value set, equality rule or the S4 contract; if a contradiction with an existing authority (ADR, parent spec/plan, contract) appears; or if a scope expansion is required: stop dependent work, continue only independent READY tasks |
| `HUMAN_NON_BLOCKING` | a new discovery that does **not** change the frozen types of §5.2 or the contract of §11 and contradicts no authority: record it in the decision queue and continue |
| `HUMAN_BEFORE_MERGE` | none known. Branch, commit, push, PR and merge still need the PO's explicit authorization |
| `AGENT_DECIDABLE` | file and test-file names; nesting of the surface variants; `require` messages; test organization and cases beyond the ACs; exact guard commands (§15.2, refined without weakening); mechanical implementation consistent with §5–§11 |

Expected human interruptions during implementation: **0**, unless one of the `HUMAN_BLOCKER` triggers
above occurs. Such a finding is never resolved silently and never classified lower (the agent never
lowers a decision's class).

**Auto-continue between tasks** is allowed when all hold: this spec and the TDD plan are
`APPROVED_BY_PO`; the previous task is GREEN; its targeted tests are GREEN; the full suite adds no new
failure relative to the recorded baseline; the diff is inside the declared file set; the protected paths
of §15.3 are unchanged; the guards of §15.2 pass; and no `HUMAN_BLOCKER` is pending.

**Checkpoints** (safe session boundaries): after the id types; after `PriceSurface`/`PriceScope`; after
`StoreResolutionMethod`; before the final verification. **Handoff state required** at each: base
commit, current task, decision queue with status, last suite evidence with the known-failure list,
protected-blob check result, guard results, and any scope deviation.

Branch, commit, push and PR still need the PO's explicit authorization; merge is `HUMAN_AUTHORITY`.

## 17. Open questions

None. The deep grill's questions Q1–Q5 are closed by PO-S3-03 to PO-S3-07, and this draft found no new
contradiction with a normative ADR (§12.3). ADR-014 D3's membership clause is recorded as not
implemented and to be reconciled by the future slice that models membership (§12.1, §13); that is a
deferral already decided by PO-S3-04, not a new question for S3.

## 18. Definition of done

1. This design and its TDD plan approved by the product owner.
2. The files of §5.1 and their tests exist in `surface/`, written test-first.
3. AC-1 to AC-15 pass; guards empty; protected paths unchanged.
4. Integrated into backend `main` through the normal PR flow, with the PO's authorization for branch,
   commit, push and PR.
5. **Then** S3 = `COMPLETE`. Updating the parent plan (the §12 overrides), the parent spec, the roadmap,
   `current-state.md` and backend `CLAUDE.md` is a separate documentation step.
