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
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The pure part of `only_one_succeeds`: the verdict from the actors' code-derived results ([ActorResult.succeeded],
 * see [RaceEvidence]) and, when the spec has one, the outcome of its oracle condition ([OracleCheck]).
 *
 * - PASSED when exactly one actor succeeded, the requests of every actor could be read (otherwise a second winner
 *   could go unseen) and the oracle condition (if any) did not fail; an oracle that could not be asked (no test API)
 *   is SKIPPED and leaves the verdict to the requests, with a note.
 * - `observed` lists the decisive request per actor in agent order, e.g.
 *   `a02 POST /tickets/t2/approve -> 303; a03 POST /tickets/t2/approve -> 409`, then the oracle's answer.
 * - The raw evidence is JSON with every actor's requests, its agent summary (text only) and whether it lost the race.
 */
internal object RaceVerdict {
    /** What the target's test API said about the race's final state. */
    class OracleCheck(
        val verdict: Verdict,
        val expected: String,
        val observed: String?,
        val note: String?,
        val body: String?,
    )

    fun judge(
        spec: AssertionSpec.OnlyOneSucceeds,
        results: List<ActorResult>,
        oracle: OracleCheck? = null,
    ): AssertionResult {
        val ordered = results.sortedBy { it.agentId.index }
        val winners = ordered.filter { it.succeeded }
        val winnerIds = winners.joinToString { it.agentId.value }
        val unknown = ordered.filter { it.race?.unavailable != null }
        val raceNote =
            when {
                ordered.isEmpty() -> "no actor results to compare"

                winners.isEmpty() -> "no actor succeeded; expected exactly one winner"

                winners.size > 1 -> "more than one actor succeeded ($winnerIds); expected exactly one"

                // A second winner could hide among actors whose requests are unknown.
                unknown.isNotEmpty() -> "the requests of ${unknown.joinToString { it.agentId.value }} could not be read"

                else -> null
            }
        val oracleFailed = oracle?.verdict == Verdict.FAILED
        return AssertionResult(
            spec = spec,
            verdict = if (raceNote == null && !oracleFailed) Verdict.PASSED else Verdict.FAILED,
            source = EvidenceSource.SENDER,
            expected = expected(spec, ordered.size) + (oracle?.let { " and ${it.expected}" } ?: ""),
            observed = AssertionText.clip(observed(ordered) + (oracle?.let { "; oracle: ${oracleObserved(it)}" } ?: "")),
            latency = null,
            note = listOfNotNull(raceNote, oracle?.note?.let { "oracle: $it" }).joinToString("; ").ifEmpty { null },
            rawEvidence = evidenceJson(spec, ordered, oracle),
            oracleEvidence = oracle?.body?.takeIf { it.isNotBlank() },
        )
    }

    private fun expected(
        spec: AssertionSpec.OnlyOneSucceeds,
        actors: Int,
    ): String = "exactly one of $actors actors succeeds" + (spec.request?.let { " by `${it.describe()}`" } ?: "")

    private fun observed(results: List<ActorResult>): String =
        if (results.isEmpty()) {
            "no actors"
        } else {
            results.joinToString("; ") { "${it.agentId.value} ${outcome(it)}" }
        }

    private fun outcome(result: ActorResult): String =
        result.race?.describe()
            ?: if (result.succeeded) "succeeded" else "did not succeed"

    private fun oracleObserved(check: OracleCheck): String =
        when {
            check.verdict == Verdict.SKIPPED -> "not checked"
            else -> check.observed ?: "-"
        }

    private fun evidenceJson(
        spec: AssertionSpec.OnlyOneSucceeds,
        results: List<ActorResult>,
        oracle: OracleCheck?,
    ): String =
        buildJsonObject {
            put("assertion", spec.type)
            put("request", spec.effectiveRequest.describe())
            put("winners", results.count { it.succeeded })
            putJsonArray("winner_ids") { results.filter { it.succeeded }.forEach { add(it.agentId.value) } }
            putJsonArray("actors") { results.forEach { actor -> addJsonObject { actor(actor) } } }
            oracle?.let { check ->
                putJsonObject("oracle") {
                    put("expected", check.expected)
                    put("verdict", check.verdict.name)
                    put("observed", check.observed)
                    put("note", check.note)
                }
            }
        }.toString()

    private fun JsonObjectBuilder.actor(actor: ActorResult) {
        put("agent_id", actor.agentId.value)
        put("succeeded", actor.succeeded)
        put("lost_race", actor.lostRace)
        put("summary", actor.summary)
        val race = actor.race ?: return
        put("decisive", race.decisive?.describe())
        race.unavailable?.let { put("requests_unavailable", it) }
        putJsonArray("requests") {
            race.requests.forEach { request ->
                addJsonObject {
                    put("method", request.method)
                    put("path", request.path)
                    put("status", request.status)
                    put("at", request.at.wall.toString())
                }
            }
        }
    }
}
