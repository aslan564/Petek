/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.verification.domain

import az.petek.campaign.domain.AssertionSpec
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.RaceNotes
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
 * - PASSED when at least [MIN_RACERS] actors raced, exactly one actor succeeded, the requests of every actor could be
 *   read (otherwise a second winner could go unseen) and the oracle condition (if any) did not fail; an oracle that
 *   could not be asked (no test API) is SKIPPED and leaves the verdict to the requests, with a note.
 * - FAILED when the site decided wrongly: more than one winner (its note leads with [RaceNotes.SEVERAL_WINNERS], a
 *   site defect), every attempt refused (nobody could decide the object), or the oracle condition failed.
 * - INCONCLUSIVE (Faza 24.12) when the evidence cannot decide: fewer than [MIN_RACERS] racers, no racer sent the
 *   request at all (`no_attempt`, a gap of the agents, not a refusal by the site), requests that could not be read
 *   where a winner could hide, or a race nobody contested: the winner alone sent the deciding request, the others found
 *   the object decided and did not ask ([RaceNotes.UNCONTESTED], the owner's decision of 2026-09-30), so the site was
 *   never asked two decisions at once.
 * - An actor raced when it reached the start line and acted, so its requests were read or found unreadable
 *   ([ActorResult.race] is set). One whose action never ran (awaited event missing, template error) did not race, and
 *   neither did the actors a step never got (a wave without them, a tester that failed earlier): a single racer that
 *   wins proves nothing about the race, so such a step never passes.
 * - `observed` lists the decisive request per actor in agent order, e.g.
 *   `a02 POST /tickets/t2/approve -> 303; a03 POST /tickets/t2/approve -> 409`, then the oracle's answer.
 * - The raw evidence is JSON with every actor's requests, its agent summary (text only) and whether it lost the race.
 */
internal object RaceVerdict {
    /** Fewer racers than this is no race at all, whoever of them won. */
    const val MIN_RACERS = 2

    /** Leads the note of a race in which no racer sent the request that decides it (Faza 24.12). */
    const val NO_ATTEMPT = "no_attempt"

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
        val racers = ordered.filter { it.race != null }
        val attempted = racers.filter { it.race?.decisive != null }
        val (raceVerdict, raceNote) =
            when {
                ordered.isEmpty() -> {
                    Verdict.INCONCLUSIVE to "no actor results to compare"
                }

                racers.size < MIN_RACERS -> {
                    val raced = racers.joinToString { it.agentId.value }.ifEmpty { null }
                    Verdict.INCONCLUSIVE to
                        "a race needs at least $MIN_RACERS racing actors; " + (raced?.let { "only $it raced" } ?: "none raced")
                }

                winners.size > 1 -> {
                    Verdict.FAILED to "${RaceNotes.SEVERAL_WINNERS}: more than one actor succeeded ($winnerIds); expected exactly one"
                }

                // A winner, or a second one, could hide among actors whose requests are unknown.
                unknown.isNotEmpty() -> {
                    Verdict.INCONCLUSIVE to "the requests of ${unknown.joinToString { it.agentId.value }} could not be read"
                }

                winners.isEmpty() && attempted.isEmpty() -> {
                    Verdict.INCONCLUSIVE to "$NO_ATTEMPT: no racer sent ${requestOf(spec)}, so nothing was decided"
                }

                winners.isEmpty() -> {
                    Verdict.FAILED to "no actor succeeded: every attempt was refused; expected exactly one winner"
                }

                attempted.size < MIN_RACERS -> {
                    Verdict.INCONCLUSIVE to
                        "${RaceNotes.UNCONTESTED}: only $winnerIds sent ${requestOf(spec)}; the others found it decided " +
                        "and did not ask, so two decisions at once were never tried"
                }

                else -> {
                    Verdict.PASSED to null
                }
            }
        val verdict =
            when {
                raceVerdict == Verdict.FAILED || oracle?.verdict == Verdict.FAILED -> Verdict.FAILED
                else -> raceVerdict
            }
        return AssertionResult(
            spec = spec,
            verdict = verdict,
            source = EvidenceSource.SENDER,
            expected = expected(spec, ordered.size) + (oracle?.let { " and ${it.expected}" } ?: ""),
            observed = AssertionText.clip(observed(ordered) + (oracle?.let { "; oracle: ${oracleObserved(it)}" } ?: "")),
            latency = null,
            note = listOfNotNull(raceNote, oracle?.note?.let { "oracle: $it" }).joinToString("; ").ifEmpty { null },
            rawEvidence = evidenceJson(spec, ordered, oracle),
            oracleEvidence = oracle?.body?.takeIf { it.isNotBlank() },
        )
    }

    private fun requestOf(spec: AssertionSpec.OnlyOneSucceeds): String =
        spec.request?.let { "a request matching `${it.describe()}`" } ?: "a mutating request"

    private fun expected(
        spec: AssertionSpec.OnlyOneSucceeds,
        actors: Int,
    ): String =
        "exactly one of $actors actor${if (actors == 1) "" else "s"} succeeds" + (spec.request?.let { " by `${it.describe()}`" } ?: "")

    private fun observed(results: List<ActorResult>): String =
        if (results.isEmpty()) {
            "no actors"
        } else {
            results.joinToString("; ") { "${it.agentId.value} ${outcome(it)}" }
        }

    private fun outcome(result: ActorResult): String =
        result.race?.describe()
            ?: if (result.succeeded) "succeeded" else "did not race"

    private fun oracleObserved(check: OracleCheck): String =
        when {
            check.verdict == Verdict.NOT_APPLICABLE -> "N/A (no oracle)"
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
