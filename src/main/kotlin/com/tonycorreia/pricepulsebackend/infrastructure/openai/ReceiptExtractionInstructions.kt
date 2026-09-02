package com.tonycorreia.pricepulsebackend.infrastructure.openai

/**
 * The extraction prompt sent to the provider as `input_text`, alongside the receipt image and the
 * Structured Outputs schema derived from the canonical contract.
 *
 * It lives here, and not as a file-private constant in `Application.kt`, for one reason: a
 * top-level `private val` is private to its file, so no test could reach it. The prompt is
 * production configuration that reaches every analysis, including a user's -- it earns assertions
 * like any other production value.
 *
 * **The literals in this text are duplicated from the canonical schema by necessity** -- the model
 * is told them in prose, and prose cannot `$ref` a JSON schema. That duplication is exactly how the
 * defect this text now corrects was introduced: rule 2 offered three confidence levels while
 * `receipt-analysis-result.v1.schema.json` has always allowed four, so `UNKNOWN` was permitted by
 * the schema and never once requested. `ReceiptExtractionInstructionsTest` pins both literal sets
 * against the contract file so the next divergence fails a test instead of shipping.
 *
 * ## Why the confidence levels are anchored in reading conditions
 *
 * ADR-007 (decisions 146 and 149). The first field-by-field measurement found **47 of 47 present
 * fields returned HIGH -- including the three that were wrong**. A self-graded signal asked for
 * "um nível de confiança (HIGH/MEDIUM/LOW)", with no definition of any level and no instruction to
 * reserve the top one, collapsing into that top label is the expected outcome, not an anomaly.
 *
 * So each level is now defined by an **observable condition of the reading**, not by how sure the
 * model feels, and HIGH is stated to be exceptional rather than the default. The two description
 * errors that founded the baseline -- a brand dropped from `LEITE INTEGRAL TIROL 1L` and an accent
 * lost from `DETERGENTE LÍQ.` -- fall under MEDIUM by construction.
 *
 * Whether this actually produces variance is a hypothesis, not a fix. Decision 147 exists to test
 * it against R002 before any of the pilot's budget is spent.
 *
 * ## What must not change here without revisiting the milestone
 *
 * Rules 1 and 3-6 govern **extraction**, which is what Milestone 3 measures against the ceilings
 * fixed in slice 3.1. Editing them mid-pilot makes a bad number unattributable: extraction and the
 * prompt would have changed together. Only the confidence clause of rule 2 was rewritten.
 */
internal object ReceiptExtractionInstructions {

    val TEXT: String = """
        Você é um assistente que extrai dados estruturados de imagens de notas fiscais/recibos de
        compra. Analise a imagem fornecida e devolva exatamente os campos do schema fornecido,
        seguindo estas regras:

        1. documentStatus: classifique como COMPLETE se a imagem é claramente um recibo/nota fiscal
           legível com os campos principais visíveis; PARTIAL se é um recibo mas alguns campos estão
           ilegíveis ou ausentes; UNREADABLE se a imagem é muito borrada, escura, ou cortada para ler
           com confiança; NOT_A_RECEIPT se a imagem claramente não é um recibo de compra.
        2. Para merchantName, purchasedAt, total, e cada item (description, quantity, unit, unitPrice,
           totalPrice): se o valor está visível e legível, devolva status "present" com o valor e um
           nível de confiança; se o valor aparece na imagem mas não pôde ser interpretado com
           confiança, devolva status "invalid" com uma breve descrição textual do que foi visto; se o
           campo simplesmente não aparece na imagem, devolva status "missing".
        2.1. O nível de confiança descreve a CONDIÇÃO DA LEITURA daquele campo, não o quanto o valor
           parece plausível. Escolha pelo que aconteceu ao ler:
           - HIGH: todo caractere do valor estava legível e foi transcrito exatamente como impresso.
             Nada foi expandido, completado, corrigido ou inferido. HIGH é o nível excepcional, não
             o padrão: se você precisou fazer qualquer coisa além de transcrever, não é HIGH.
           - MEDIUM: o valor foi lido, mas com ao menos uma destas: um caractere ambíguo resolvido
             pelo contexto; uma abreviação expandida por você; texto cortado, apagado ou borrado
             completado; acento, marca ou palavra restaurados a partir do que a linha sugeria.
           - LOW: o valor foi majoritariamente inferido em vez de lido -- deduzido da posição na
             nota, da soma ou diferença de outros campos, ou do que uma nota costuma trazer naquele
             lugar.
           - UNKNOWN: o valor foi lido, mas não há base para avaliar a condição da leitura.
        3. purchasedAt deve ser convertido para UTC no formato ISO-8601 terminando em "Z", nunca um
           deslocamento numérico.
        4. total e cada unitPrice/totalPrice devem ser valores monetários em unidades menores
           (centavos), nunca números decimais ou de ponto flutuante.
        5. quantity deve ser um valor decimal estritamente positivo.
        6. Nunca invente valores que não estão visíveis na imagem -- prefira "missing"/"invalid" a
           uma suposição.
    """.trimIndent()
}
