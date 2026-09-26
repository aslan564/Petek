/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.demo

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import az.petek.dashboard.domain.CapacityLimit
import az.petek.dashboard.domain.CapacityView
import az.petek.dashboard.domain.DiffView
import az.petek.dashboard.domain.ExplorationEventView
import az.petek.dashboard.domain.ExplorationPhase
import az.petek.dashboard.domain.ExplorationStatus
import az.petek.dashboard.domain.ExplorationView
import az.petek.dashboard.domain.FindingView
import az.petek.dashboard.domain.PanelBackend
import az.petek.dashboard.domain.PanelBudget
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.PhaseProgress
import az.petek.dashboard.domain.PhaseState
import az.petek.dashboard.domain.RunPlanView
import az.petek.dashboard.domain.RunRequest
import az.petek.dashboard.domain.RunStartView
import az.petek.dashboard.domain.RunSummaryView
import az.petek.dashboard.domain.ScenarioSource
import az.petek.dashboard.domain.ScenarioStatus
import az.petek.dashboard.domain.ScenarioVersionView
import az.petek.dashboard.domain.ScenarioView
import az.petek.dashboard.domain.SiteModelDiffView
import az.petek.dashboard.domain.SiteModelView
import az.petek.dashboard.domain.StabilityView
import az.petek.dashboard.domain.StepStabilityView
import az.petek.dashboard.domain.TeardownView
import az.petek.dashboard.domain.TriageCategory
import az.petek.dashboard.domain.TriageVerdictView
import az.petek.dashboard.domain.TriageView
import az.petek.dashboard.domain.VisitedPageView
import az.petek.dashboard.testing.FakeScreens
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.RunResult
import az.petek.orchestration.domain.RunOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A [PanelBackend] with realistic simulated data, so every screen of the panel can be seen working without the
 * explorer, scenarios or orchestration modules: an explorer walking a KadroHR-like site, versioned scenarios with a
 * triage proposal, a run history with a repeat group, and runs started through [runStarter] (the demo plays a
 * simulated run on the live board). Long work runs in [scope]; one exploration and one run at a time.
 *
 * [reportRoot] is where demo reports are written, `<root>/<runId>/report/`; it should be the artifact store's root so
 * the reports' relative evidence links resolve.
 */
class DemoPanelBackend(
    private val clock: HarnessClock,
    private val ids: IdGenerator,
    private val artifacts: ArtifactStore,
    private val scope: CoroutineScope,
    private val pause: suspend (millis: Long) -> Unit,
    private val reportRoot: Path,
    private val runStarter: suspend (request: RunRequest, runId: RunId) -> RunOutcome,
) : PanelBackend {
    private val lock = Mutex()
    private val state = MutableStateFlow<ExplorationView?>(null)
    private var exploration: Walk? = null
    private var explorationJob: Job? = null
    private val explorationArtifacts = ConcurrentHashMap<ArtifactId, ArtifactRecord>()
    private val scenarioList = mutableListOf<ScenarioView>()
    private val runList = mutableListOf<RunSummaryView>()
    private val triages = mutableMapOf<RunId, TriageView>()
    private var runJob: Job? = null

    /** A walk over [DemoContent.VISITS]: how far it got, what the owner answered, and the screenshots it took. */
    private class Walk(
        val id: String,
        val instructions: PanelInstructions,
        val startedAt: HarnessTimestamp,
        val visits: List<DemoVisit>,
        var step: Int = 0,
        var status: ExplorationStatus = ExplorationStatus.RUNNING,
        var endedAt: HarnessTimestamp? = null,
        val shots: MutableList<ArtifactId?> = mutableListOf(),
        val times: MutableList<Instant> = mutableListOf(),
        val answers: MutableMap<String, String> = mutableMapOf(),
        var appended: String = "",
    )

    override val explorationUpdates: Flow<ExplorationView> = state.filterNotNull()

    // --- seed -----------------------------------------------------------------------------------------------------

    /**
     * Fills the demo with its starting data: scenarios, past runs (with reports and a triage) and, unless
     * [liveExploration], a half-way exploration frozen in time (for screenshots and tests).
     */
    suspend fun seed(liveExploration: Boolean = false) {
        val now = clock.now()
        lock.withLock {
            seedScenarios(now.wall)
            seedRuns(now.wall)
        }
        if (!liveExploration) seedExploration(now)
    }

    private fun seedScenarios(now: Instant) {
        val byVersion = mutableMapOf<Pair<String, Int>, String>()
        for (s in DemoContent.SCENARIOS) {
            val id = "scn_${s.name.substringAfter("kadrohr-")}_v${s.version}"
            byVersion[s.name to s.version] = id
            val created = now.minus(Duration.ofDays(s.daysAgo)).minus(Duration.ofHours(3))
            val status = ScenarioStatus.valueOf(s.status)
            val approved = if (status == ScenarioStatus.DRAFT) null else created.plus(Duration.ofHours(2))
            val frozen = if (status == ScenarioStatus.FROZEN) created.plus(Duration.ofDays(1)) else null
            scenarioList +=
                ScenarioView(
                    ScenarioVersionView(
                        id,
                        s.name,
                        s.version,
                        status,
                        ScenarioSource.valueOf(s.source),
                        s.parentVersion?.let { byVersion[s.name to it] },
                        s.note,
                        created,
                        approved,
                        frozen,
                    ),
                    s.yaml,
                )
        }
    }

    private suspend fun seedRuns(now: Instant) {
        data class Past(
            val suffix: String,
            val scenario: String,
            val testers: Int,
            val result: RunResult,
            val minutes: Long,
            val daysAgo: Long,
            val group: String?,
            val index: Int?,
            val findings: Int,
        )
        val past =
            listOf(
                Past("0924c", "scn_core_v2", 30, RunResult.FAILED, 18, 0, "rg-0924", 3, 3),
                Past("0924b", "scn_core_v2", 30, RunResult.PASSED, 16, 0, "rg-0924", 2, 1),
                Past("0924a", "scn_core_v2", 30, RunResult.PASSED, 17, 0, "rg-0924", 1, 0),
                Past("0921", "scn_core_v1", 30, RunResult.PASSED, 15, 3, null, null, 0),
                Past("0917", "scn_qeydiyyat_v2", 10, RunResult.PASSED, 4, 7, null, null, 0),
                Past("0914", "scn_qeydiyyat_v1", 10, RunResult.ABORTED, 2, 11, null, null, 1),
            )
        past.forEachIndexed { i, p ->
            val runId = RunId("run_demo_${p.suffix}")
            val started = now.minus(Duration.ofDays(p.daysAgo)).minus(Duration.ofHours(2L + i))
            val scenario = scenarioList.first { it.version.id == p.scenario }.version
            val steps = if (p.testers == 30) 214 else 38
            val failed =
                if (p.result == RunResult.FAILED) {
                    9
                } else if (p.result == RunResult.ABORTED) {
                    3
                } else {
                    0
                }
            val report = p.result != RunResult.ABORTED
            if (report) writeReport(runId, scenario.name, p.result, p.testers)
            runList +=
                RunSummaryView(
                    runId = runId,
                    campaignName = scenario.name,
                    target = DemoRun.TARGET,
                    startedAt = started,
                    endedAt = started.plus(Duration.ofMinutes(p.minutes)),
                    durationMs = Duration.ofMinutes(p.minutes).toMillis() + i * 13_000L,
                    result = p.result,
                    testers = p.testers,
                    stepsPassed = steps - failed,
                    stepsFailed = failed,
                    assertionsPassed = if (p.testers == 30) 96 - failed else 12,
                    assertionsFailed = failed / 3,
                    findings = p.findings,
                    inputTokens = if (p.testers == 30) 1_620_000L + i * 41_000 else 210_000L,
                    outputTokens = if (p.testers == 30) 214_000L + i * 7_000 else 31_000L,
                    costUsd = null,
                    repeatGroup = p.group,
                    repeatIndex = p.index,
                    reportAvailable = report,
                    triaged = p.result == RunResult.FAILED,
                    scenarioId = scenario.id,
                )
        }
        val failedRun = RunId("run_demo_0924c")
        triages[failedRun] = triageOf(failedRun)
    }

    private fun triageOf(runId: RunId): TriageView =
        TriageView(
            runId = runId,
            scenarioId = "scn_core_v2",
            verdicts =
                listOf(
                    TriageVerdictView(
                        surpriseId = "srp_1",
                        scenarioStep = DemoRun.READ,
                        agentId =
                            az.petek.core.ids
                                .AgentId("a15"),
                        surpriseKind = "FAILED_STEP",
                        surprise = "a15 elanı 5 s ərzində görmədi; səhifəni yeniləyəndən sonra göründü.",
                        category = TriageCategory.SYSTEM_BUG,
                        rationale =
                            "Oracle elanı 'published' göstərir və a15-in SSE axınında announcement hadisəsi yoxdur: " +
                                "real-time çatdırılma bu istifadəçiyə işləməyib.",
                        confidence = 0.86,
                        proposedChange = null,
                        proposalScenarioId = null,
                        evidence = emptyList(),
                    ),
                    TriageVerdictView(
                        surpriseId = "srp_2",
                        scenarioStep = DemoRun.TICKET,
                        agentId =
                            az.petek.core.ids
                                .AgentId("a07"),
                        surpriseKind = "PROBLEM_REPORTED",
                        surprise = "'Yeni ticket' düyməsi tapılmadı; səhifədə 'Müraciət yarat' düyməsi var.",
                        category = TriageCategory.MODEL_GAP,
                        rationale = "Düymənin mətni dəyişib, funksiya eynidir; sayt modeli və ssenari mətni köhnədir.",
                        confidence = 0.92,
                        proposedChange =
                            "- do: \"IT departamentinə ticket yaz: 'Noutbuk işləmir'\"\n" +
                                "+ do: \"'Müraciət yarat' ilə IT-yə müraciət yaz: 'Noutbuk işləmir'\"",
                        proposalScenarioId = "scn_core_v3",
                        evidence = emptyList(),
                    ),
                    TriageVerdictView(
                        surpriseId = "srp_3",
                        scenarioStep = DemoRun.RACE,
                        agentId =
                            az.petek.core.ids
                                .AgentId("a03"),
                        surpriseKind = "FAILED_STEP",
                        surprise = "only_one_succeeds: hər iki menecerin təsdiqi uğurlu göründü.",
                        category = TriageCategory.SCENARIO_BUG,
                        rationale =
                            "İkinci menecer ticketi birincidən 2,4 s sonra açıb; ssenari yarışı eyni anda başlatmır. " +
                                "'parallel: true' var, amma 'wait_for: ticket_created' yoxdur.",
                        confidence = 0.64,
                        proposedChange = "  - id: race\n+   wait_for: ticket_created\n    parallel: true",
                        proposalScenarioId = null,
                        evidence = emptyList(),
                    ),
                ),
        )

    /** Writes a small demo report for [runId] (`index.html`, `report.md`, one linked screenshot) and returns its directory. */
    suspend fun writeReport(
        runId: RunId,
        campaign: String,
        result: RunResult,
        testers: Int,
    ): Path {
        val shot =
            artifacts.write(
                runId,
                StepId("stp_demo"),
                "a01",
                ArtifactType.SCREENSHOT,
                FakeScreens.png(FakeScreens.Page.ANNOUNCEMENTS, "Admin"),
            )
        val link = "../" + shot.relativePath.substringAfter("${runId.value}/")
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = Files.createDirectories(reportRoot.resolve(runId.value).resolve("report"))
            Files.writeString(dir.resolve("report.md"), "# $campaign\n\nRun `${runId.value}`: ${result.name}, $testers tester.\n")
            Files.writeString(
                dir.resolve("index.html"),
                """
                <!doctype html><html lang="az"><head><meta charset="utf-8"><title>$campaign — hesabat</title>
                <style>body{font:15px/1.5 system-ui,sans-serif;margin:40px auto;max-width:860px;color:#0f172a}
                .b{display:inline-block;padding:3px 10px;border-radius:99px;font-weight:700;background:#e2e8f0}
                img{max-width:100%;border:1px solid #e2e8f0;border-radius:12px}</style></head><body>
                <h1>$campaign</h1><p><span class="b">${result.name}</span> · run <code>${runId.value}</code> · $testers tester</p>
                <p>Demo hesabatı: əsl hesabat reporting modulunun yazdığı Markdown və HTML-dir.</p>
                <img src="$link" alt="Admin ekranı"></body></html>
                """.trimIndent(),
            )
            dir
        }
    }

    private suspend fun seedExploration(now: HarnessTimestamp) {
        val instructions = defaultInstructions()
        val walk = Walk("exp_demo_1", instructions, now.minus(Duration.ofSeconds(SEEDED_SECONDS)), visitsFor(instructions))
        for (i in 0 until SEEDED_STEP) {
            walk.shots += screenshot(walk, i)
            walk.times += walk.startedAt.wall.plusSeconds((i + 1) * SEEDED_SECONDS / (SEEDED_STEP + 1))
        }
        walk.step = SEEDED_STEP
        walk.answers["u_2"] = DemoContent.UNKNOWNS.first { it.id == "u_2" }.answer!!
        lock.withLock { exploration = walk }
        publish(walk, now)
    }

    // --- capacity -------------------------------------------------------------------------------------------------

    override suspend fun capacity(testers: Int): CapacityView {
        val total = 16_384L
        val available = 11_776L
        val reserve = maxOf(2_048L, total * 15 / 100)
        val perSession = 180.0 + 350.0 / CONTEXTS_PER_BROWSER
        val memoryBound = ((available - reserve) / perSession).toInt()
        val cpuBound = CORES * SESSIONS_PER_CORE
        return CapacityView(
            requested = testers,
            recommended = maxOf(1, minOf(memoryBound, cpuBound)),
            limitingFactor = if (memoryBound <= cpuBound) CapacityLimit.MEMORY else CapacityLimit.CPU,
            availableMemoryMb = available,
            totalMemoryMb = total,
            cpuCores = CORES,
            measured = false,
            notes =
                listOf(
                    "Hər sessiya ~180 MB, hər paylaşılan brauzer ~350 MB (təxmini; ölçmək üçün: petek capacity --measure).",
                    "Əməliyyat sistemi üçün ${reserve / 1024} GB boş saxlanılır.",
                    "LLM ötürmə qabiliyyəti tester sayını yox, sürəti məhdudlaşdırır.",
                ),
        )
    }

    // --- exploration ----------------------------------------------------------------------------------------------

    override fun exploration(): ExplorationView? = state.value

    override suspend fun startExploration(instructions: PanelInstructions): ExplorationView {
        val (walk, job) =
            lock.withLock {
                if (explorationJob?.isActive == true) throw PanelConflictException("Kəşfiyyat artıq gedir; yenisi üçün onu dayandırın.")
                val walk = Walk(ids.runId().value.replace("run_", "exp_"), instructions, clock.now(), visitsFor(instructions))
                val job =
                    scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            walk(walk)
                        } finally {
                            withContext(NonCancellable) { finishWalk(walk) }
                        }
                    }
                exploration = walk
                explorationJob = job
                walk to job
            }
        publish(walk, clock.now())
        job.start()
        return checkNotNull(state.value)
    }

    private suspend fun walk(walk: Walk) {
        for (i in walk.visits.indices) {
            pause(WALK_PAUSE_MILLIS + (i % 3) * 400L)
            val shot = screenshot(walk, i)
            lock.withLock {
                walk.shots += shot
                walk.times += clock.now().wall
                walk.step = i + 1
            }
            publish(walk, clock.now())
        }
        lock.withLock { walk.status = ExplorationStatus.FINISHED }
    }

    private suspend fun finishWalk(walk: Walk) {
        lock.withLock {
            if (walk.status == ExplorationStatus.RUNNING) walk.status = ExplorationStatus.CANCELLED
            walk.endedAt = clock.now()
        }
        publish(walk, clock.now())
    }

    override suspend fun answerUnknown(
        unknownId: String,
        answer: String,
    ): ExplorationView {
        val walk =
            lock.withLock {
                val walk = exploration ?: throw PanelNotFoundException("Kəşfiyyat yoxdur.")
                val question = DemoContent.UNKNOWNS.firstOrNull { it.id == unknownId } ?: throw PanelNotFoundException("Belə sual yoxdur.")
                if (walk.answers.containsKey(unknownId)) throw PanelConflictException("Bu suala artıq cavab verilib.")
                walk.answers[unknownId] = answer
                walk.appended += "\n\nSual: ${question.question}\nCavab: $answer"
                walk
            }
        publish(walk, clock.now())
        return checkNotNull(state.value)
    }

    override suspend fun cancelExploration(): Boolean {
        val job = lock.withLock { explorationJob?.takeIf { it.isActive } } ?: return false
        job.cancel()
        job.join()
        return true
    }

    override suspend fun compareWithPrevious(): SiteModelDiffView? =
        state.value?.let { SiteModelDiffView(it.model.version - 1, it.model.version, DemoContent.MODEL_CHANGES) }

    override suspend fun explorationArtifact(artifactId: ArtifactId): ArtifactRecord? = explorationArtifacts[artifactId]

    private suspend fun screenshot(
        walk: Walk,
        index: Int,
    ): ArtifactId {
        val visit = walk.visits[index]
        val user = if (visit.visitedAs == "anonymous") "Qonaq" else ROLE_NAMES.getValue(visit.visitedAs)
        val png = FakeScreens.png(visit.screen, user, index, error = if (visit.httpStatus >= 400) "HTTP ${visit.httpStatus}" else null)
        val record = artifacts.write(RunId(walk.id), StepId("stp_explore_$index"), "explorer", ArtifactType.SCREENSHOT, png)
        explorationArtifacts[record.artifactId] = record
        return record.artifactId
    }

    private suspend fun publish(
        walk: Walk,
        now: HarnessTimestamp,
    ) {
        state.value = lock.withLock { viewOf(walk, now) }
    }

    private fun viewOf(
        walk: Walk,
        now: HarnessTimestamp,
    ): ExplorationView {
        val seen = walk.visits.take(walk.step)
        val patterns = seen.map { pattern(it.path) }.toSet()
        val pages = DemoContent.PAGES.filter { it.urlPattern in patterns }
        val paths = seen.map { it.path }.toSet()
        val running = walk.status == ExplorationStatus.RUNNING
        val visited =
            seen.indices.reversed().map { i ->
                val v = seen[i]
                VisitedPageView(
                    DemoRun.TARGET + v.path,
                    v.title,
                    v.visitedAs,
                    v.httpStatus,
                    v.loadMs,
                    walk.shots.getOrNull(i),
                    walk.times[i],
                )
            }
        val unknowns =
            DemoContent.UNKNOWNS
                .filter { UNKNOWN_AFTER.getValue(it.id) in paths }
                .map { it.copy(answer = walk.answers[it.id]) }
        val draftReady = walk.step >= DRAFT_AFTER || !running
        return ExplorationView(
            id = walk.id,
            target = walk.instructions.target,
            instructions = walk.instructions.instructions + walk.appended,
            status = walk.status,
            startedAt = walk.startedAt.wall,
            elapsedMs = walk.startedAt.elapsedUntil(walk.endedAt ?: now).inWholeMilliseconds,
            budget = walk.instructions.budget,
            phases = phases(walk),
            currentPage = visited.firstOrNull(),
            visited = visited,
            model =
                SiteModelView(
                    MODEL_VERSION,
                    pages,
                    if ("/announcements" in paths &&
                        seen.any { it.visitedAs != "anonymous" }
                    ) {
                        DemoContent.REALTIME
                    } else {
                        emptyList()
                    },
                ),
            findings = DemoContent.FINDINGS.filter { it.first in paths }.map { it.second },
            unknowns = unknowns,
            ideas = if (pages.size >= IDEAS_AFTER_PAGES) DemoContent.IDEAS else emptyList(),
            draftYaml = if (draftReady && pages.size >= IDEAS_AFTER_PAGES) DemoContent.DRAFT_YAML else null,
            previousModelVersion = MODEL_VERSION - 1,
            activity = activity(walk, seen, draftReady && pages.size >= IDEAS_AFTER_PAGES),
            message = if (walk.status == ExplorationStatus.CANCELLED) "Dayandırıldı; o ana qədər öyrənilənlər saxlanılıb." else null,
        )
    }

    private fun phases(walk: Walk): List<PhaseProgress> {
        val seen = walk.visits.take(walk.step)
        val current = seen.lastOrNull()?.phase
        val done = walk.status != ExplorationStatus.RUNNING
        return ExplorationPhase.entries.map { phase ->
            val count = seen.count { it.phase == phase }
            val planned = walk.visits.any { it.phase == phase }
            val state =
                when {
                    !planned -> PhaseState.SKIPPED
                    walk.status == ExplorationStatus.FINISHED -> PhaseState.DONE
                    count == 0 -> if (done) PhaseState.SKIPPED else PhaseState.PENDING
                    phase == current && !done -> PhaseState.RUNNING
                    else -> PhaseState.DONE
                }
            val roles = seen.filter { it.phase == phase && it.visitedAs != "anonymous" }.map { it.visitedAs }.distinct()
            PhaseProgress(phase, state, count, if (phase == ExplorationPhase.ROLE_BASED) roles else emptyList())
        }
    }

    private fun activity(
        walk: Walk,
        seen: List<DemoVisit>,
        draftReady: Boolean,
    ): List<ExplorationEventView> {
        val events = mutableListOf<ExplorationEventView>()
        var phase: ExplorationPhase? = null
        seen.forEachIndexed { i, visit ->
            val at = walk.times[i]
            if (visit.phase != phase) {
                phase = visit.phase
                events += ExplorationEventView(at.minusMillis(1), "PHASE_STARTED", PHASE_NAMES.getValue(visit.phase))
            }
            events +=
                ExplorationEventView(at, "PAGE_VISITED", "${visit.path} · ${ROLE_WORDS.getValue(visit.visitedAs)} · ${visit.httpStatus}")
            DemoContent.FINDINGS.filter { it.first == visit.path && seen.indexOfFirst { v -> v.path == visit.path } == i }.forEach {
                events += ExplorationEventView(at.plusMillis(1), "FINDING_RECORDED", it.second.detail)
            }
            DemoContent.UNKNOWNS
                .filter {
                    UNKNOWN_AFTER.getValue(it.id) == visit.path &&
                        seen.indexOfFirst { v -> v.path == visit.path } == i
                }.forEach {
                    events += ExplorationEventView(at.plusMillis(2), "UNKNOWN_RAISED", it.question)
                }
        }
        if (draftReady) {
            events +=
                ExplorationEventView(
                    (walk.times.lastOrNull() ?: walk.startedAt.wall).plusMillis(3),
                    "DRAFT_READY",
                    "Ssenari layihəsi hazırdır (4 test ideyası)",
                )
        }
        if (walk.status == ExplorationStatus.FINISHED) {
            events +=
                ExplorationEventView(
                    (walk.endedAt ?: walk.startedAt).wall,
                    "FINISHED",
                    "${seen.size} səhifə, ${DemoContent.PAGES.size} səhifə modeli",
                )
        }
        return events.sortedByDescending { it.at }.take(ACTIVITY_LIMIT)
    }

    private fun visitsFor(instructions: PanelInstructions): List<DemoVisit> =
        DemoContent.VISITS
            .filter { instructions.allowWrites || it.phase != ExplorationPhase.TRIAL_TOUCH }
            .take(instructions.budget.maxPages)

    // --- scenarios ------------------------------------------------------------------------------------------------

    override suspend fun generateScenario(): ScenarioView {
        val draft =
            state.value?.draftYaml
                ?: throw PanelConflictException("Kəşfiyyatın hələ ssenari layihəsi yoxdur; kəşfiyyat bir az da irəliləsin.")
        return lock.withLock {
            val name = "kadrohr-explored"
            val version = (scenarioList.filter { it.version.name == name }.maxOfOrNull { it.version.version } ?: 0) + 1
            val view =
                ScenarioView(
                    ScenarioVersionView(
                        "scn_explored_v$version",
                        name,
                        version,
                        ScenarioStatus.DRAFT,
                        ScenarioSource.EXPLORER,
                        null,
                        "Kəşfiyyatçının modelindən (v$MODEL_VERSION) yaradılıb; 4 ideya əhatə olunub, 3-ü növbəyə qalıb.",
                        clock.now().wall,
                        null,
                        null,
                    ),
                    draft,
                )
            scenarioList += view
            view
        }
    }

    override suspend fun scenarios(): List<ScenarioVersionView> =
        lock.withLock {
            scenarioList.map { it.version }.sortedWith(
                compareBy<ScenarioVersionView> { it.name }.thenByDescending { it.version },
            )
        }

    override suspend fun scenario(id: String): ScenarioView? = lock.withLock { scenarioList.firstOrNull { it.version.id == id } }

    override suspend fun diff(
        fromId: String,
        toId: String,
    ): DiffView {
        val (from, to) =
            lock.withLock {
                val from = scenarioList.firstOrNull { it.version.id == fromId } ?: throw PanelNotFoundException("Ssenari tapılmadı.")
                val to = scenarioList.firstOrNull { it.version.id == toId } ?: throw PanelNotFoundException("Ssenari tapılmadı.")
                from to to
            }
        return DiffView(from.version, to.version, LineDiff.diff(from.yaml, to.yaml))
    }

    override suspend fun approve(id: String): ScenarioVersionView =
        lock.withLock {
            val target = scenarioList.firstOrNull { it.version.id == id } ?: throw PanelNotFoundException("Ssenari tapılmadı.")
            if (target.version.status != ScenarioStatus.DRAFT) throw PanelConflictException("Yalnız qaralama təsdiqlənə bilər.")
            val now = clock.now().wall
            scenarioList.replaceAll {
                when {
                    it.version.id == id -> {
                        it.copy(version = it.version.copy(status = ScenarioStatus.APPROVED, approvedAt = now))
                    }

                    it.version.name == target.version.name && it.version.status == ScenarioStatus.APPROVED -> {
                        it.copy(version = it.version.copy(status = ScenarioStatus.SUPERSEDED))
                    }

                    else -> {
                        it
                    }
                }
            }
            scenarioList.first { it.version.id == id }.version
        }

    override suspend fun freeze(id: String): ScenarioVersionView =
        lock.withLock {
            val target = scenarioList.firstOrNull { it.version.id == id } ?: throw PanelNotFoundException("Ssenari tapılmadı.")
            if (target.version.status !=
                ScenarioStatus.APPROVED
            ) {
                throw PanelConflictException("Yalnız təsdiqlənmiş versiya dondurula bilər.")
            }
            val frozen = target.copy(version = target.version.copy(status = ScenarioStatus.FROZEN, frozenAt = clock.now().wall))
            scenarioList[scenarioList.indexOf(target)] = frozen
            frozen.version
        }

    override suspend fun runPlan(scenarioId: String): RunPlanView? {
        val scenario = scenario(scenarioId) ?: return null
        return DemoPlans.preview(scenario.version.name, scenario.yaml)
    }

    // --- runs -----------------------------------------------------------------------------------------------------

    override suspend fun startRun(request: RunRequest): RunStartView {
        val (job, start) =
            lock.withLock {
                if (runJob?.isActive == true) throw PanelConflictException("Artıq bir run gedir; bir anda yalnız bir run ola bilər.")
                val scenario =
                    scenarioList.firstOrNull { it.version.id == request.scenarioId }?.version
                        ?: throw PanelNotFoundException("Ssenari tapılmadı.")
                if (!scenario.runnable) throw PanelConflictException("Yalnız təsdiqlənmiş və ya dondurulmuş ssenari run oluna bilər.")
                val runId = ids.runId()
                val testers = request.testers ?: DEFAULT_TESTERS
                val summary =
                    RunSummaryView(
                        runId,
                        scenario.name,
                        DemoRun.TARGET,
                        clock.now().wall,
                        null,
                        null,
                        RunResult.RUNNING,
                        testers,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        null,
                        null,
                        null,
                        false,
                        false,
                        scenario.id,
                    )
                runList.add(0, summary)
                val job =
                    scope.launch(start = CoroutineStart.LAZY) {
                        var result = RunResult.ABORTED
                        try {
                            result = resultOf(runStarter(request, runId))
                        } finally {
                            withContext(NonCancellable) { finishRun(runId, result) }
                        }
                    }
                runJob = job
                job to RunStartView(runId, scenario.id, testers)
            }
        job.start()
        return start
    }

    private suspend fun finishRun(
        runId: RunId,
        result: RunResult,
    ) {
        lock.withLock {
            val index = runList.indexOfFirst { it.runId == runId }
            if (index < 0) return@withLock
            val run = runList[index]
            val ended = clock.now().wall
            val report = Files.isDirectory(reportRoot.resolve(runId.value).resolve("report"))
            runList[index] =
                run.copy(
                    result = result,
                    endedAt = ended,
                    durationMs = Duration.between(run.startedAt, ended).toMillis(),
                    reportAvailable = report,
                )
        }
    }

    override suspend fun cancelRun(): Boolean {
        val job = lock.withLock { runJob?.takeIf { it.isActive } } ?: return false
        job.cancel()
        job.join()
        return true
    }

    override suspend fun runs(): List<RunSummaryView> = lock.withLock { runList.sortedByDescending { it.startedAt } }

    override suspend fun stability(repeatGroup: String): StabilityView? {
        val runs = lock.withLock { runList.filter { it.repeatGroup == repeatGroup }.sortedBy { it.repeatIndex } }
        if (runs.isEmpty()) return null
        val flaky = mapOf(DemoRun.READ to 2, DemoRun.RACE to 2)
        return StabilityView(
            repeatGroup,
            runs.map { it.runId },
            STEPS.map { StepStabilityView(it, runs.size, flaky[it] ?: runs.size) },
        )
    }

    override suspend fun triage(runId: RunId): TriageView? = lock.withLock { triages[runId] }

    override suspend fun runTriage(runId: RunId): TriageView =
        lock.withLock {
            val run = runList.firstOrNull { it.runId == runId } ?: throw PanelNotFoundException("Run tapılmadı.")
            if (run.result == RunResult.RUNNING) throw PanelConflictException("Run hələ bitməyib; triaj bitmiş run üçündür.")
            val view = TriageView(runId, run.scenarioId, triageOf(runId).verdicts.take(1))
            triages[runId] = view
            runList[runList.indexOf(run)] = run.copy(triaged = true)
            view
        }

    override suspend fun reportDirectory(runId: RunId): Path? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            reportRoot.resolve(runId.value).resolve("report").takeIf { Files.isDirectory(it) }
        }

    override suspend fun findings(runId: RunId): List<FindingView> = emptyList()

    override suspend fun teardown(runId: RunId): TeardownView =
        lock.withLock {
            val run = runList.firstOrNull { it.runId == runId } ?: throw PanelNotFoundException("Run tapılmadı.")
            if (run.result == RunResult.RUNNING) throw PanelConflictException("Run hələ bitməyib.")
            TeardownView(runId, listOf("company demo-${runId.value.takeLast(4)}"), emptyList())
        }

    private fun resultOf(outcome: RunOutcome): RunResult =
        when (outcome) {
            RunOutcome.PASSED -> RunResult.PASSED
            RunOutcome.FAILED -> RunResult.FAILED
            RunOutcome.ABORTED -> RunResult.ABORTED
        }

    companion object {
        const val DEFAULT_TESTERS = 30
        private const val CORES = 8
        private const val SESSIONS_PER_CORE = 6
        private const val CONTEXTS_PER_BROWSER = 8
        private const val MODEL_VERSION = 4
        private const val SEEDED_STEP = 16
        private const val SEEDED_SECONDS = 252L
        private const val DRAFT_AFTER = 14
        private const val IDEAS_AFTER_PAGES = 6
        private const val ACTIVITY_LIMIT = 40
        private const val WALK_PAUSE_MILLIS = 1_600L
        private val STEPS =
            listOf(
                DemoRun.OWNER_SIGNUP,
                DemoRun.SEED_STEP,
                DemoRun.JOIN,
                DemoRun.ANNOUNCE,
                DemoRun.READ,
                DemoRun.TICKET,
                DemoRun.TICKET_FLOW,
                DemoRun.RACE,
                DemoRun.FORBIDDEN,
            )
        private val ROLE_NAMES = mapOf("admin" to "Aysel (admin)", "manager" to "Əli (menecer)", "employee" to "Günel (işçi)")
        private val ROLE_WORDS = mapOf("anonymous" to "anonim", "admin" to "admin", "manager" to "menecer", "employee" to "işçi")
        private val PHASE_NAMES =
            mapOf(
                ExplorationPhase.ANONYMOUS to "Anonim gəzinti",
                ExplorationPhase.ROLE_BASED to "Rollarla gəzinti",
                ExplorationPhase.TRIAL_TOUCH to "Sınaq toxunuşu",
            )

        /** The page after which each question comes up. */
        private val UNKNOWN_AFTER = mapOf("u_1" to "/tickets/57", "u_2" to "/announcements/new", "u_3" to "/employees")

        private fun pattern(path: String): String =
            path.split('/').joinToString("/") {
                if (it.isNotEmpty() &&
                    it.all(Char::isDigit)
                ) {
                    "{id}"
                } else {
                    it
                }
            }

        fun defaultInstructions() =
            PanelInstructions(
                target = DemoRun.TARGET,
                instructions =
                    "Elan yaratma axınını yoxla: admin elan yaradır, bütün işçilər onu 5 saniyə ərzində canlı görməlidir. " +
                        "Ticket, menecerin təsdiqi və icazələri də vacibdir. Ödəniş bölməsinə toxunma.",
                testers = DEFAULT_TESTERS,
                roles =
                    az.petek.dashboard.domain
                        .RoleSplit(1, 5, 24),
                departments = listOf("IT", "HR", "Satış", "Maliyyə", "Əməliyyat"),
                registration =
                    az.petek.dashboard.domain
                        .RegistrationSplit(15, 14),
                budget = PanelBudget(maxMinutes = 30, maxStepsPerAgent = 60, maxPages = 40),
                allowWrites = true,
            )
    }
}

private fun HarnessTimestamp.minus(duration: Duration): HarnessTimestamp =
    HarnessTimestamp(
        wall.minus(duration),
        monotonicNanos - duration.toNanos(),
    )
