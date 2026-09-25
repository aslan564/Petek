package az.petek.orchestration.testing

import az.petek.agent.application.TesterAgent
import az.petek.agent.application.TesterAgentFactory
import az.petek.agent.domain.ActionOutcome
import az.petek.agent.domain.ActionStatus
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.SharedRunState
import az.petek.agent.domain.StepContext
import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.BrowserEngineConfig
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.browser.testing.FakeBrowserSession
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.TemplateContext
import az.petek.campaign.domain.TemplateException
import az.petek.campaign.domain.TemplateRenderer
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.core.time.HarnessClock
import az.petek.core.time.HarnessTimestamp
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityRegistryGenerator
import az.petek.identity.domain.IdentitySpec
import az.petek.oracle.domain.JsonFieldSelector
import az.petek.orchestration.application.RunFinalizer
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.PublishedEvent
import az.petek.orchestration.domain.RunPlan
import az.petek.orchestration.domain.RunSummary
import az.petek.orchestration.domain.TaskState
import az.petek.orchestration.domain.TaskUpdate
import az.petek.verification.application.VerifyStepUseCase
import az.petek.verification.domain.ActorResult
import az.petek.verification.domain.AssertionInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/** Harness clock that follows the test scheduler's virtual time, so durations measured by the runner are real. */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    private val origin: Instant = Instant.parse("2026-01-01T10:00:00Z"),
) : HarnessClock {
    override fun now(): HarnessTimestamp {
        val millis = scheduler.currentTime
        return HarnessTimestamp(origin.plusMillis(millis), millis * 1_000_000)
    }
}

/** a01 = admin; managers one per department; employees round-robin; non-admins alternate invite/company code. */
class SimpleIdentityGenerator : IdentityRegistryGenerator {
    val specs = CopyOnWriteArrayList<IdentitySpec>()

    override fun generate(
        spec: IdentitySpec,
        runTag: RunTag,
    ): IdentityPlan {
        specs += spec
        val identities = mutableListOf<Identity>()

        fun add(
            role: Role,
            department: String?,
        ) {
            val n = identities.size + 1
            val registration =
                when {
                    role == Role.ADMIN -> RegistrationMode.OWNER
                    n % 2 == 0 -> RegistrationMode.INVITE
                    else -> RegistrationMode.COMPANY_CODE
                }
            identities +=
                Identity(
                    agentId = AgentId.of(n),
                    displayName = "Tester $n",
                    email = "t$n.$runTag@${spec.mailDomain}",
                    password = Secret("secret-$n"),
                    phone = "+99450" + n.toString().padStart(7, '0'),
                    role = role,
                    department = department,
                    registration = registration,
                )
        }
        repeat(spec.admins) { add(Role.ADMIN, null) }
        repeat(spec.managers) { add(Role.MANAGER, spec.departments[it % spec.departments.size]) }
        repeat(spec.employees) { add(Role.EMPLOYEE, spec.departments[it % spec.departments.size]) }
        return IdentityPlan(runTag, identities)
    }
}

/** `{last_id}`, `{self.x}`, `{event.x.id}`; anything unresolvable throws like the real renderer. */
class SimpleTemplateRenderer : TemplateRenderer {
    private val placeholder = Regex("\\{([a-z_][a-z0-9_.]*)}")

    override fun render(
        template: String,
        context: TemplateContext,
    ): String =
        placeholder.replace(template) { match ->
            val name = match.groupValues[1]
            resolve(name, context) ?: throw TemplateException("Cannot resolve placeholder {$name}")
        }

    override fun placeholders(template: String): Set<String> = placeholder.findAll(template).map { it.groupValues[1] }.toSet()

    private fun resolve(
        name: String,
        context: TemplateContext,
    ): String? =
        when {
            name == "last_id" -> context.lastId
            name.startsWith("self.") -> context.self[name.removePrefix("self.")]
            name.startsWith("event.") && name.endsWith(".id") -> context.eventIds[name.removePrefix("event.").removeSuffix(".id")]
            else -> null
        }
}

/** Dotted-path selector over JSON objects (enough for id sources in tests). */
class DottedFieldSelector : JsonFieldSelector {
    override fun select(
        root: JsonElement,
        path: String,
    ): JsonElement? = path.split('.').fold<String, JsonElement?>(root) { node, key -> (node as? JsonObject)?.get(key) }
}

/**
 * Evaluates assertions like the real use case in miniature and records them: visible_text looks at the fake session's
 * visible texts and measures latency from t0; latency_max reads that latency; oracle renders its path (so template
 * errors surface) and asks [oracleVerdict]; everything else passes.
 */
class FakeVerify(
    private val recorder: EvidenceRecorder,
    private val clock: HarnessClock,
    private val renderer: TemplateRenderer,
) : VerifyStepUseCase {
    val actorCalls = CopyOnWriteArrayList<Pair<List<AssertionSpec>, AssertionInput>>()
    val groupCalls = CopyOnWriteArrayList<List<ActorResult>>()

    @Volatile
    var oracleVerdict: (String, AssertionInput) -> Verdict = { _, _ -> Verdict.PASSED }

    override suspend fun verifyActor(
        specs: List<AssertionSpec>,
        input: AssertionInput,
    ): List<AssertionRecord> {
        actorCalls += specs to input
        var latencyMs: Long? = null
        return specs.map { spec ->
            val record =
                when (spec) {
                    is AssertionSpec.VisibleText -> {
                        val seen = (input.session as? FakeBrowserSession)?.visibleTexts?.contains(spec.text) == true
                        latencyMs = if (seen) input.eventEmittedAt?.elapsedUntil(clock.now())?.inWholeMilliseconds else null
                        record(input, spec, EvidenceSource.RECEIVER, spec.text, if (seen) Verdict.PASSED else Verdict.FAILED, latencyMs)
                    }

                    is AssertionSpec.LatencyMax -> {
                        val ok = latencyMs != null && latencyMs <= spec.max.inWholeMilliseconds
                        record(input, spec, EvidenceSource.HARNESS, "${spec.max}", if (ok) Verdict.PASSED else Verdict.FAILED, latencyMs)
                    }

                    is AssertionSpec.Oracle -> {
                        try {
                            val path = renderer.render(spec.path, input.templates)
                            record(input, spec, EvidenceSource.ORACLE, path, oracleVerdict(path, input), null)
                        } catch (e: TemplateException) {
                            record(input, spec, EvidenceSource.ORACLE, spec.path, Verdict.FAILED, null, e.message)
                        }
                    }

                    else -> {
                        record(input, spec, EvidenceSource.RECEIVER, spec.type, Verdict.PASSED, null)
                    }
                }
            recorder.assertion(record)
            record
        }
    }

    override suspend fun verifyGroup(
        specs: List<AssertionSpec>,
        input: AssertionInput,
        results: List<ActorResult>,
    ): List<AssertionRecord> {
        groupCalls += results
        val winners = results.count { it.succeeded }
        return specs.map { spec ->
            record(input, spec, EvidenceSource.SENDER, "exactly one", if (winners == 1) Verdict.PASSED else Verdict.FAILED, null)
                .also { recorder.assertion(it) }
        }
    }

    private fun record(
        input: AssertionInput,
        spec: AssertionSpec,
        source: EvidenceSource,
        expected: String,
        verdict: Verdict,
        latencyMs: Long?,
        note: String? = null,
    ) = AssertionRecord(
        stepId = input.stepId,
        runId = input.runId,
        agentId = input.agentId,
        scenarioStep = input.scenarioStep,
        type = spec.type,
        source = source,
        expected = expected,
        observed = verdict.name,
        verdict = verdict,
        latencyMs = latencyMs,
        note = note,
        artifactIds = emptyList(),
    )
}

class FakeBrowserEngine(
    private val clock: HarnessClock,
) : BrowserEngine {
    val sessions = ConcurrentHashMap<String, FakeBrowserSession>()
    val opened = CopyOnWriteArrayList<SessionOptions>()
    val starts = AtomicInteger()
    val stops = AtomicInteger()

    @Volatile
    var failStart = false

    @Volatile
    var failOpenFor: Set<String> = emptySet()

    /** What opening a session listed in [failOpenFor] throws. */
    @Volatile
    var openError: (String) -> Exception = { label -> BrowserActionException("context for $label crashed") }

    @Volatile
    var configure: (FakeBrowserSession) -> Unit = {}

    override suspend fun start(config: BrowserEngineConfig): BrowserSessionFactory {
        starts.incrementAndGet()
        if (failStart) throw BrowserActionException("chromium could not start")
        return BrowserSessionFactory { options ->
            if (options.label in failOpenFor) throw openError(options.label)
            opened += options
            FakeBrowserSession(options.label, clock).also {
                configure(it)
                sessions[options.label] = it
            }
        }
    }

    override suspend fun stop() {
        stops.incrementAndGet()
    }

    fun session(agent: String): FakeBrowserSession = sessions.getValue(agent)
}

data class AgentCall(
    val agentId: AgentId,
    val scenarioStep: String,
    val action: StepAction,
    val context: StepContext,
)

/** Agents whose outcome comes from [script]; every call is recorded in [calls]. */
class ScriptedAgentFactory : TesterAgentFactory {
    val runtimes = ConcurrentHashMap<AgentId, AgentRuntime>()
    val calls = CopyOnWriteArrayList<AgentCall>()

    @Volatile
    var script: suspend (AgentCall, AgentRuntime) -> ActionOutcome = { _, _ -> ActionOutcome(ActionStatus.SUCCEEDED, "ok") }

    override fun create(runtime: AgentRuntime): TesterAgent {
        runtimes[runtime.identity.agentId] = runtime
        return object : TesterAgent {
            override val runtime: AgentRuntime = runtime

            override suspend fun perform(
                action: StepAction,
                step: StepContext,
            ): ActionOutcome {
                val call = AgentCall(runtime.identity.agentId, step.scenarioStep, action, step)
                calls += call
                return script(call, runtime)
            }
        }
    }

    fun callsFor(scenarioStep: String): List<AgentCall> = calls.filter { it.scenarioStep == scenarioStep }
}

class TestSharedRunState : SharedRunState {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun get(key: String): String? = values.value[key]

    override fun put(
        key: String,
        value: String,
    ) = values.update { it + (key to value) }

    override suspend fun await(
        key: String,
        timeout: Duration,
    ): String? = withTimeoutOrNull(timeout) { values.first { key in it }[key] }
}

/**
 * Records every monitor call. [events] keeps the board's calls (run, steps, messages); [timeline] has every call in
 * order as one readable line, task updates as `task <step> <agent> <STATE>`.
 */
class RecordingMonitor : MonitorView {
    val events = CopyOnWriteArrayList<String>()
    val statuses = CopyOnWriteArrayList<AgentStatus>()
    val summaries = CopyOnWriteArrayList<RunSummary>()
    val plans = CopyOnWriteArrayList<RunPlan>()
    val tasks = CopyOnWriteArrayList<TaskUpdate>()
    val published = CopyOnWriteArrayList<PublishedEvent>()
    val received = CopyOnWriteArrayList<String>()
    val timeline = CopyOnWriteArrayList<String>()

    override fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    ) {
        events += "runStarted ${agents.size}"
        timeline += "runStarted ${agents.size}"
    }

    override fun agentUpdated(status: AgentStatus) {
        statuses += status
    }

    override fun stepStarted(scenarioStep: String) {
        events += "step $scenarioStep"
        timeline += "step $scenarioStep"
    }

    override fun message(text: String) {
        events += "message $text"
        timeline += "message $text"
    }

    override fun runFinished(summary: RunSummary) {
        summaries += summary
        events += "runFinished ${summary.outcome}"
        timeline += "runFinished ${summary.outcome}"
    }

    override fun planReady(plan: RunPlan) {
        plans += plan
        timeline += "plan " + plan.steps.joinToString(" ") { step -> "${step.id}=${step.resolvedAgents.joinToString(",")}" }
    }

    override fun taskUpdated(update: TaskUpdate) {
        tasks += update
        timeline += "task ${update.stepId} ${update.agentId} ${update.state}"
    }

    override fun eventPublished(event: PublishedEvent) {
        published += event
        timeline += "published ${event.name} by ${event.emitter}"
    }

    override fun eventReceived(
        eventName: String,
        agentId: AgentId,
        latencyMs: Long?,
        received: Boolean,
    ) {
        val line = "${if (received) "received" else "missed"} $eventName by $agentId"
        this.received += line
        timeline += line
    }

    /** The states one task went through, in order. */
    fun statesOf(
        stepId: String,
        agent: String,
    ): List<TaskState> = tasks.filter { it.stepId == stepId && it.agentId == AgentId(agent) }.map { it.state }
}

class CountingFinalizer(
    private val failure: Exception? = null,
) : RunFinalizer {
    val calls = CopyOnWriteArrayList<RunId>()

    override suspend fun finalize(runId: RunId): Path? {
        calls += runId
        failure?.let { throw it }
        return Path.of("evidence", runId.value, "report")
    }
}
