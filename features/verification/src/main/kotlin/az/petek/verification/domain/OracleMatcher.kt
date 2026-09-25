/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.verification.domain

import az.petek.campaign.domain.AssertionSpec
import az.petek.evidence.domain.Verdict
import az.petek.oracle.domain.JsonFieldSelector
import az.petek.oracle.domain.OracleResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure comparison of an oracle answer with an already rendered `oracle` assertion.
 *
 * Rules: a non-2xx status fails. With `field`, the value is selected by [fields]; a primitive compares by its
 * content (`3`, `true`, `in_progress`), objects and arrays by their JSON text. Without `field`, `equals` compares the
 * whole body and `contains` searches the raw body. `equals` and `contains` may both be given; both must hold.
 * With neither, the check only requires a 2xx answer (and the field to exist, when one is named).
 */
internal class OracleMatcher(
    private val fields: JsonFieldSelector,
) {
    data class Match(
        val verdict: Verdict,
        val observed: String,
        val note: String?,
    )

    fun match(
        spec: AssertionSpec.Oracle,
        response: OracleResponse,
    ): Match {
        if (response.status !in SUCCESS) {
            return Match(Verdict.FAILED, "HTTP ${response.status}", "oracle answered ${response.status}, expected 2xx")
        }
        val field = spec.field ?: return compare(spec, wholeBody(response), response.rawBody, label = null)
        val body =
            response.body
                ?: return Match(Verdict.FAILED, "body is not JSON", "cannot select field `$field` from a non-JSON body")
        val value =
            fields.select(body, field)
                ?: return Match(Verdict.FAILED, "field `$field` missing", "field `$field` not found in the oracle answer")
        val text = value.text()
        return compare(spec, text, text, label = field)
    }

    private fun compare(
        spec: AssertionSpec.Oracle,
        equalsSubject: String,
        containsSubject: String,
        label: String?,
    ): Match {
        val failures =
            listOfNotNull(
                spec.equals?.takeIf { it != equalsSubject }?.let {
                    "expected ${AssertionText.quote(it)} but was ${AssertionText.quote(AssertionText.clip(equalsSubject))}"
                },
                spec.contains?.takeIf { it !in containsSubject }?.let {
                    "${AssertionText.quote(it)} not found in ${label?.let { name -> "field `$name`" } ?: "the body"}"
                },
            )
        val shown = AssertionText.clip(if (label == null) containsSubject else equalsSubject)
        val observed = if (label == null) shown else "$label = $shown"
        return if (failures.isEmpty()) {
            Match(Verdict.PASSED, observed, null)
        } else {
            Match(Verdict.FAILED, observed, failures.joinToString("; "))
        }
    }

    private fun wholeBody(response: OracleResponse): String = (response.body as? JsonPrimitive)?.content ?: response.rawBody.trim()

    private fun JsonElement.text(): String = (this as? JsonPrimitive)?.content ?: toString()

    private companion object {
        val SUCCESS = 200..299
    }
}
