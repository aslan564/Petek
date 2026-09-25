package az.petek.evidence.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.EventId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

/*
 * SQLite schema of the evidence store (docs/PLAN.md "Sübut bazası və hesabat").
 *
 * - Every table has an auto-increment `seq`: it is the insertion order, the tie-breaker behind "ordered by time".
 * - Records with a natural id are unique per run (`run_id` + id), so re-recording the same id is idempotent.
 * - There are deliberately no foreign keys: evidence is an append-mostly log, and a record must never be lost
 *   because it arrived before its run row (e.g. a harness step recorded while the run is still being created).
 */

private fun Table.runIdColumn(): Column<RunId> = varchar("run_id", ID_LENGTH).transform(::RunId, RunId::value)

private fun Table.agentIdColumn(name: String): Column<AgentId> = varchar(name, ID_LENGTH).transform(::AgentId, AgentId::value)

private fun Table.stepIdColumn(): Column<StepId> = varchar("step_id", ID_LENGTH).transform(::StepId, StepId::value)

internal object RunTable : Table("run") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val runTag = varchar("run_tag", ID_LENGTH).transform(::RunTag, RunTag::value)
    val campaignHash = varchar("campaign_hash", ID_LENGTH)
    val campaignName = text("campaign_name")
    val seed = long("seed")
    val target = text("target")
    val startedAt = instant("started_at")
    val endedAt = instant("ended_at").nullable()
    val result = enumName<RunResult>("result")
    val repeatGroup = varchar("repeat_group", ID_LENGTH).nullable()
    val repeatIndex = integer("repeat_index").nullable()

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId)
        index(false, startedAt)
        index(false, repeatGroup, repeatIndex)
    }
}

internal object RunResourceTable : Table("run_resource") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val kind = varchar("kind", ID_LENGTH)
    val externalId = varchar("external_id", ID_LENGTH)
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, kind, externalId)
    }
}

internal object StepTable : Table("step") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val stepId = stepIdColumn()
    val agentId = agentIdColumn("agent_id").nullable()
    val scenarioStep = varchar("scenario_step", ID_LENGTH)
    val kind = enumName<StepKind>("kind")
    val action = text("action")
    val llmReason = text("llm_reason").nullable()
    val startedAt = instant("started_at")
    val endedAt = instant("ended_at")
    val durationMs = long("duration_ms")
    val status = enumName<StepStatus>("status")
    val detail = text("detail").nullable()
    val correlationId = varchar("correlation_id", ID_LENGTH).transform(::CorrelationId, CorrelationId::value)

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, stepId)
        index(false, runId, startedAt)
    }
}

internal object EventTable : Table("event") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val eventId = varchar("event_id", ID_LENGTH).transform(::EventId, EventId::value)
    val name = varchar("name", ID_LENGTH)
    val emitter = agentIdColumn("emitter")
    val objectId = text("object_id").nullable()
    val objectIdSource = varchar("object_id_source", ID_LENGTH).nullable()
    val payloadJson = text("payload_json")
    val t0 = instant("t0")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, eventId)
        index(false, runId, t0)
    }
}

internal object ReceiptTable : Table("receipt") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val eventId = varchar("event_id", ID_LENGTH).transform(::EventId, EventId::value)
    val receiver = agentIdColumn("receiver")
    val received = bool("received")
    val t1 = instant("t1").nullable()
    val latencyMs = long("latency_ms").nullable()

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, eventId, receiver)
        index(false, runId, t1)
    }
}

internal object ArtifactTable : Table("artifact") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val artifactId = varchar("artifact_id", ID_LENGTH).transform(::ArtifactId, ArtifactId::value)
    val stepId = stepIdColumn()
    val type = enumName<ArtifactType>("type")
    val relativePath = text("relative_path")
    val sha256 = varchar("sha256", SHA256_HEX_LENGTH)
    val sizeBytes = long("size_bytes")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, artifactId)
    }
}

internal object AssertionTable : Table("assertion") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val stepId = stepIdColumn()
    val agentId = agentIdColumn("agent_id").nullable()
    val scenarioStep = varchar("scenario_step", ID_LENGTH)
    val type = varchar("type", ID_LENGTH)
    val evidenceSource = enumName<EvidenceSource>("source")
    val expected = text("expected")
    val observed = text("observed").nullable()
    val verdict = enumName<Verdict>("verdict")
    val latencyMs = long("latency_ms").nullable()
    val note = text("note").nullable()
    val artifactIds = artifactIds("artifact_ids")

    override val primaryKey = PrimaryKey(seq)

    init {
        index(false, runId)
    }
}

internal object FindingTable : Table("finding") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val findingId = varchar("finding_id", ID_LENGTH).transform(::FindingId, FindingId::value)
    val stepId = stepIdColumn().nullable()
    val scenarioStep = varchar("scenario_step", ID_LENGTH)
    val agentId = agentIdColumn("agent_id").nullable()
    val findingClass = enumName<FindingClass>("finding_class")
    val a = text("a").nullable()
    val b = text("b").nullable()
    val c = text("c").nullable()
    val note = text("note")
    val artifactIds = artifactIds("artifact_ids")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, findingId)
    }
}

/** One row per (run, agent); recording usage adds to the row's totals. */
internal object UsageTable : Table("usage") {
    val seq = long("seq").autoIncrement()
    val runId = runIdColumn()
    val agentId = agentIdColumn("agent_id")
    val inputTokens = long("input_tokens")
    val outputTokens = long("output_tokens")
    val cacheReadTokens = long("cache_read_tokens")
    val costUsd = double("cost_usd").nullable()
    val calls = integer("calls")

    override val primaryKey = PrimaryKey(seq)

    init {
        uniqueIndex(runId, agentId)
    }
}

private const val SHA256_HEX_LENGTH = 64

/** Every table of the evidence store, in creation order. */
internal val evidenceTables: Array<Table> =
    arrayOf(
        RunTable,
        RunResourceTable,
        StepTable,
        EventTable,
        ReceiptTable,
        ArtifactTable,
        AssertionTable,
        FindingTable,
        UsageTable,
    )
