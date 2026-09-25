package az.petek.evidence.infrastructure

import az.petek.core.ids.AgentId
import az.petek.evidence.domain.UsageRecord
import az.petek.evidence.infrastructure.EvidenceFixtures.A01
import az.petek.evidence.infrastructure.EvidenceFixtures.A07
import az.petek.evidence.infrastructure.EvidenceFixtures.RUN
import az.petek.evidence.infrastructure.EvidenceFixtures.usage
import az.petek.evidence.infrastructure.EvidenceFixtures.withStore
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteUsageTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a single usage report round-trips unchanged`() =
        withStore(dir) { store, _ ->
            val record = usage(A07)

            store.usage(record)

            store.usage(RUN) shouldContainExactly listOf(record)
        }

    @Test
    fun `usage of the same agent adds up into one row per agent`() =
        withStore(dir) { store, _ ->
            store.usage(usage(A07, inputTokens = 1000, costUsd = 0.25))
            store.usage(usage(A01, inputTokens = 10, costUsd = 0.01))
            store.usage(
                UsageRecord(
                    runId = RUN,
                    agentId = A07,
                    inputTokens = 500,
                    outputTokens = 70,
                    cacheReadTokens = 3,
                    costUsd = 0.5,
                    calls = 2,
                ),
            )

            val rows = store.usage(RUN)

            rows.map { it.agentId } shouldContainExactly listOf(A07, A01)
            val a07 = rows.first()
            a07.inputTokens shouldBe 1500
            a07.outputTokens shouldBe 270
            a07.cacheReadTokens shouldBe 53
            a07.calls shouldBe 3
            a07.costUsd!! shouldBe (0.75 plusOrMinus 1e-9)
            rows.last() shouldBe usage(A01, inputTokens = 10, costUsd = 0.01)
        }

    @Test
    fun `an unknown cost stays unknown until some call reports one`() =
        withStore(dir) { store, _ ->
            store.usage(usage(A07, costUsd = null))
            store.usage(usage(A07, costUsd = null))
            store.usage(RUN).single().costUsd shouldBe null

            store.usage(usage(A07, costUsd = 0.4))
            store.usage(usage(A07, costUsd = null))

            store.usage(RUN).single().costUsd shouldBe 0.4
        }

    @Test
    fun `usage is kept apart per run`() =
        withStore(dir) { store, _ ->
            store.usage(usage(A07, inputTokens = 1))
            store.usage(usage(A07, inputTokens = 2, runId = EvidenceFixtures.OTHER_RUN))

            store.usage(RUN).single().inputTokens shouldBe 1
            store.usage(EvidenceFixtures.OTHER_RUN).single().inputTokens shouldBe 2
        }

    @Test
    fun `concurrent usage reports of many agents are accumulated without losing any`() =
        withStore(dir) { store, _ ->
            val agents = (1..10).map { AgentId.of(it) }

            withContext(Dispatchers.Default) {
                agents
                    .flatMap { agent -> (1..20).map { async { store.usage(usage(agent, inputTokens = 100, costUsd = 0.5)) } } }
                    .awaitAll()
            }

            val rows = store.usage(RUN)
            rows.map { it.agentId }.toSet() shouldBe agents.toSet()
            rows.forEach { row ->
                row.inputTokens shouldBe 2000
                row.outputTokens shouldBe 4000
                row.cacheReadTokens shouldBe 1000
                row.calls shouldBe 20
                row.costUsd shouldBe 10.0
            }
        }
}
