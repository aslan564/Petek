/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.demo

import az.petek.core.ids.AgentId
import az.petek.core.ids.IdGenerator
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTags
import az.petek.core.model.Role
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import az.petek.dashboard.application.DashboardEvidenceRecorder
import az.petek.dashboard.application.DashboardIdentityRepository
import az.petek.dashboard.application.DashboardRunRepository
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.domain.PlanStepView
import az.petek.dashboard.domain.RunPlanView
import az.petek.dashboard.domain.TaskState
import az.petek.dashboard.domain.TaskStateView
import az.petek.dashboard.testing.FakeScreens
import az.petek.dashboard.testing.FakeScreens.Page
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EventReceipt
import az.petek.evidence.domain.EventRecord
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.identity.domain.AzerbaijaniNameCatalog
import az.petek.identity.domain.DefaultIdentityRegistryGenerator
import az.petek.identity.domain.HmacPasswordDeriver
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentitySpec
import az.petek.identity.testing.InMemoryIdentityRepository
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A simulated run of the `kadrohr-core` campaign (scenarios/kadrohr.yaml) that drives a [LiveDashboard] exactly the way
 * the app will: through the monitor port, the orchestrator's plan and task reports, and the dashboard decorators around
 * an in-memory evidence store. [pause] lets time pass (a real delay in the demo, a fake clock advance in tests), so the
 * same script serves the manual demo ([play]) and the UI test ([populate]).
 */
class DemoRun(
    private val agentCount: Int,
    private val dashboard: LiveDashboard,
    private val artifacts: ArtifactStore,
    private val clock: HarnessClock,
    private val ids: IdGenerator,
    private val pause: suspend (millis: Long) -> Unit,
    seed: Long = 7,
) {
    /** Everything recorded, as the evidence store saw it. */
    val evidence = InMemoryEvidence()
    private val identityStore = InMemoryIdentityRepository()
    private val recorder = DashboardEvidenceRecorder(evidence, dashboard)
    private val runs = DashboardRunRepository(evidence, dashboard)
    private val identityRepository = DashboardIdentityRepository(identityStore, dashboard)
    private val random = Random(seed)
    private val screenshots = AtomicInteger()
    private var started: RunStart? = null

    private class RunStart(
        val runId: RunId,
        val agents: List<Identity>,
        val at: HarnessTimestamp,
    )

    val runId: RunId get() = begun().runId
    val agents: List<Identity> get() = begun().agents
    val admin: Identity get() = agents.first { it.role == Role.ADMIN }
    val managers: List<Identity> get() = agents.filter { it.role == Role.MANAGER }
    val employees: List<Identity> get() = agents.filter { it.role == Role.EMPLOYEE }

    /** The employee whose registration fails in every script (the tenth, when there is one). */
    val dropout: Identity? get() = employees.getOrNull(DROPOUT_INDEX)

    class Act(
        val action: String,
        val page: Page,
        val reason: String?,
        val kind: StepKind = StepKind.DO,
    )

    suspend fun start(runId: RunId = ids.runId()) {
        check(started == null) { "the demo run was already started" }
        val now = clock.now()
        runs.create(
            RunRecord(
                runId = runId,
                runTag = RunTags.forRun(runId),
                campaignName = CAMPAIGN,
                campaignHash = "demo",
                seed = SEED,
                target = TARGET,
                startedAt = now.wall,
            ),
        )
        val plan = registry().generate(spec(), RunTags.forRun(runId))
        identityRepository.replaceAll(runId, plan)
        val agents = plan.identities.sortedBy { it.agentId.index }
        started = RunStart(runId, agents, now)
        dashboard.runStarted(runId, agents.map { status(it, AgentState.IDLE, null, null) })
        dashboard.planReady(plan())
    }

    /** The orchestrator's plan of this run, the way it resolves kadrohr.yaml for these agents. */
    fun plan(): RunPlanView =
        RunPlanView(
            runId = runId,
            campaignName = CAMPAIGN,
            steps =
                listOf(
                    PlanStepView(OWNER_SIGNUP, true, "admin", ids(admin), "do", OWNER_SIGNUP_TEXT, null, null, false, emptyList()),
                    PlanStepView(SEED_STEP, true, "admin", ids(admin), "run", "seed_company", null, null, false, emptyList()),
                    PlanStepView(
                        JOIN,
                        true,
                        "employee[*] | manager[*]",
                        ids(managers + employees),
                        "run",
                        "register_and_login",
                        null,
                        null,
                        false,
                        emptyList(),
                    ),
                    PlanStepView(
                        ANNOUNCE,
                        false,
                        "admin",
                        ids(admin),
                        "do",
                        ANNOUNCE_TEXT,
                        ANNOUNCED,
                        null,
                        false,
                        listOf(ORACLE_PUBLISHED),
                    ),
                    PlanStepView(
                        READ,
                        false,
                        "employee[*]",
                        ids(employees),
                        "do",
                        "Bildirişləri aç və yeni elanı oxu",
                        null,
                        ANNOUNCED,
                        false,
                        listOf(
                            "visible_text 'Sabah 10:00 ümumi iclas' within 5s",
                            "latency_max 5000 ms",
                            "oracle receipts contains {self.email}",
                        ),
                    ),
                    PlanStepView(
                        TICKET,
                        false,
                        "employee[dept=IT, n=1]",
                        ids(itEmployees().take(1)),
                        "do",
                        TICKET_TEXT,
                        TICKET_CREATED,
                        null,
                        false,
                        emptyList(),
                    ),
                    PlanStepView(
                        TICKET_FLOW,
                        false,
                        "manager[IT]",
                        ids(listOfNotNull(itManager())),
                        "do",
                        "Ticketi in-progress et, sonra HR menecerinə assign et",
                        null,
                        TICKET_CREATED,
                        false,
                        listOf("oracle /test/tickets/{last_id} status = in_progress"),
                    ),
                    PlanStepView(
                        RACE,
                        false,
                        "[manager[IT], manager[HR]]",
                        ids(racers()),
                        "do",
                        "Eyni ticketi approve et",
                        null,
                        null,
                        true,
                        listOf("only_one_succeeds"),
                    ),
                    PlanStepView(
                        FORBIDDEN,
                        false,
                        "employee[dept=IT, n=2]",
                        ids(itEmployees().drop(1).take(2)),
                        "do",
                        "Ticketi approve etməyə çalış",
                        null,
                        null,
                        false,
                        listOf("not_visible [data-testid=\"ticket-approve\"]", "http_status POST /api/tickets/{last_id}/approve = 403"),
                    ),
                ),
        )

    fun scenarioStep(name: String) = dashboard.stepStarted(name)

    fun message(text: String) = dashboard.message(text)

    fun state(
        agent: Identity,
        state: AgentState,
        step: String?,
        lastAction: String? = null,
    ) = dashboard.agentUpdated(status(agent, state, step, lastAction))

    fun task(
        agent: Identity,
        step: String,
        state: TaskState,
        detail: String? = null,
    ) = dashboard.taskUpdated(TaskStateView(runId, step, agent.agentId, state, detail))

    /** One action of [agent] in [step]: WORKING while it runs, then the step record and a screenshot. */
    suspend fun act(
        agent: Identity,
        step: String,
        act: Act,
        status: StepStatus = StepStatus.PASSED,
        detail: String? = null,
        after: AgentState? = AgentState.IDLE,
        toast: String? = null,
    ) {
        val begin = clock.now()
        state(agent, AgentState.WORKING, step, act.action)
        pause(between(250, 1_400))
        val end = clock.now()
        val stepId = ids.stepId()
        recorder.step(
            StepRecord(
                stepId = stepId,
                runId = runId,
                agentId = agent.agentId,
                scenarioStep = step,
                kind = act.kind,
                action = act.action,
                llmReason = act.reason,
                startedAt = begin.wall,
                endedAt = end.wall,
                durationMs = begin.elapsedUntil(end).inWholeMilliseconds,
                status = status,
                detail = detail,
                correlationId = ids.correlationId(),
            ),
        )
        val error = detail?.takeIf { status != StepStatus.PASSED }?.substringBefore(':')
        val png = FakeScreens.png(act.page, agent.displayName, screenshots.getAndIncrement() % VARIANTS, toast, error)
        recorder.artifact(artifacts.write(runId, stepId, agent.agentId.value, ArtifactType.SCREENSHOT, png))
        if (after != null) {
            val summary = if (status == StepStatus.PASSED) "ok: ${act.action}" else "${detail ?: status.name.lowercase()}: ${act.action}"
            state(agent, after, step, summary)
        }
    }

    suspend fun event(
        emitter: Identity,
        name: String,
        objectId: String,
    ): EventRecord {
        val record = EventRecord(ids.eventId(), runId, name, emitter.agentId, objectId, "oracle", "{}", clock.now().wall)
        recorder.event(record)
        return record
    }

    /** [receiver] sees [event] after [latencyMs] (or misses it when null); a visible_text assertion follows either way. */
    suspend fun receive(
        receiver: Identity,
        event: EventRecord,
        step: String,
        latencyMs: Long?,
    ) {
        val seen = latencyMs != null
        recorder.receipt(
            EventReceipt(event.eventId, runId, receiver.agentId, seen, latencyMs?.let { event.t0.plusMillis(it) }, latencyMs),
        )
        recorder.assertion(
            AssertionRecord(
                stepId = ids.stepId(),
                runId = runId,
                agentId = receiver.agentId,
                scenarioStep = step,
                type = "visible_text",
                source = EvidenceSource.RECEIVER,
                expected = "'Sabah 10:00 ümumi iclas' 5 s ərzində görünür",
                observed = if (seen) "göründü" else "görünmədi",
                verdict = if (seen) Verdict.PASSED else Verdict.FAILED,
                latencyMs = latencyMs,
                note = null,
                artifactIds = emptyList(),
            ),
        )
    }

    suspend fun oracleCheck(
        agent: Identity,
        step: String,
        passed: Boolean,
    ) = recorder.assertion(
        AssertionRecord(
            stepId = ids.stepId(),
            runId = runId,
            agentId = agent.agentId,
            scenarioStep = step,
            type = "oracle",
            source = EvidenceSource.ORACLE,
            expected = "/test/announcements/{last_id} status = published",
            observed = if (passed) "published" else "draft",
            verdict = if (passed) Verdict.PASSED else Verdict.FAILED,
            latencyMs = null,
            note = null,
            artifactIds = emptyList(),
        ),
    )

    suspend fun finding(
        agent: Identity?,
        findingClass: FindingClass,
        step: String,
        a: String?,
        b: String?,
        c: String?,
        note: String,
    ) {
        val proof = agent?.let { evidence.artifactList.lastOrNull { record -> record.relativePath.contains("/${it.agentId}/") } }
        recorder.finding(
            FindingRecord(
                ids.findingId(),
                runId,
                null,
                step,
                agent?.agentId,
                findingClass,
                a,
                b,
                c,
                note,
                listOfNotNull(proof?.artifactId),
            ),
        )
    }

    fun finish(
        outcome: RunOutcome,
        reportDirectory: String? = null,
    ) {
        agents.forEach { if (it != dropout) dashboard.agentUpdated(status(it, AgentState.DONE, null, null)) }
        dashboard.runFinished(
            RunSummary(
                runId = runId,
                outcome = outcome,
                stepsPassed = evidence.stepList.count { it.status == StepStatus.PASSED },
                stepsFailed = evidence.stepList.count { it.status != StepStatus.PASSED },
                assertionsFailed = evidence.assertionList.count { it.verdict == Verdict.FAILED },
                failedAgents = if (dropout == null) 0 else 1,
                reportDirectory = reportDirectory,
                durationMs = begun().at.elapsedUntil(clock.now()).inWholeMilliseconds,
            ),
        )
    }

    /**
     * A deterministic, lively moment of a run, one agent after the other: the company exists, everybody joined (one
     * failed), the admin announced, and the employees are in the middle of reading it (most saw it, two missed it, a
     * few are still reading or waiting, one is stuck).
     */
    suspend fun populate(runId: RunId = ids.runId()) {
        start(runId)
        setup()
        announce()
        scenarioStep(READ)
        val announced = evidence.eventList.last()
        readers().forEachIndexed { i, agent ->
            when {
                i % MISS_EVERY == MISS_AT -> {
                    missed(agent, announced)
                }

                i % READ_KINDS == 1 && i > 2 -> {
                    task(agent, READ, TaskState.RUNNING)
                    act(agent, READ, READ_ACTS.first(), after = AgentState.WORKING)
                }

                i % READ_KINDS == 3 && i > 2 -> {
                    task(agent, READ, TaskState.WAITING_EVENT, "wait_for $ANNOUNCED")
                    state(agent, AgentState.WAITING, READ, "wait_for $ANNOUNCED")
                }

                else -> {
                    read(agent, announced, 280L + (i * 137L) % 2_300)
                }
            }
        }
        readers().getOrNull(2)?.let { stuck(it) }
        deliveryFindings()
        managers.forEach { state(it, AgentState.IDLE, JOIN, "ok: run register_and_login") }
    }

    /** The whole campaign with every agent acting at once and real pauses, for watching the panel move. */
    suspend fun play(runId: RunId = ids.runId()) =
        coroutineScope {
            start(runId)
            scenarioStep(OWNER_SIGNUP)
            task(admin, OWNER_SIGNUP, TaskState.RUNNING)
            for (act in OWNER_ACTS) act(admin, OWNER_SIGNUP, act, after = AgentState.WORKING)
            task(admin, OWNER_SIGNUP, TaskState.PASSED)
            scenarioStep(SEED_STEP)
            task(admin, SEED_STEP, TaskState.RUNNING)
            act(admin, SEED_STEP, Act("run seed_company", Page.ANNOUNCEMENTS, null, StepKind.RUN))
            task(admin, SEED_STEP, TaskState.PASSED)
            scenarioStep(JOIN)
            (managers + employees)
                .mapIndexed { i, agent ->
                    launch {
                        pause(between(0, 4_000))
                        register(agent, if (i % 3 == 0) Page.JOIN else Page.LOGIN)
                    }
                }.joinAll()
            pause(1_200)
            scenarioStep(ANNOUNCE)
            readers().forEach {
                task(it, READ, TaskState.WAITING_EVENT, "wait_for $ANNOUNCED")
                state(it, AgentState.WAITING, READ, "wait_for $ANNOUNCED")
            }
            val announced = announce()
            scenarioStep(READ)
            readers()
                .mapIndexed { i, agent ->
                    launch {
                        if (i % MISS_EVERY == MISS_AT) {
                            pause(5_000)
                            missed(agent, announced)
                        } else {
                            val latency = between(250, 3_600)
                            pause(latency)
                            read(agent, announced, latency)
                        }
                    }
                }.joinAll()
            deliveryFindings()
            pause(1_200)
            tickets()
        }

    private suspend fun setup() {
        scenarioStep(OWNER_SIGNUP)
        task(admin, OWNER_SIGNUP, TaskState.RUNNING)
        for (act in OWNER_ACTS) act(admin, OWNER_SIGNUP, act, after = AgentState.WORKING)
        task(admin, OWNER_SIGNUP, TaskState.PASSED)
        scenarioStep(SEED_STEP)
        act(admin, SEED_STEP, Act("run seed_company", Page.ANNOUNCEMENTS, null, StepKind.RUN))
        task(admin, SEED_STEP, TaskState.PASSED)
        scenarioStep(JOIN)
        (managers + employees).forEachIndexed { i, agent -> register(agent, if (i % 3 == 0) Page.JOIN else Page.LOGIN) }
    }

    private suspend fun announce(): EventRecord {
        task(admin, ANNOUNCE, TaskState.RUNNING)
        for (act in ANNOUNCE_ACTS) act(admin, ANNOUNCE, act, after = AgentState.WORKING)
        val announced = event(admin, ANNOUNCED, "184")
        oracleCheck(admin, ANNOUNCE, passed = true)
        task(admin, ANNOUNCE, TaskState.PASSED)
        state(admin, AgentState.IDLE, ANNOUNCE, "ok: elan dərc olundu")
        return announced
    }

    private suspend fun register(
        agent: Identity,
        page: Page,
    ) {
        val act = Act("run register_and_login", page, null, StepKind.RUN)
        task(agent, JOIN, TaskState.RUNNING)
        if (agent != dropout) {
            act(agent, JOIN, act)
            task(agent, JOIN, TaskState.PASSED)
            return
        }
        act(agent, JOIN, act, StepStatus.FAILED, "verification_code_missing: e-poçt kodu 60 s-də gəlmədi", after = null)
        task(agent, JOIN, TaskState.FAILED, "verification_code_missing")
        state(agent, AgentState.FAILED, JOIN, "failed setup: verification_code_missing")
        message("${agent.agentId} failed setup step '$JOIN' (verification_code_missing) and is excluded from later steps")
        plan()
            .steps
            .filterNot {
                it.setup
            }.filter { agent.agentId in it.agentIds }
            .forEach { task(agent, it.id, TaskState.SKIPPED, "setup failed") }
    }

    private suspend fun read(
        agent: Identity,
        announced: EventRecord,
        latencyMs: Long,
    ) {
        task(agent, READ, TaskState.RUNNING)
        receive(agent, announced, READ, latencyMs)
        act(agent, READ, READ_ACTS.last(), toast = "Yeni elan: Sabah 10:00 ümumi iclas")
        task(agent, READ, TaskState.PASSED)
    }

    private suspend fun missed(
        agent: Identity,
        announced: EventRecord,
    ) {
        task(agent, READ, TaskState.WAITING_EVENT, "wait_for $ANNOUNCED")
        receive(agent, announced, READ, null)
        task(agent, READ, TaskState.FAILED, "not_received: 5 s ərzində görünmədi")
        state(agent, AgentState.IDLE, READ, "not_received: $ANNOUNCED")
    }

    private suspend fun stuck(agent: Identity) {
        task(agent, READ, TaskState.RUNNING)
        act(agent, READ, READ_ACTS.first(), StepStatus.BLOCKED, "no_progress: 120 s ərzində irəliləyiş yoxdur", after = AgentState.BLOCKED)
        task(agent, READ, TaskState.BLOCKED, "no_progress: 120 s")
    }

    private suspend fun tickets() =
        coroutineScope {
            val reporter = itEmployees().firstOrNull() ?: return@coroutineScope
            scenarioStep(TICKET)
            task(reporter, TICKET, TaskState.RUNNING)
            for (act in TICKET_ACTS) act(reporter, TICKET, act, after = AgentState.WORKING)
            event(reporter, TICKET_CREATED, "57")
            task(reporter, TICKET, TaskState.PASSED)
            state(reporter, AgentState.IDLE, TICKET, "ok: ticket göndərildi")
            itManager()?.let { manager ->
                scenarioStep(TICKET_FLOW)
                task(manager, TICKET_FLOW, TaskState.RUNNING)
                for (act in MANAGER_ACTS) act(manager, TICKET_FLOW, act, after = AgentState.WORKING)
                task(manager, TICKET_FLOW, TaskState.PASSED)
            }
            scenarioStep(RACE)
            racers()
                .mapIndexed { i, manager ->
                    launch {
                        task(manager, RACE, TaskState.RUNNING)
                        act(manager, RACE, Act("click [14] \"Təsdiqlə\"", Page.TASKS, "Ticketi təsdiqləyirəm"))
                        task(
                            manager,
                            RACE,
                            if (i ==
                                0
                            ) {
                                TaskState.PASSED
                            } else {
                                TaskState.LOST_RACE
                            },
                            if (i == 0) null else "digər menecer daha tez təsdiqlədi",
                        )
                    }
                }.joinAll()
            scenarioStep(FORBIDDEN)
            itEmployees()
                .drop(1)
                .take(2)
                .map { agent ->
                    launch {
                        task(agent, FORBIDDEN, TaskState.RUNNING)
                        act(agent, FORBIDDEN, Act("navigate /tickets/57", Page.TASKS, "Ticketi açıb təsdiq düyməsini axtarıram"))
                        task(agent, FORBIDDEN, TaskState.PASSED)
                    }
                }.joinAll()
        }

    private suspend fun deliveryFindings() {
        val missed = readers().filterIndexed { i, _ -> i % MISS_EVERY == MISS_AT }
        missed.take(2).forEach {
            finding(
                it,
                FindingClass.DELIVERY_UI,
                READ,
                "${admin.agentId} elanı dərc etdi (t0)",
                "${it.agentId}: 5 s ərzində görünmədi",
                "oracle: elan mövcuddur (published)",
                "Elan backend-də var, amma ${it.displayName} onu ekranda görmədi.",
            )
        }
        finding(
            null,
            FindingClass.INVESTIGATE,
            READ,
            "${admin.agentId} elanı dərc etdi",
            "3 alıcı elanı 2 s-dən gec gördü",
            "oracle: elan mövcuddur",
            "Gecikmə yüksəkdir; real-time kanalı (SSE) yoxlanmalıdır.",
        )
    }

    private fun readers(): List<Identity> = employees.filter { it != dropout }

    private fun itEmployees(): List<Identity> = employees.filter { it.department == "IT" && it != dropout }

    private fun itManager(): Identity? = managers.firstOrNull { it.department == "IT" }

    private fun racers(): List<Identity> = listOfNotNull(itManager(), managers.firstOrNull { it.department == "HR" })

    private fun ids(identity: Identity): List<AgentId> = listOf(identity.agentId)

    private fun ids(identities: List<Identity>): List<AgentId> = identities.map { it.agentId }.sortedBy { it.index }

    private fun between(
        from: Long,
        until: Long,
    ): Long = synchronized(random) { random.nextLong(from, until) }

    private fun begun(): RunStart = checkNotNull(started) { "call start() first" }

    private fun status(
        agent: Identity,
        state: AgentState,
        step: String?,
        lastAction: String?,
    ) = AgentStatus(agent.agentId, agent.displayName, agent.role.key, state, step, lastAction, clock.now())

    private fun registry() = DefaultIdentityRegistryGenerator(AzerbaijaniNameCatalog, HmacPasswordDeriver("demo-secret".toByteArray()))

    private fun spec(): IdentitySpec {
        val managers = minOf(DEPARTMENTS.size, maxOf(0, (agentCount - 1 + MANAGER_EVERY - 1) / MANAGER_EVERY))
        val employees = agentCount - 1 - managers
        val invite = maxOf(managers, (managers + employees + 1) / 2)
        return IdentitySpec(
            testers = agentCount,
            seed = SEED,
            names = listOf("Əli", "Vəli", "Sahil", "Cəmil", "Amil"),
            admins = 1,
            managers = managers,
            employees = employees,
            departments = DEPARTMENTS,
            inviteCount = invite,
            companyCodeCount = agentCount - 1 - invite,
            mailDomain = "test.kadrohr.az",
        )
    }

    companion object {
        const val CAMPAIGN = "kadrohr-core"
        const val TARGET = "https://staging.kadrohr.az"
        const val OWNER_SIGNUP = "owner_signup"
        const val SEED_STEP = "seed"
        const val JOIN = "join"
        const val ANNOUNCE = "announce"
        const val READ = "read_announce"
        const val TICKET = "ticket"
        const val TICKET_FLOW = "ticket_flow"
        const val RACE = "race"
        const val FORBIDDEN = "forbidden"
        const val ANNOUNCED = "announcement_created"
        const val TICKET_CREATED = "ticket_created"

        private const val OWNER_SIGNUP_TEXT = "Qeydiyyatdan keç, email kodunu təsdiqlə, 'Pətək Test MMC' adlı şirkət yarat"
        private const val ANNOUNCE_TEXT = "Elan yarat: 'Sabah 10:00 ümumi iclas'"
        private const val TICKET_TEXT = "IT departamentinə ticket yaz: 'Noutbuk işləmir'"
        private const val ORACLE_PUBLISHED = "oracle /test/announcements/{last_id} status = published"
        private val DEPARTMENTS = listOf("IT", "HR", "Satış", "Maliyyə", "Əməliyyat")
        private const val SEED = 42L
        private const val VARIANTS = 5
        private const val DROPOUT_INDEX = 9
        private const val MISS_EVERY = 11
        private const val MISS_AT = 4
        private const val READ_KINDS = 5
        private const val MANAGER_EVERY = 6

        val OWNER_ACTS =
            listOf(
                Act("navigate /register", Page.LOGIN, "Qeydiyyat səhifəsini açıram"),
                Act("type [4] \"{self.email}\"", Page.LOGIN, "E-poçtu yazıram"),
                Act("click [9] \"Şirkət yarat\"", Page.JOIN, "Şirkəti 'Pətək Test MMC' adı ilə yaradıram"),
            )

        val ANNOUNCE_ACTS =
            listOf(
                Act("navigate /announcements", Page.ANNOUNCEMENTS, "Elanlar səhifəsini açıram"),
                Act("click [12] \"Elan yarat\"", Page.NEW_ANNOUNCEMENT, "Yeni elan formunu açmaq lazımdır"),
                Act("type [15] \"Sabah 10:00 ümumi iclas\"", Page.NEW_ANNOUNCEMENT, "Başlığı tapşırıqdakı kimi yazıram"),
                Act("click [21] \"Dərc et\"", Page.ANNOUNCEMENTS, "Form hazırdır, dərc edirəm"),
            )

        val READ_ACTS =
            listOf(
                Act("navigate /notifications", Page.NOTIFICATIONS, "Bildirişləri yoxlayıram"),
                Act("read_text [3] \"Sabah 10:00 ümumi iclas\"", Page.NOTIFICATIONS, "Yeni elanı açıb oxuyuram"),
            )

        val TICKET_ACTS =
            listOf(
                Act("navigate /tickets", Page.TASKS, "Tapşırıqlar bölməsinə keçirəm"),
                Act("click [7] \"Yeni ticket\"", Page.TASKS, "Yeni ticket yaratmalıyam"),
                Act("type [9] \"Noutbuk işləmir\"", Page.TASKS, "Problemi qısa təsvir edirəm"),
                Act("click [11] \"Göndər\"", Page.TASKS, "Ticketi IT-yə göndərirəm"),
            )

        val MANAGER_ACTS =
            listOf(
                Act("click [4] \"İcraya götür\"", Page.TASKS, "Şöbəmin ticketini icraya götürürəm"),
                Act("select [6] \"HR menecer\"", Page.TASKS, "Ticketi HR menecerinə təyin edirəm"),
            )
    }
}
