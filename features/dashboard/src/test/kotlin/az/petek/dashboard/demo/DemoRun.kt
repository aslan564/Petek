package az.petek.dashboard.demo

import az.petek.core.ids.IdGenerator
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTags
import az.petek.core.model.Role
import az.petek.core.time.HarnessClock
import az.petek.dashboard.application.DashboardEvidenceRecorder
import az.petek.dashboard.application.DashboardIdentityRepository
import az.petek.dashboard.application.DashboardRunRepository
import az.petek.dashboard.application.LiveDashboard
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
 * A simulated KadroHR run that drives a [LiveDashboard] exactly the way the app will: through the monitor port and
 * the dashboard decorators around an in-memory evidence store. [pause] lets time pass (a real delay in the demo, a fake
 * clock advance in tests), so the same script serves the manual demo ([play]) and the UI test ([populate]).
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
        val at: az.petek.core.time.HarnessTimestamp,
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

    suspend fun start() {
        check(started == null) { "the demo run was already started" }
        val runId = ids.runId()
        val now = clock.now()
        runs.create(
            RunRecord(
                runId = runId,
                runTag = RunTags.forRun(runId),
                campaignName = "KadroHR · elan və tapşırıq axını",
                campaignHash = "demo",
                seed = SEED,
                target = "https://staging.kadrohr.az",
                startedAt = now.wall,
            ),
        )
        val plan = registry().generate(spec(), RunTags.forRun(runId))
        identityRepository.replaceAll(runId, plan)
        val agents = plan.identities.sortedBy { it.agentId.index }
        started = RunStart(runId, agents, now)
        dashboard.runStarted(runId, agents.map { status(it, AgentState.IDLE, null, null) })
    }

    fun scenarioStep(name: String) = dashboard.stepStarted(name)

    fun message(text: String) = dashboard.message(text)

    fun state(
        agent: Identity,
        state: AgentState,
        step: String?,
        lastAction: String? = null,
    ) = dashboard.agentUpdated(status(agent, state, step, lastAction))

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
        val record = EventRecord(ids.eventId(), runId, name, emitter.agentId, objectId, "url_regex", "{}", clock.now().wall)
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
                expected = "'Yeni iş qrafiki' 10s ərzində görünür",
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
            expected = "/test/announcements count == 1",
            observed = if (passed) "1" else "0",
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
     * A deterministic, lively moment of a run, one agent after the other: everybody signed up (one failed), the admin
     * announced, employees received it (some did not), and the team is in the middle of creating tickets.
     */
    suspend fun populate() {
        start()
        scenarioStep(REGISTER)
        agents.forEachIndexed { i, agent -> register(agent, if (i % 3 == 0) Page.JOIN else Page.LOGIN) }
        scenarioStep(ANNOUNCE)
        for (act in ANNOUNCE_ACTS) act(admin, ANNOUNCE, act)
        val announced = event(admin, "announcement_created", "184")
        oracleCheck(admin, ANNOUNCE, passed = true)
        managers.forEach { act(it, ANNOUNCE, READ_ACTS.first()) }
        scenarioStep(RECEIVE)
        receivers().forEachIndexed { i, agent ->
            if (i % MISS_EVERY == MISS_AT) {
                state(agent, AgentState.WAITING, RECEIVE, "wait_for announcement_created")
                receive(agent, announced, RECEIVE, null)
                state(agent, AgentState.IDLE, RECEIVE, "not_received: announcement_created")
            } else {
                receive(agent, announced, RECEIVE, 280L + (i * 137L) % 2_300)
                act(agent, RECEIVE, READ_ACTS.last(), toast = "Yeni elan: Yeni iş qrafiki")
            }
        }
        deliveryFindings()
        scenarioStep(TICKETS)
        receivers().forEachIndexed { i, agent -> ticket(agent, i) }
        receivers().getOrNull(2)?.let { blocked(it) }
        managers.forEachIndexed { i, manager ->
            act(manager, TICKETS, MANAGER_ACTS[i % MANAGER_ACTS.size], after = if (i % 2 == 0) AgentState.WORKING else AgentState.WAITING)
        }
        state(admin, AgentState.WAITING, TICKETS, "wait_for ticket_created")
    }

    /** The same story with every agent acting at once and real pauses, for watching the board move. */
    suspend fun play() =
        coroutineScope {
            start()
            scenarioStep(REGISTER)
            agents
                .mapIndexed { i, agent ->
                    launch {
                        pause(between(0, 4_000))
                        register(agent, if (i % 3 == 0) Page.JOIN else Page.LOGIN)
                    }
                }.joinAll()
            pause(1_500)
            scenarioStep(ANNOUNCE)
            receivers().forEach { state(it, AgentState.WAITING, RECEIVE, "wait_for announcement_created") }
            for (act in ANNOUNCE_ACTS) act(admin, ANNOUNCE, act, after = AgentState.WORKING)
            val announced = event(admin, "announcement_created", "184")
            oracleCheck(admin, ANNOUNCE, passed = true)
            state(admin, AgentState.IDLE, ANNOUNCE, "ok: announcement published")
            scenarioStep(RECEIVE)
            val readers =
                receivers().mapIndexed { i, agent ->
                    launch {
                        if (i % MISS_EVERY == MISS_AT) {
                            pause(10_000)
                            receive(agent, announced, RECEIVE, null)
                            state(agent, AgentState.IDLE, RECEIVE, "not_received: announcement_created")
                        } else {
                            val latency = between(250, 3_000)
                            pause(latency)
                            receive(agent, announced, RECEIVE, latency)
                            act(agent, RECEIVE, READ_ACTS.last(), toast = "Yeni elan: Yeni iş qrafiki")
                        }
                    }
                } + managers.map { launch { act(it, ANNOUNCE, READ_ACTS.first()) } }
            readers.joinAll()
            deliveryFindings()
            pause(1_500)
            scenarioStep(TICKETS)
            val workers =
                receivers().mapIndexed { i, agent ->
                    launch {
                        pause(between(0, 2_500))
                        ticket(agent, i)
                        if (i == 2) blocked(agent)
                        pause(between(500, 2_000))
                        state(agent, AgentState.DONE, TICKETS, "ok: ticket flow")
                    }
                } +
                    managers.mapIndexed { i, manager ->
                        launch {
                            repeat(3) {
                                pause(between(1_500, 4_000))
                                act(manager, TICKETS, MANAGER_ACTS[(i + it) % MANAGER_ACTS.size], after = AgentState.WAITING)
                            }
                        }
                    }
            workers.joinAll()
        }

    private suspend fun register(
        agent: Identity,
        page: Page,
    ) {
        val act = Act("run register_and_login", page, null, StepKind.RUN)
        if (agent != dropout) return act(agent, REGISTER, act)
        act(agent, REGISTER, act, StepStatus.FAILED, "verification_code_missing: e-poçt kodu 60 s-də gəlmədi", after = null)
        state(agent, AgentState.FAILED, REGISTER, "failed setup: verification_code_missing")
        message("${agent.agentId} failed setup step '$REGISTER' (verification_code_missing) and is excluded from later steps")
    }

    private suspend fun ticket(
        agent: Identity,
        i: Int,
    ) {
        when (i % TICKET_KINDS) {
            0, 1, 2 -> {
                act(agent, TICKETS, TICKET_ACTS[0])
                act(agent, TICKETS, TICKET_ACTS[1 + i % 3], after = AgentState.WORKING)
            }

            3 -> {
                act(agent, TICKETS, TICKET_ACTS[0])
                state(agent, AgentState.WAITING, TICKETS, "wait_for ticket_assigned")
            }

            4 -> {
                act(agent, TICKETS, TICKET_ACTS[0])
            }

            else -> {
                act(agent, TICKETS, TICKET_ACTS[3], StepStatus.FAILED, "assertion_failed: 'Tapşırıq göndərildi' görünmədi")
            }
        }
    }

    private suspend fun blocked(agent: Identity) =
        act(agent, TICKETS, TICKET_ACTS[2], StepStatus.BLOCKED, "no_progress: 120 s ərzində irəliləyiş yoxdur", after = AgentState.BLOCKED)

    private suspend fun deliveryFindings() {
        val missed = receivers().filterIndexed { i, _ -> i % MISS_EVERY == MISS_AT }
        missed.take(2).forEach {
            finding(
                it,
                FindingClass.DELIVERY_UI,
                RECEIVE,
                "${admin.agentId} elanı dərc etdi (t0)",
                "${it.agentId}: 10 s ərzində görünmədi",
                "oracle: elan mövcuddur",
                "Elan backend-də var, amma ${it.displayName} onu ekranda görmədi.",
            )
        }
        finding(
            null,
            FindingClass.INVESTIGATE,
            RECEIVE,
            "${admin.agentId} elanı dərc etdi",
            "3 alıcı elanı 2 s-dən gec gördü",
            "oracle: elan mövcuddur",
            "Gecikmə yüksəkdir; real-time kanalı yoxlanmalıdır.",
        )
    }

    private fun receivers(): List<Identity> = employees.filter { it != dropout }

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
        val departments = listOf("Satış", "Maliyyə", "İnsan resursları", "IT", "Marketinq")
        val managers = minOf(departments.size, maxOf(0, (agentCount - 1) / MANAGER_EVERY))
        val employees = agentCount - 1 - managers
        val invite = managers + employees / 2
        return IdentitySpec(
            testers = agentCount,
            seed = SEED,
            names = emptyList(),
            admins = 1,
            managers = managers,
            employees = employees,
            departments = departments,
            inviteCount = invite,
            companyCodeCount = agentCount - 1 - invite,
            mailDomain = "test.kadrohr.az",
        )
    }

    companion object {
        const val REGISTER = "register"
        const val ANNOUNCE = "announce"
        const val RECEIVE = "receive_announcement"
        const val TICKETS = "create_ticket"

        private const val SEED = 42L
        private const val VARIANTS = 5
        private const val DROPOUT_INDEX = 9
        private const val MISS_EVERY = 11
        private const val MISS_AT = 4
        private const val TICKET_KINDS = 6
        private const val MANAGER_EVERY = 6

        val ANNOUNCE_ACTS =
            listOf(
                Act("navigate /announcements", Page.ANNOUNCEMENTS, "Elanlar səhifəsini açıram"),
                Act("click [12] \"Elan yarat\"", Page.NEW_ANNOUNCEMENT, "Yeni elan formunu açmaq lazımdır"),
                Act("type [15] \"Yeni iş qrafiki\"", Page.NEW_ANNOUNCEMENT, "Başlığı tapşırıqdakı kimi yazıram"),
                Act("select [18] \"Hamısı\"", Page.NEW_ANNOUNCEMENT, "Elan bütün şöbələrə getməlidir"),
                Act("click [21] \"Dərc et\"", Page.ANNOUNCEMENTS, "Form hazırdır, dərc edirəm"),
            )

        val READ_ACTS =
            listOf(
                Act("navigate /notifications", Page.NOTIFICATIONS, "Bildirişləri yoxlayıram"),
                Act("wait_text \"Yeni iş qrafiki\"", Page.NOTIFICATIONS, null, StepKind.WAIT),
            )

        val TICKET_ACTS =
            listOf(
                Act("navigate /tickets", Page.TASKS, "Tapşırıqlar bölməsinə keçirəm"),
                Act("click [7] \"Yeni tapşırıq\"", Page.TASKS, "Yeni tapşırıq yaratmalıyam"),
                Act("type [9] \"Printer işləmir\"", Page.TASKS, "Problemi qısa təsvir edirəm"),
                Act("click [11] \"Göndər\"", Page.TASKS, "Tapşırığı göndərirəm"),
            )

        val MANAGER_ACTS =
            listOf(
                Act("click [4] \"İcraya götür\"", Page.TASKS, "Şöbəmin tapşırığını icraya götürürəm"),
                Act("read_text [6]", Page.TASKS, "Tapşırığın detallarını oxuyuram"),
            )
    }
}
