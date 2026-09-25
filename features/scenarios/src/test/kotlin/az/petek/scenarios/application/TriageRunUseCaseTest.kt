/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.scenarios.application

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.security.Secret
import az.petek.core.testing.FakeHarnessClock
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmRequest
import az.petek.llm.testing.ScriptedLlmClient
import az.petek.scenarios.domain.EvidenceRef
import az.petek.scenarios.domain.EvidenceRefType
import az.petek.scenarios.domain.IgnoreReason
import az.petek.scenarios.domain.ProposalStatus
import az.petek.scenarios.domain.ProposedChange
import az.petek.scenarios.domain.RunEvidence
import az.petek.scenarios.domain.ScenarioNotFoundException
import az.petek.scenarios.domain.ScenarioNotInCatalogException
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioStatus
import az.petek.scenarios.domain.ScenarioVersion
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.SecretRedactor
import az.petek.scenarios.domain.SurpriseId
import az.petek.scenarios.domain.TriageCategory
import az.petek.scenarios.domain.TriageRunNotFinishedException
import az.petek.scenarios.domain.TriageRunNotFoundException
import az.petek.scenarios.domain.TriageVerdict
import az.petek.scenarios.domain.YamlEdit
import az.petek.scenarios.infrastructure.FileSystemScenarioFiles
import az.petek.scenarios.testing.InMemoryScenarioRepository
import az.petek.scenarios.testing.InMemoryTriageRepository
import az.petek.scenarios.testing.RunStories
import az.petek.scenarios.testing.RunStories.into
import az.petek.scenarios.testing.RunStories.plus
import az.petek.scenarios.testing.ScenarioTestKit
import az.petek.scenarios.testing.ScenarioTestKit.MINI_YAML
import az.petek.scenarios.testing.ScenarioTestKit.RUN
import az.petek.scenarios.testing.ScenarioTestKit.step
import az.petek.scenarios.testing.SequentialScenarioIds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class TriageRunUseCaseTest {
    @TempDir
    lateinit var dir: Path

    private val clock = FakeHarnessClock()
    private val ids = SequentialScenarioIds()
    private val evidence = InMemoryEvidence()
    private val scenarios = InMemoryScenarioRepository()
    private val triage = InMemoryTriageRepository()
    private val validator get() = ScenarioTestKit.validator(dir.resolve("work"))
    private val catalog get() = ScenarioCatalog(scenarios, validator, FileSystemScenarioFiles(), clock, ids)

    private fun useCase(
        llm: ScriptedLlmClient,
        options: TriageOptions = TriageOptions(),
        redactor: SecretRedactor = SecretRedactor(),
    ) = TriageRunUseCase(llm, evidence, evidence, scenarios, triage, validator, clock, ids, redactor, options)

    // ---- the scenario, the run and the model -----------------------------------------------------------------------

    /** Stores [MINI_YAML] as an approved v1 and a finished run of it with the given evidence. */
    private suspend fun givenRun(vararg stories: RunEvidence): ScenarioVersion {
        val v1 = catalog.createDraft(MINI_YAML, ScenarioSource.USER)
        catalog.approve(v1.id)
        evidence.create(ScenarioTestKit.run())
        stories.forEach { it.into(evidence) }
        return catalog.get(v1.id)
    }

    private fun srp(
        scenarioStep: String,
        agent: String?,
    ): SurpriseId = SurpriseId.of(RUN, scenarioStep, agent?.let(::AgentId))

    private fun answer(
        category: TriageCategory,
        refs: List<String> = emptyList(),
        confidence: Double = 0.8,
        rationale: String = "Sübut bunu göstərir.",
        edits: List<Pair<String, String>>? = null,
        summary: String = "Addımı düzəlt",
    ): JsonObject =
        buildJsonObject {
            put("category", category.name)
            put("rationale", rationale)
            put("confidence", confidence)
            putJsonArray("evidence_refs") { refs.forEach { add(it) } }
            if (edits != null) {
                putJsonObject("proposed_change") {
                    put("summary", summary)
                    putJsonArray("edits") {
                        edits.forEach { (find, replace) ->
                            addJsonObject {
                                put("find", find)
                                put("replace", replace)
                            }
                        }
                    }
                }
            }
        }

    private fun surpriseOf(request: LlmRequest) = SurpriseId(request.label.substringAfterLast('/'))

    /** Answers each question by the surprise its label names; an unexpected question fails the test. */
    private fun model(answers: Map<SurpriseId, suspend (LlmRequest) -> JsonObject>) =
        ScriptedLlmClient { request ->
            val respond = answers[surpriseOf(request)] ?: error("unexpected question ${request.label}")
            respond(request)
        }

    private fun fixed(vararg answers: Pair<SurpriseId, JsonObject>): ScriptedLlmClient {
        val byId = answers.toMap()
        return ScriptedLlmClient { request -> byId[surpriseOf(request)] ?: error("unexpected question ${request.label}") }
    }

    private val readTask = "do: \"Bildirişləri aç və yeni elanı oxu\""
    private val readTaskFixed = "do: \"Zəng işarəsinə bas və yeni elanı oxu\""
    private val announceTask = "do: \"Elan yarat: 'Sabah 10:00 ümumi iclas'\""
    private val announceTaskFixed = "do: \"Elanlar bölməsində elan yarat: 'Sabah 10:00 ümumi iclas'\""

    // ---- verdicts ----------------------------------------------------------------------------------------------------

    @Test
    fun `each surprise gets one question and a verdict linked to the evidence it is based on`() =
        runTest {
            val v1 = givenRun(RunStories.announcementPassed(), RunStories.problemReported("a03"), RunStories.failedJoin("a04"))
            val llm =
                fixed(
                    srp("read_announce", "a03") to answer(TriageCategory.SYSTEM_BUG, listOf("stp_a03_3", "[fnd_a03]", "stp_invented")),
                    srp("join", "a04") to answer(TriageCategory.MODEL_GAP, confidence = 0.4),
                )

            val report = useCase(llm).execute(RUN)

            llm.requests.map { it.label }.toSet() shouldBe
                setOf("triage/run_1/${srp("read_announce", "a03")}", "triage/run_1/${srp("join", "a04")}")
            report.asked shouldBe 2
            report.deferred shouldBe 0
            report.draft shouldBe null
            report.scenario shouldBe v1
            report.items.map { it.surprise.id } shouldBe listOf(srp("read_announce", "a03"), srp("join", "a04"))
            val system = report.items[0].verdict.shouldNotBeNull()
            system.category shouldBe TriageCategory.SYSTEM_BUG
            system.scenarioVersionId shouldBe v1.id
            system.model shouldBe "scripted"
            system.decidedAt shouldBe clock.now().wall
            system.proposedChange shouldBe null
            // Cited step and finding, plus the screenshots of the cited step; the invented id is dropped.
            system.basedOn shouldBe
                listOf(
                    EvidenceRef(EvidenceRefType.STEP, "stp_a03_3"),
                    EvidenceRef(EvidenceRefType.FINDING, "fnd_a03"),
                    EvidenceRef(EvidenceRefType.ARTIFACT, "art_a03_1"),
                    EvidenceRef(EvidenceRefType.ARTIFACT, "art_a03_2"),
                )
            // Nothing cited: the verdict rests on everything the question showed.
            val gap = report.items[1].verdict.shouldNotBeNull()
            gap.confidence shouldBe 0.4
            gap.basedOn shouldBe
                report.items[1]
                    .surprise.evidence.refs
            TriageResults(triage).forRun(RUN) shouldBe report.items
        }

    @Test
    fun `the question shows the facts, the step definition and the scenario yaml, but never a secret`() =
        runTest {
            val leak =
                RunEvidence(
                    steps =
                        listOf(
                            step(
                                "stp_leak",
                                "a03",
                                "read_announce",
                                StepKind.DO,
                                "type [4] \"Gizli-Parol-2026\"",
                                StepStatus.PASSED,
                                "token=abc123",
                            ),
                        ),
                    assertions = emptyList(),
                    findings = emptyList(),
                    artifacts = emptyList(),
                )
            givenRun(RunStories.problemReported("a03") + leak)
            val llm = fixed(srp("read_announce", "a03") to answer(TriageCategory.SYSTEM_BUG))

            useCase(llm, redactor = SecretRedactor(listOf(Secret("Gizli-Parol-2026")))).execute(RUN)

            val request = llm.requests.single()
            val question = request.messages.single().content
            question shouldContain "Surprise ${srp("read_announce", "a03")} (PROBLEM_REPORTED) by a03:"
            question shouldContain "Scenario step 'read_announce' (main, YAML line "
            question shouldContain "actor employee[*]; do: Bildirişləri aç və yeni elanı oxu; wait_for announcement_created within 30s"
            question shouldContain "- [stp_a03_3] DO report_problem bug \"Elan siyahıda yoxdur\" -> FAILED"
            question shouldContain "Scenario YAML of mini v1, exactly as stored:"
            question shouldContain "    $readTask\n"
            question shouldNotContain "Gizli-Parol-2026"
            question shouldNotContain "abc123"
            request.system shouldContain "SYSTEM_BUG"
            request.system shouldContain "Azerbaijani"
            request.responseSchema shouldBe TriagePrompt.SCHEMA
        }

    @Test
    fun `expected refusals, lost races and environment failures are not asked about`() =
        runTest {
            givenRun(RunStories.expectedRefusal(), RunStories.race(won = true), RunStories.environmentFailure())
            val llm = fixed()

            val report = useCase(llm).execute(RUN)

            llm.requests.shouldBeEmpty()
            report.items.shouldBeEmpty()
            report.asked shouldBe 0
            report.ignored.map { it.reason }.toSet() shouldBe
                setOf(IgnoreReason.EXPECTED_REFUSAL, IgnoreReason.LOST_RACE, IgnoreReason.ENVIRONMENT)
        }

    // ---- proposals ---------------------------------------------------------------------------------------------------

    @Test
    fun `a scenario bug with a usable change becomes a v2 draft for the owner to review`() =
        runTest {
            val v1 = givenRun(RunStories.problemReported("a03"))
            val llm =
                fixed(
                    srp("read_announce", "a03") to
                        answer(TriageCategory.SCENARIO_BUG, listOf("stp_a03_2"), edits = listOf(readTask to readTaskFixed)),
                )

            val report = useCase(llm).execute(RUN)

            val draft = report.draft.shouldNotBeNull()
            draft.version shouldBe 2
            draft.status shouldBe ScenarioStatus.DRAFT
            draft.source shouldBe ScenarioSource.TRIAGE
            draft.parentId shouldBe v1.id
            draft.yaml shouldBe MINI_YAML.replace(readTask, readTaskFixed)
            draft.note shouldContain "run_1"
            draft.note shouldContain srp("read_announce", "a03").value
            draft.note shouldContain "Addımı düzəlt"
            val verdict =
                report.items
                    .single()
                    .verdict
                    .shouldNotBeNull()
            verdict.proposedChange shouldBe
                ProposedChange("Addımı düzəlt", listOf(YamlEdit(readTask, readTaskFixed)), ProposalStatus.DRAFTED, draftId = draft.id)
            TriageResults(triage).forDraft(draft.id) shouldBe listOf(verdict)
            catalog.diffFromParent(draft.id).shouldNotBeNull().unified() shouldContain "+    $readTaskFixed"
            catalog.get(v1.id) shouldBe v1
        }

    @Test
    fun `a change that breaks the campaign is rejected and the verdict is kept`() =
        runTest {
            givenRun(RunStories.problemReported("a03"))
            val llm =
                fixed(
                    srp("read_announce", "a03") to
                        answer(TriageCategory.MODEL_GAP, edits = listOf("wait_for: announcement_created" to "wait_for: ticket_created")),
                )

            val report = useCase(llm).execute(RUN)

            report.draft shouldBe null
            val verdict =
                report.items
                    .single()
                    .verdict
                    .shouldNotBeNull()
            verdict.category shouldBe TriageCategory.MODEL_GAP
            val change = verdict.proposedChange.shouldNotBeNull()
            change.status shouldBe ProposalStatus.REJECTED
            change.rejection.shouldNotBeNull() shouldContain "does not pass the campaign validator"
            change.rejection.shouldNotBeNull() shouldContain "ticket_created"
            scenarios.all().map { it.version } shouldBe listOf(1)
        }

    @Test
    fun `changes that do not apply, touch protected settings or come malformed are rejected with the reason`() =
        runTest {
            givenRun(RunStories.problemReported("a03"), RunStories.failedJoin("a04"), RunStories.expectedRefusal(statusFails = true))
            val malformed =
                buildJsonObject {
                    put("category", "SCENARIO_BUG")
                    put("rationale", "x")
                    put("confidence", 0.5)
                    put("proposed_change", "change the text of step forbidden")
                }
            val llm =
                fixed(
                    srp("read_announce", "a03") to answer(TriageCategory.SCENARIO_BUG, edits = listOf("do: \"Ticket yaz\"" to "x")),
                    srp("join", "a04") to
                        answer(
                            TriageCategory.MODEL_GAP,
                            edits = listOf("seed: 7" to "seed: 8", "https://staging.kadrohr.test" to "https://www.kadrohr.test"),
                        ),
                    srp("forbidden", "a04") to malformed,
                )

            val report = useCase(llm).execute(RUN)

            report.draft shouldBe null
            val rejections =
                report.items.map {
                    it.verdict
                        ?.proposedChange
                        ?.rejection
                        .orEmpty()
                }
            rejections[0] shouldContain "does not occur"
            rejections[1] shouldContain "target, seed"
            rejections[2] shouldContain "malformed proposal: 'proposed_change' must be an object"
            report.items.map { it.verdict?.proposedChange?.status } shouldBe List(3) { ProposalStatus.REJECTED }
        }

    @Test
    fun `a system bug never changes the scenario`() =
        runTest {
            givenRun(RunStories.problemReported("a03"))
            val llm = fixed(srp("read_announce", "a03") to answer(TriageCategory.SYSTEM_BUG, edits = listOf(readTask to readTaskFixed)))

            val report = useCase(llm).execute(RUN)

            report.draft shouldBe null
            val change =
                report.items
                    .single()
                    .verdict
                    ?.proposedChange
                    .shouldNotBeNull()
            change.status shouldBe ProposalStatus.REJECTED
            change.rejection shouldBe "a SYSTEM_BUG verdict does not change the scenario"
        }

    @Test
    fun `the usable changes of one run make one draft, conflicts are rejected and identical changes count once`() =
        runTest {
            givenRun(
                RunStories.problemReported("a03"),
                RunStories.failedJoin("a04"),
                RunStories.expectedRefusal(statusFails = true),
                RunStories.race(won = false),
            )
            val llm =
                fixed(
                    srp("read_announce", "a03") to answer(TriageCategory.SCENARIO_BUG, edits = listOf(readTask to readTaskFixed)),
                    srp("join", "a04") to answer(TriageCategory.MODEL_GAP, edits = listOf(announceTask to announceTaskFixed)),
                    srp("forbidden", "a04") to
                        answer(TriageCategory.SCENARIO_BUG, edits = listOf(readTask to "do: \"Bildiriş siyahısını aç\"")),
                    srp("race", "a03") to answer(TriageCategory.SYSTEM_BUG),
                    srp("race", null) to answer(TriageCategory.SCENARIO_BUG, edits = listOf(readTask to readTaskFixed)),
                )

            val report = useCase(llm, TriageOptions(parallelism = 3)).execute(RUN)

            val draft = report.draft.shouldNotBeNull()
            draft.yaml shouldBe MINI_YAML.replace(readTask, readTaskFixed).replace(announceTask, announceTaskFixed)
            val byStep = report.items.associate { (it.surprise.scenarioStep to it.surprise.agentId?.value) to it.verdict?.proposedChange }
            byStep.getValue("read_announce" to "a03")?.draftId shouldBe draft.id
            byStep.getValue("join" to "a04")?.draftId shouldBe draft.id
            byStep.getValue("race" to null)?.draftId shouldBe draft.id
            byStep.getValue("race" to "a03") shouldBe null
            val conflict = byStep.getValue("forbidden" to "a04").shouldNotBeNull()
            conflict.status shouldBe ProposalStatus.REJECTED
            conflict.rejection.shouldNotBeNull() shouldContain "conflicts with an earlier change of this triage"
            TriageResults(triage).forDraft(draft.id) shouldHaveSize 3
            scenarios.history("mini").map { it.version } shouldBe listOf(1, 2)
        }

    @Test
    fun `a frozen version gets its v2 as a new draft and stays exactly as it was`() =
        runTest {
            val v1 = givenRun(RunStories.problemReported("a03"))
            val frozen = catalog.freeze(v1.id)
            val llm = fixed(srp("read_announce", "a03") to answer(TriageCategory.SCENARIO_BUG, edits = listOf(readTask to readTaskFixed)))

            val report = useCase(llm).execute(RUN)

            report.draft.shouldNotBeNull().parentId shouldBe v1.id
            catalog.get(v1.id) shouldBe frozen
        }

    @Test
    fun `pending changes left by an interrupted execution are drafted by the next one`() =
        runTest {
            val v1 = givenRun(RunStories.problemReported("a03"))
            val surprise = useCase(fixed()).preview(RUN).surprises.single()
            triage.addSurprises(listOf(surprise))
            triage.saveVerdict(
                TriageVerdict(
                    surprise.id,
                    RUN,
                    v1.id,
                    TriageCategory.SCENARIO_BUG,
                    "əvvəlki cavab",
                    0.7,
                    surprise.evidence.refs,
                    ProposedChange("s", listOf(YamlEdit(readTask, readTaskFixed)), ProposalStatus.PENDING),
                    "scripted",
                    clock.now().wall,
                ),
            )
            val llm = fixed()

            val report = useCase(llm).execute(RUN)

            llm.requests.shouldBeEmpty()
            report.draft.shouldNotBeNull().yaml shouldBe MINI_YAML.replace(readTask, readTaskFixed)
            report.items
                .single()
                .verdict
                ?.proposedChange
                ?.status shouldBe ProposalStatus.DRAFTED
        }

    // ---- failures, budget and resumption -----------------------------------------------------------------------------

    @Test
    fun `an invalid answer is stored as a failure and the other surprises are still triaged`() =
        runTest {
            givenRun(RunStories.problemReported("a03"), RunStories.failedJoin("a04"))
            val invalid =
                buildJsonObject {
                    put("category", "NETWORK")
                    put("rationale", "x")
                    put("confidence", 0.5)
                }
            val llm = fixed(srp("read_announce", "a03") to invalid, srp("join", "a04") to answer(TriageCategory.MODEL_GAP))

            val report = useCase(llm).execute(RUN)

            val failed = report.items[0]
            failed.verdict shouldBe null
            failed.failure.shouldNotBeNull().reason shouldContain "invalid answer: 'category' must be one of"
            failed.failure.failedAt shouldBe clock.now().wall
            report.items[1]
                .verdict
                .shouldNotBeNull()
                .category shouldBe TriageCategory.MODEL_GAP
            report.items[1].failure shouldBe null
        }

    @Test
    fun `a model error on one question does not stop the others`() =
        runTest {
            givenRun(RunStories.problemReported("a03"), RunStories.failedJoin("a04"))
            val llm =
                model(
                    mapOf(
                        srp("read_announce", "a03") to { _ -> throw LlmException.Timeout("no answer within 120s") },
                        srp("join", "a04") to { _ -> answer(TriageCategory.MODEL_GAP) },
                    ),
                )

            val report = useCase(llm).execute(RUN)

            report.items[0]
                .failure
                .shouldNotBeNull()
                .reason shouldBe "LLM call failed (Timeout): no answer within 120s"
            report.items[1].verdict.shouldNotBeNull()
        }

    @Test
    fun `once the model is unavailable no further question is sent`() =
        runTest {
            givenRun(RunStories.problemReported("a03"), RunStories.failedJoin("a04"), RunStories.expectedRefusal(statusFails = true))
            val llm = ScriptedLlmClient { throw LlmException.Unavailable("not logged in") }

            val report = useCase(llm, TriageOptions(parallelism = 1)).execute(RUN)

            llm.requests shouldHaveSize 1
            report.items.map { it.failure?.reason } shouldBe
                listOf(
                    "LLM unavailable: not logged in",
                    "not asked: LLM unavailable: not logged in",
                    "not asked: LLM unavailable: not logged in",
                )
            report.items.map { it.verdict } shouldBe listOf(null, null, null)
        }

    @Test
    fun `executing again asks only about undecided surprises and retriage asks again`() =
        runTest {
            givenRun(RunStories.problemReported("a03"), RunStories.failedJoin("a04"))
            var fail = true
            val llm =
                model(
                    mapOf(
                        srp("read_announce", "a03") to { _ ->
                            if (fail) buildJsonObject { put("category", "?") } else answer(TriageCategory.SYSTEM_BUG)
                        },
                        srp("join", "a04") to { _ -> answer(TriageCategory.MODEL_GAP) },
                    ),
                )
            useCase(llm).execute(RUN)
            fail = false
            clock.advance(10.seconds)

            val second = useCase(llm).execute(RUN)

            llm.requests shouldHaveSize 3
            llm.requests.last().label shouldContain srp("read_announce", "a03").value
            second.asked shouldBe 1
            second.items.map { it.failure } shouldBe listOf(null, null)
            second.items.map { it.verdict?.category } shouldBe listOf(TriageCategory.SYSTEM_BUG, TriageCategory.MODEL_GAP)

            val third = useCase(llm).execute(RUN, retriage = true)

            third.asked shouldBe 2
            llm.requests shouldHaveSize 5
            triage.surprises(RUN) shouldHaveSize 2
        }

    @Test
    fun `the question budget defers the rest to the next execution`() =
        runTest {
            givenRun(RunStories.problemReported("a03"), RunStories.failedJoin("a04"))
            val llm =
                fixed(
                    srp("read_announce", "a03") to answer(TriageCategory.SYSTEM_BUG),
                    srp("join", "a04") to answer(TriageCategory.MODEL_GAP),
                )

            val first = useCase(llm, TriageOptions(maxQuestions = 1)).execute(RUN)

            first.asked shouldBe 1
            first.deferred shouldBe 1
            first.items.map { it.verdict != null } shouldBe listOf(true, false)

            val second = useCase(llm, TriageOptions(maxQuestions = 1)).execute(RUN)

            second.deferred shouldBe 0
            second.items.map { it.verdict != null } shouldBe listOf(true, true)
        }

    // ---- which scenario --------------------------------------------------------------------------------------------

    @Test
    fun `the run is matched to the version with its exact campaign text`() =
        runTest {
            val v1 = givenRun(RunStories.problemReported("a03"))
            val v2Yaml = MINI_YAML.replace(readTask, readTaskFixed)
            val v2 = catalog.createDraft(v2Yaml, ScenarioSource.USER, v1.id)
            val secondRun = RunId("run_2")
            evidence.create(ScenarioTestKit.run(hash = v2.sha256, runId = secondRun))
            val llm = ScriptedLlmClient { answer(TriageCategory.SYSTEM_BUG) }

            useCase(llm).preview(RUN).scenario shouldBe v1
            useCase(llm).preview(secondRun).scenario shouldBe v2
            useCase(llm).preview(RUN, scenarioId = v2.id).scenario shouldBe v2
        }

    @Test
    fun `a run, a scenario or a campaign text that cannot be found is a clear error`() =
        runTest {
            givenRun()
            evidence.create(ScenarioTestKit.run(hash = "f".repeat(64), runId = RunId("run_unknown_text")))
            val triageRun = useCase(fixed())

            shouldThrow<TriageRunNotFoundException> { triageRun.execute(RunId("run_missing")) }
            shouldThrow<ScenarioNotInCatalogException> { triageRun.execute(RunId("run_unknown_text")) }.message shouldContain
                "import the campaign file first"
            shouldThrow<ScenarioNotFoundException> { triageRun.execute(RUN, scenarioId = ScenarioVersionId("scn_404")) }
        }

    @Test
    fun `a preview shows surprises and ignored signals without asking or storing anything`() =
        runTest {
            givenRun(RunStories.problemReported("a03"), RunStories.expectedRefusal())
            val llm = fixed()

            val preview = useCase(llm).preview(RUN)

            preview.surprises.map { it.id } shouldBe listOf(srp("read_announce", "a03"))
            preview.ignored.map { it.reason }.distinct() shouldBe listOf(IgnoreReason.EXPECTED_REFUSAL)
            preview.run.runId shouldBe RUN
            llm.requests.shouldBeEmpty()
            triage.surprises(RUN).shouldBeEmpty()
        }

    @Test
    fun `many surprises triaged in parallel are reported in collection order`() =
        runTest {
            val agents = listOf("a01", "a02", "a03", "a04")
            givenRun(*agents.map { RunStories.problemReported(it) }.toTypedArray())
            val llm = ScriptedLlmClient { answer(TriageCategory.SYSTEM_BUG) }

            val report = useCase(llm, TriageOptions(parallelism = 4)).execute(RUN)

            report.items.map { it.surprise.agentId?.value } shouldBe agents
            report.items.all { it.verdict != null } shouldBe true
        }

    // ---- review fixes ------------------------------------------------------------------------------------------------

    @Test
    fun `a run that has not finished is previewed but never triaged`() =
        runTest {
            catalog.createDraft(MINI_YAML, ScenarioSource.USER)
            evidence.create(ScenarioTestKit.run(result = RunResult.RUNNING))
            RunStories.problemReported("a03").into(evidence)
            val llm = fixed()

            useCase(llm).preview(RUN).surprises shouldHaveSize 1
            shouldThrow<TriageRunNotFinishedException> { useCase(llm).execute(RUN) }.message shouldContain "has not finished"

            llm.requests.shouldBeEmpty()
            triage.surprises(RUN).shouldBeEmpty()
        }

    /** [agent] clicks 30 times, then its action runs out of steps; only the latest 25 steps are shown to the model. */
    private fun longAction(agent: String) =
        RunEvidence(
            steps =
                (1..30).map { i ->
                    step("stp_${agent}_t$i", agent, "read_announce", StepKind.DO, "click [$i]", StepStatus.PASSED, second = i.toLong())
                } +
                    step(
                        "stp_${agent}_t31",
                        agent,
                        "read_announce",
                        StepKind.DO,
                        "do: Bildirişləri aç və yeni elanı oxu",
                        StepStatus.FAILED,
                        "step_limit: not finished",
                        second = 31,
                    ),
            assertions = emptyList(),
            findings = emptyList(),
            artifacts = listOf(ScenarioTestKit.artifact("art_$agent", "stp_${agent}_t31")),
        )

    @Test
    fun `a verdict links only to evidence the question showed`() =
        runTest {
            givenRun(longAction("a03"), longAction("a04"))
            val llm =
                fixed(
                    srp("read_announce", "a03") to answer(TriageCategory.MODEL_GAP, listOf("stp_a03_t1")),
                    srp("read_announce", "a04") to answer(TriageCategory.MODEL_GAP, listOf("stp_a04_t1", "[stp_a04_t31]")),
                )

            val report = useCase(llm).execute(RUN)

            val onlyHidden = report.items[0].verdict.shouldNotBeNull()
            onlyHidden.basedOn shouldBe
                report.items[0]
                    .surprise.evidence.shownRefs
            onlyHidden.basedOn.map { it.id } shouldNotContainAnyOf listOf("stp_a03_t1", "stp_a03_t6")
            report.items[1]
                .verdict
                .shouldNotBeNull()
                .basedOn shouldBe
                listOf(EvidenceRef(EvidenceRefType.STEP, "stp_a04_t31"), EvidenceRef(EvidenceRefType.ARTIFACT, "art_a04"))
            llm.requests.forEach { it.messages.single().content shouldNotContain "[stp_a03_t1]" }
        }

    @Test
    fun `a change of the target line is refused even when a target override hides it`() =
        runTest {
            givenRun(RunStories.problemReported("a03"), RunStories.failedJoin("a04"))
            val overridden = ScenarioTestKit.validator(dir.resolve("work"), targetOverride = URI("https://override.kadrohr.test"))
            val llm =
                fixed(
                    srp("read_announce", "a03") to
                        answer(TriageCategory.MODEL_GAP, edits = listOf("https://staging.kadrohr.test" to "https://www.kadrohr.test")),
                    srp("join", "a04") to answer(TriageCategory.SCENARIO_BUG, edits = listOf(readTask to readTaskFixed)),
                )
            val useCase = TriageRunUseCase(llm, evidence, evidence, scenarios, triage, overridden, clock, ids)

            val report = useCase.execute(RUN)

            val target =
                report.items[0]
                    .verdict
                    ?.proposedChange
                    .shouldNotBeNull()
            target.status shouldBe ProposalStatus.REJECTED
            target.rejection.shouldNotBeNull() shouldContain "must keep: target"
            report.draft.shouldNotBeNull().yaml shouldBe MINI_YAML.replace(readTask, readTaskFixed)
        }

    @Test
    fun `a later execution of the same run builds on its first draft so the newest draft carries every change`() =
        runTest {
            givenRun(RunStories.problemReported("a03"), RunStories.failedJoin("a04"))
            val llm =
                fixed(
                    srp("read_announce", "a03") to answer(TriageCategory.SCENARIO_BUG, edits = listOf(readTask to readTaskFixed)),
                    srp("join", "a04") to answer(TriageCategory.MODEL_GAP, edits = listOf(announceTask to announceTaskFixed)),
                )
            val v2 = useCase(llm, TriageOptions(maxQuestions = 1)).execute(RUN).draft.shouldNotBeNull()

            val second = useCase(llm, TriageOptions(maxQuestions = 1)).execute(RUN)

            val v3 = second.draft.shouldNotBeNull()
            v3.parentId shouldBe v2.id
            v3.yaml shouldBe MINI_YAML.replace(readTask, readTaskFixed).replace(announceTask, announceTaskFixed)
            v3.note shouldContain "on top of mini v2"
            second.items.map { it.verdict?.proposedChange?.draftId } shouldBe listOf(v2.id, v3.id)
            val increment = catalog.diffFromParent(v3.id).shouldNotBeNull()
            increment.added shouldBe 1
            increment.unified() shouldContain "+    $announceTaskFixed"
            scenarios.history("mini").map { it.version } shouldBe listOf(1, 2, 3)
        }

    @Test
    fun `a change an earlier execution already drafted is linked to that draft, not drafted twice`() =
        runTest {
            givenRun(RunStories.problemReported("a03"), RunStories.problemReported("a04"))
            val llm = ScriptedLlmClient { answer(TriageCategory.SCENARIO_BUG, edits = listOf(readTask to readTaskFixed)) }
            val v2 = useCase(llm, TriageOptions(maxQuestions = 1)).execute(RUN).draft.shouldNotBeNull()

            val second = useCase(llm, TriageOptions(maxQuestions = 1)).execute(RUN)

            second.draft shouldBe v2
            second.items.map { it.verdict?.proposedChange?.draftId } shouldBe listOf(v2.id, v2.id)
            TriageResults(triage).forDraft(v2.id) shouldHaveSize 2
            scenarios.history("mini").map { it.version } shouldBe listOf(1, 2)
        }

    @Test
    fun `a draft stored by an interrupted execution is reused, not duplicated`() =
        runTest {
            val v1 = givenRun(RunStories.problemReported("a03"))
            val surprise = useCase(fixed()).preview(RUN).surprises.single()
            triage.addSurprises(listOf(surprise))
            triage.saveVerdict(
                TriageVerdict(
                    surprise.id,
                    RUN,
                    v1.id,
                    TriageCategory.SCENARIO_BUG,
                    "əvvəlki cavab",
                    0.7,
                    surprise.evidence.refs,
                    ProposedChange("s", listOf(YamlEdit(readTask, readTaskFixed)), ProposalStatus.PENDING),
                    "scripted",
                    clock.now().wall,
                ),
            )
            val stored = catalog.createDraft(MINI_YAML.replace(readTask, readTaskFixed), ScenarioSource.TRIAGE, v1.id, "interrupted")

            val report = useCase(fixed()).execute(RUN)

            report.draft shouldBe stored
            report.items
                .single()
                .verdict
                ?.proposedChange
                ?.draftId shouldBe stored.id
            scenarios.history("mini").map { it.version } shouldBe listOf(1, 2)
        }
}
