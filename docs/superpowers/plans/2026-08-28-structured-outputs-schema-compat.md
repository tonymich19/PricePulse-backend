# Structured Outputs Schema Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the derived provider schema satisfy OpenAI Structured Outputs, so `POST /v1/receipt-analyses` stops resolving to `FAILED_NO_PROVIDER` / `MALFORMED_REQUEST` and a receipt is actually analysed.

**Architecture:** Both fixes live entirely in `OpenAiStructuredOutputsReceiptAnalysisSchema`, the class that already exists to derive a provider-compatible variant of the canonical schema in memory. It gains two transformations beside the existing `oneOf` → `anyOf` rename: a `type` added to every `const` node, and a reviewed, lookaround-free replacement for the one `pattern` the provider rejects. The canonical contract file is not touched.

**Tech Stack:** Kotlin 2.2.10, Jackson 2.22.2 (`JsonNode`/`ObjectNode`), JUnit 5.

## Global Constraints

- **No contract change.** `contracts/receipt-analysis-result.v1.schema.json` and `contracts/openapi.json` must not be edited. Every fix is a derivation, in memory, for the provider only.
- **The canonical tree is never mutated.** `derive` deep-copies first; response validation (`ValidatedReceiptAnalysisResultV1`) keeps reading the untouched canonical schema.
- **Closed allowlists, loud failures.** This class's established discipline: an unexpected occurrence *and* a missing expected occurrence both fail before anything is mutated, so future canonical evolution becomes a review gate rather than a silent reinterpretation.
- **Build command:** `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew ...`
- **Do not commit, branch or push** unless the user explicitly asks.

## Assumption audit

Run with `.claude/skills/pricepulse-assumption-audit`. Every row was verified against the source or against the live provider; nothing here is inferred.

| Assumption | Evidence inspected | Planning consequence |
| --- | --- | --- |
| Exactly these two incompatibilities exist | Replayed the real production body against `POST /v1/responses`: first `invalid_json_schema` on a `const` node, then `regex lookaround is not supported`, then the error moved to billing (`429 credit_balance_exhausted`) | CONFIRMED. Two fixes, not more. The billing error proves schema validation passed. |
| 13 `const` nodes lack `type`, all string-valued | Walked `contracts/receipt-analysis-result.v1.schema.json`: `/properties/schemaVersion` plus 12 `status` nodes across the 4 `$defs` unions | CONFIRMED. A uniform `"type": "string"` is correct today; a non-textual `const` must fail loudly rather than be guessed. |
| Only `quantityField` uses lookaround | Same walk: one `pattern` contains `(?!`, none contains `(?=` | CONFIRMED. The allowlist has exactly one entry. |
| The replacement pattern is equivalent | Compared old and new against `1, 10, 0.5, 0.05, 1.352, 0, 0.0, 0.000, 01, 1.0, 0.10, .5, 1., 00.5` — identical accept/reject on all 14 | CONFIRMED. Equivalence is tested, not asserted; Task 2 keeps that comparison as a test. |
| `format: "date-time"` is accepted | Present in the body the provider accepted | CONFIRMED. Not touched by this plan. |
| `derive` currently changes *only* `oneOf`→`anyOf` | `OpenAiStructuredOutputsReceiptAnalysisSchemaTest:38`, `replacing anyOf back to oneOf makes the derived and canonical trees deeply identical` | **This test will fail** once `derive` adds `type` and rewrites a pattern. It is not collateral damage: it encodes the old contract of `derive`, and Task 1 must replace it with an assertion of the new one — the derived tree differs from the canonical in exactly the documented ways and no others. |
| Pointer shape depends on transformation order | `ALLOWLISTED_ONEOF_POINTERS` are `/$defs/x` (the union parent), while a pattern pointer must address inside a branch: `/oneOf/0/...` before the rename, `/anyOf/0/...` after | CONFIRMED. Both new transformations run **before** the rename, so every new pointer is expressed against the canonical `/oneOf/` shape. Stated explicitly in the code. |
| The static invariants test stays valid | `derived tree satisfies the static OpenAI-relevant invariants` checks root type, `additionalProperties`, `required`↔`properties`, internal `$ref` | CONFIRMED. Adding `type` to leaf `const` nodes touches none of those. |
| The canonical is also consumed for response validation | `ValidatedReceiptAnalysisResultV1` validates against the canonical resource | CONFIRMED. `derive` deep-copies, so the provider variant cannot leak into response validation. |
| Provider credit is a separate blocker | `429 credit_balance_exhausted`; that code is already in `BILLING_OR_QUOTA_ERROR_CODES` | CONFIRMED, and **out of scope**: no code change makes an analysis succeed until the OpenAI account has credit. This plan removes the schema rejection, nothing more. |

## File structure

| File | Responsibility |
| --- | --- |
| `infrastructure/openai/OpenAiStructuredOutputsReceiptAnalysisSchema.kt` (modify) | Gains `typeForConstNodes` and `rewriteUnsupportedPatterns`, both before the existing rename. |
| `test/.../openai/OpenAiStructuredOutputsReceiptAnalysisSchemaTest.kt` (modify) | Replaces the "identical apart from oneOf" test; adds coverage for both transformations and both loud failures. |

---

### Task 1: Every `const` node carries a `type`

**Files:**
- Modify: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai/OpenAiStructuredOutputsReceiptAnalysisSchema.kt`
- Test: `src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai/OpenAiStructuredOutputsReceiptAnalysisSchemaTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `derive` output in which every node having `const` also has `type`. No new public API.

**Why a general rule here, and an allowlist in Task 2.** A `const`'s own JSON type determines the `type` unambiguously — there is nothing to review, so a closed allowlist would add ceremony without adding a decision. A non-textual `const` is different: it has never existed in this schema, and guessing `"number"` versus `"integer"` for it would be exactly the silent reinterpretation this class exists to prevent. So: textual `const` gets `"string"`; anything else fails loudly.

- [ ] **Step 1: Write the failing test**

Replace the existing test named `replacing anyOf back to oneOf makes the derived and canonical trees deeply identical` with the two below — the first is its deliberate successor, stating the new contract of `derive`.

```kotlin
@Test
fun `the derived tree differs from the canonical only in the documented transformations`() {
    val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical).deepCopy<ObjectNode>()

    // Undo every transformation the derivation is allowed to make, then require byte equality
    // with the canonical: anything else that changed shows up here as a failure.
    undoAnyOfRename(derived)
    undoConstTypes(derived)
    undoPatternRewrites(derived)

    assertEquals(canonical, derived, "derive changed something it was never authorised to change")
}

@Test
fun `every const node in the derived tree declares a type, as Structured Outputs requires`() {
    val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical)

    val offenders = constNodesWithoutType(derived)
    assertEquals(emptyList(), offenders, "Structured Outputs rejects a schema node that has no type")
    // The canonical still has all 13, proving the transformation is on the copy only.
    assertEquals(13, constNodesWithoutType(canonical).size)
}

@Test
fun `a non-textual const fails loudly instead of being guessed`() {
    val tampered = canonical.deepCopy<ObjectNode>()
    (tampered.at("/properties/schemaVersion") as ObjectNode).put("const", 7)

    val failure = assertFailsWith<IllegalArgumentException> {
        OpenAiStructuredOutputsReceiptAnalysisSchema.derive(tampered)
    }
    assertTrue(failure.message!!.contains("/properties/schemaVersion"))
}
```

Add these helpers at the bottom of the test class, next to `countOccurrencesOfField`:

```kotlin
/** Pointers of every node that has `const` but no `type`. */
private fun constNodesWithoutType(node: JsonNode, path: String = ""): List<String> {
    val found = mutableListOf<String>()
    when {
        node.isObject -> {
            if (node.has("const") && !node.has("type")) found += path
            node.properties().forEach { (name, child) -> found += constNodesWithoutType(child, "$path/$name") }
        }
        node.isArray -> node.forEachIndexed { index, child -> found += constNodesWithoutType(child, "$path/$index") }
    }
    return found
}

private fun undoConstTypes(node: JsonNode) {
    if (node.isObject) {
        val objectNode = node as ObjectNode
        if (objectNode.has("const")) objectNode.remove("type")
        objectNode.properties().forEach { (_, child) -> undoConstTypes(child) }
    } else if (node.isArray) {
        node.forEach { undoConstTypes(it) }
    }
}

private fun undoAnyOfRename(node: JsonNode) {
    if (node.isObject) {
        val objectNode = node as ObjectNode
        if (objectNode.has("anyOf")) objectNode.set<JsonNode>("oneOf", objectNode.remove("anyOf"))
        objectNode.properties().forEach { (_, child) -> undoAnyOfRename(child) }
    } else if (node.isArray) {
        node.forEach { undoAnyOfRename(it) }
    }
}

/** Restores the canonical pattern at every rewritten pointer. Empty until Task 2 lands. */
private fun undoPatternRewrites(root: JsonNode) {
    OpenAiStructuredOutputsReceiptAnalysisSchema.PATTERN_REWRITES.forEach { (pointer, rewrite) ->
        (root.at(pointer) as ObjectNode).put("pattern", rewrite.canonicalPattern)
    }
}
```

`undoPatternRewrites` references Task 2's API. Write Task 1's code first with that helper's body commented out, uncomment it in Task 2 — or implement both tasks before running, if executing them together.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*OpenAiStructuredOutputsReceiptAnalysisSchemaTest*"`
Expected: FAIL — `every const node in the derived tree declares a type` reports 13 offenders.

- [ ] **Step 3: Implement the transformation**

In `OpenAiStructuredOutputsReceiptAnalysisSchema`, inside `derive`, after the `oneOf` guards and **before** the rename loop:

```kotlin
            // Before the rename on purpose: every pointer this class documents is expressed
            // against the canonical `/oneOf/` shape, so a reader can locate it in the contract
            // file without mentally applying an earlier transformation.
            addTypeToConstNodes(derived, "")
```

And add:

```kotlin
        /**
         * OpenAI Structured Outputs requires a `type` on every schema node; the canonical schema
         * legitimately omits it wherever `const` already pins the value, which plain JSON Schema
         * allows. The provider rejected the whole request for this alone -- 13 nodes today, all of
         * them a `status` discriminator or the `schemaVersion`.
         *
         * A general rule rather than an allowlist, deliberately: the constant's own JSON type
         * determines the answer with nothing left to review. A non-textual constant is the case
         * that *would* need review -- `number` versus `integer` is a real choice -- and no such
         * constant has ever existed here, so it fails loudly instead of being guessed.
         */
        private fun addTypeToConstNodes(node: JsonNode, path: String) {
            when {
                node.isObject -> {
                    val objectNode = node as ObjectNode
                    val constant = objectNode.get("const")
                    if (constant != null && !objectNode.has("type")) {
                        require(constant.isTextual) {
                            "canonical schema has a non-textual const at $path -- refusing to infer " +
                                "its Structured Outputs type, this needs a reviewed decision"
                        }
                        objectNode.put("type", "string")
                    }
                    objectNode.properties().forEach { (name, child) ->
                        addTypeToConstNodes(child, "$path/${escapeJsonPointerSegment(name)}")
                    }
                }
                node.isArray -> node.forEachIndexed { index, child ->
                    addTypeToConstNodes(child, "$path/$index")
                }
            }
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*OpenAiStructuredOutputsReceiptAnalysisSchemaTest*"`
Expected: PASS.

---

### Task 2: The one unsupported pattern is rewritten from a reviewed allowlist

**Files:**
- Modify: `src/main/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai/OpenAiStructuredOutputsReceiptAnalysisSchema.kt`
- Test: `src/test/kotlin/com/tonycorreia/pricepulsebackend/infrastructure/openai/OpenAiStructuredOutputsReceiptAnalysisSchemaTest.kt`

**Interfaces:**
- Consumes: Task 1's `derive`.
- Produces: `internal data class PatternRewrite(val canonicalPattern: String, val providerPattern: String)` and `internal val PATTERN_REWRITES: Map<String, PatternRewrite>` keyed by canonical JSON pointer.

**The rewrite.** Canonical, rejected by the provider:

```
^(?!0(\.0+)?$)(0|[1-9][0-9]*)(\.[0-9]+)?$
```

Provider-compatible, lookaround-free, verified equivalent:

```
^([1-9][0-9]*(\.[0-9]+)?|0\.[0-9]*[1-9][0-9]*)$
```

Both mean: a canonical positive decimal — no sign, no exponent, no leading zeros — whose value is not zero. The canonical expresses it as "any canonical decimal, except one that is zero"; the replacement enumerates the two shapes that satisfy it instead: a non-zero integer part, or a zero integer part with at least one non-zero fractional digit.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `the rewritten quantity pattern accepts and rejects exactly what the canonical one does`() {
    val rewrite = OpenAiStructuredOutputsReceiptAnalysisSchema
        .PATTERN_REWRITES.getValue("/\$defs/quantityField/oneOf/0/properties/value")
    val canonicalRegex = Regex(rewrite.canonicalPattern)
    val providerRegex = Regex(rewrite.providerPattern)

    val cases = listOf(
        "1", "10", "0.5", "0.05", "1.352", "999999", "0.000001",
        "0", "0.0", "0.000", "01", "007", ".5", "1.", "00.5", "-1", "1e3", "1,5", ""
    )

    val divergent = cases.filter { canonicalRegex.matches(it) != providerRegex.matches(it) }
    assertEquals(emptyList(), divergent, "the provider pattern must accept exactly the canonical language")
}

@Test
fun `the derived tree carries the provider pattern and the canonical keeps its own`() {
    val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical)
    val pointer = "/\$defs/quantityField/anyOf/0/properties/value"
    val rewrite = OpenAiStructuredOutputsReceiptAnalysisSchema
        .PATTERN_REWRITES.getValue("/\$defs/quantityField/oneOf/0/properties/value")

    assertEquals(rewrite.providerPattern, derived.at(pointer).get("pattern").asText())
    assertEquals(
        rewrite.canonicalPattern,
        canonical.at("/\$defs/quantityField/oneOf/0/properties/value").get("pattern").asText(),
        "the canonical contract is never mutated"
    )
}

@Test
fun `no lookaround survives anywhere in the derived tree`() {
    val derived = OpenAiStructuredOutputsReceiptAnalysisSchema.derive(canonical)

    assertEquals(emptyList(), patternsWithLookaround(derived), "the provider rejects any lookaround")
}

@Test
fun `an unlisted lookaround pattern fails loudly instead of reaching the provider`() {
    val tampered = canonical.deepCopy<ObjectNode>()
    (tampered.at("/\$defs/textField/oneOf/0/properties/value") as ObjectNode)
        .put("pattern", "^(?=.*x).+$")

    val failure = assertFailsWith<IllegalArgumentException> {
        OpenAiStructuredOutputsReceiptAnalysisSchema.derive(tampered)
    }
    assertTrue(failure.message!!.contains("/\$defs/textField/oneOf/0/properties/value"))
}

@Test
fun `a rewrite whose canonical pattern no longer matches the contract fails loudly`() {
    val tampered = canonical.deepCopy<ObjectNode>()
    (tampered.at("/\$defs/quantityField/oneOf/0/properties/value") as ObjectNode)
        .put("pattern", "^[0-9]+$")

    assertFailsWith<IllegalArgumentException> {
        OpenAiStructuredOutputsReceiptAnalysisSchema.derive(tampered)
    }
}
```

Add the helper:

```kotlin
private fun patternsWithLookaround(node: JsonNode, path: String = ""): List<String> {
    val found = mutableListOf<String>()
    when {
        node.isObject -> {
            val pattern = node.get("pattern")
            if (pattern != null && pattern.isTextual &&
                (pattern.asText().contains("(?=") || pattern.asText().contains("(?!"))
            ) found += path
            node.properties().forEach { (name, child) -> found += patternsWithLookaround(child, "$path/$name") }
        }
        node.isArray -> node.forEachIndexed { index, child -> found += patternsWithLookaround(child, "$path/$index") }
    }
    return found
}
```

Then uncomment `undoPatternRewrites`'s body from Task 1.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*OpenAiStructuredOutputsReceiptAnalysisSchemaTest*"`
Expected: FAIL to compile — `Unresolved reference: PATTERN_REWRITES`.

- [ ] **Step 3: Implement the allowlist**

Add to the companion object, next to `ALLOWLISTED_ONEOF_POINTERS`:

```kotlin
        /**
         * A canonical pattern the provider refuses, paired with a reviewed equivalent. Structured
         * Outputs rejects regex lookaround outright, and the canonical quantity pattern uses a
         * negative lookahead to say "not zero".
         *
         * [canonicalPattern] is not documentation: it is checked against the contract before any
         * rewrite, so editing the canonical pattern without revisiting the replacement fails the
         * build instead of silently shipping a different language to the provider. Equivalence
         * itself is asserted by test, never by this comment.
         */
        internal data class PatternRewrite(val canonicalPattern: String, val providerPattern: String)

        /** Keyed by pointer into the CANONICAL tree -- rewrites run before the oneOf/anyOf rename. */
        internal val PATTERN_REWRITES: Map<String, PatternRewrite> = mapOf(
            "/\$defs/quantityField/oneOf/0/properties/value" to PatternRewrite(
                canonicalPattern = "^(?!0(\\.0+)?\$)(0|[1-9][0-9]*)(\\.[0-9]+)?\$",
                // Same language without lookaround: a non-zero integer part, or a zero integer
                // part with at least one non-zero fractional digit.
                providerPattern = "^([1-9][0-9]*(\\.[0-9]+)?|0\\.[0-9]*[1-9][0-9]*)\$"
            )
        )
```

And in `derive`, after `addTypeToConstNodes` and still before the rename:

```kotlin
            rewriteUnsupportedPatterns(derived)
```

with:

```kotlin
        /**
         * Applies every [PATTERN_REWRITES] entry, then proves no lookaround is left anywhere --
         * an unlisted one fails loudly rather than travelling to the provider, which would reject
         * the entire request and cost a reservation for a fault this class could have caught.
         */
        private fun rewriteUnsupportedPatterns(derived: JsonNode) {
            PATTERN_REWRITES.forEach { (pointer, rewrite) ->
                val target = derived.at(pointer)
                require(target.isObject) { "pattern rewrite pointer $pointer does not resolve to a JSON object" }
                val objectNode = target as ObjectNode
                val actual = objectNode.get("pattern")?.takeIf { it.isTextual }?.asText()
                require(actual == rewrite.canonicalPattern) {
                    "canonical pattern at $pointer is not the one this rewrite was reviewed against -- " +
                        "the replacement may no longer be equivalent, this needs a reviewed update"
                }
                objectNode.put("pattern", rewrite.providerPattern)
            }

            val remaining = findLookaroundPatternPointers(derived, "")
            require(remaining.isEmpty()) {
                "canonical schema uses regex lookaround at $remaining, which Structured Outputs " +
                    "rejects -- refusing to rewrite automatically, this needs a reviewed entry in PATTERN_REWRITES"
            }
        }

        private fun findLookaroundPatternPointers(node: JsonNode, path: String): List<String> {
            val found = mutableListOf<String>()
            when {
                node.isObject -> {
                    val pattern = node.get("pattern")?.takeIf { it.isTextual }?.asText()
                    if (pattern != null && (pattern.contains("(?=") || pattern.contains("(?!"))) {
                        found += path
                    }
                    node.properties().forEach { (name, child) ->
                        found += findLookaroundPatternPointers(child, "$path/${escapeJsonPointerSegment(name)}")
                    }
                }
                node.isArray -> node.forEachIndexed { index, child ->
                    found += findLookaroundPatternPointers(child, "$path/$index")
                }
            }
            return found
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test --tests "*OpenAiStructuredOutputsReceiptAnalysisSchemaTest*"`
Expected: PASS.

- [ ] **Step 5: Run the whole suite**

Run: `JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew test`
Expected: PASS — in particular `OpenAiResponsesRequestFactoryTest`, which builds a request around this schema.

- [ ] **Step 6: Confirm no contract drift**

Run: `git diff --name-only -- contracts/`
Expected: only `receipt-analysis-result.v1.schema.json`, and only with the edits that were already uncommitted before this plan began. No task here opens a file under `contracts/`.

---

## Verification after both tasks

1. **The provider accepts the schema.** Rebuild (`docker compose up -d --build api`) and send one receipt. The expected outcome is no longer `MALFORMED_REQUEST`.
2. **Expect `FAILED_NO_PROVIDER` / `BILLING_OR_QUOTA_EXHAUSTED` until the OpenAI account has credit.** That is the correct classification of `429 credit_balance_exhausted`, and it releases the user's credit. It is success for this plan, not failure.
3. **Only once the account has credit** can a receipt reach `SUCCEEDED` with its items. That is the first moment a real end-to-end latency can be measured — the number still missing for any timeout or `background`-mode decision.

## Explicitly out of scope

- Adding credit to the OpenAI account: required for any analysis to complete, and nothing in this repository can do it.
- The 60-second request timeout and `background` mode: still undecided, and still without a single real latency measurement to decide on.
- The uncommitted edits to the canonical schema: preserved untouched; whether they are intended is the user's call, not this plan's.
