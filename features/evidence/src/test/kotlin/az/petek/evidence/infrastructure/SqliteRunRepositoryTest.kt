package az.petek.evidence.infrastructure

import az.petek.core.ids.RunId
import az.petek.evidence.domain.RunResult
import az.petek.evidence.infrastructure.EvidenceFixtures.OTHER_RUN
import az.petek.evidence.infrastructure.EvidenceFixtures.RUN
import az.petek.evidence.infrastructure.EvidenceFixtures.at
import az.petek.evidence.infrastructure.EvidenceFixtures.resource
import az.petek.evidence.infrastructure.EvidenceFixtures.run
import az.petek.evidence.infrastructure.EvidenceFixtures.withStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteRunRepositoryTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a created run is found as running with no end time`() =
        withStore(dir) { store, _ ->
            val created = run()

            store.create(created)

            store.find(RUN) shouldBe created
            created.result shouldBe RunResult.RUNNING
            created.endedAt shouldBe null
        }

    @Test
    fun `finishing a run records its result and end time and changes nothing else`() =
        withStore(dir) { store, _ ->
            val created = run(repeatGroup = "grp_1", repeatIndex = 2)
            store.create(created)

            store.finish(RUN, RunResult.FAILED, endedAt = at(90).plusNanos(7))

            store.find(RUN) shouldBe created.copy(result = RunResult.FAILED, endedAt = at(90).plusNanos(7))
        }

    @Test
    fun `finishing a run again overwrites the previous result`() =
        withStore(dir) { store, _ ->
            store.create(run())
            store.finish(RUN, RunResult.ABORTED, endedAt = at(10))

            store.finish(RUN, RunResult.PASSED, endedAt = at(20))

            store.find(RUN)?.result shouldBe RunResult.PASSED
            store.find(RUN)?.endedAt shouldBe at(20)
        }

    @Test
    fun `creating a run id twice is refused and keeps the original`() =
        withStore(dir) { store, _ ->
            val original = run()
            store.create(original)

            val error = shouldThrow<IllegalArgumentException> { store.create(run(startedAt = at(60))) }

            error.message shouldContain RUN.value
            store.find(RUN) shouldBe original
        }

    @Test
    fun `finishing an unknown run is refused`() =
        withStore(dir) { store, _ ->
            val error = shouldThrow<IllegalArgumentException> { store.finish(RunId("run_missing"), RunResult.PASSED, at(1)) }

            error.message shouldContain "run_missing"
        }

    @Test
    fun `unknown runs are not found and an empty store has no latest run`() =
        withStore(dir) { store, _ ->
            store.find(RUN) shouldBe null
            store.latest() shouldBe null
            store.byRepeatGroup("grp_1").shouldBeEmpty()
        }

    @Test
    fun `latest is the run with the most recent start, whatever the insertion order`() =
        withStore(dir) { store, _ ->
            store.create(run(RunId("run_new"), startedAt = at(100)))
            store.create(run(RunId("run_old"), startedAt = at(1)))
            store.create(run(RunId("run_mid"), startedAt = at(50)))

            store.latest()?.runId shouldBe RunId("run_new")
        }

    @Test
    fun `latest prefers the run inserted last when start times tie`() =
        withStore(dir) { store, _ ->
            store.create(run(RunId("run_a"), startedAt = at(5)))
            store.create(run(RunId("run_b"), startedAt = at(5)))

            store.latest()?.runId shouldBe RunId("run_b")
        }

    @Test
    fun `a repeat group lists its runs by repeat index and nothing else`() =
        withStore(dir) { store, _ ->
            store.create(run(RunId("run_3"), startedAt = at(3), repeatGroup = "grp_1", repeatIndex = 3))
            store.create(run(RunId("run_1"), startedAt = at(1), repeatGroup = "grp_1", repeatIndex = 1))
            store.create(run(RunId("run_x"), startedAt = at(2), repeatGroup = "grp_2", repeatIndex = 2))
            store.create(run(RunId("run_2"), startedAt = at(2), repeatGroup = "grp_1", repeatIndex = 2))
            store.create(run(RunId("run_solo"), startedAt = at(9)))

            store.byRepeatGroup("grp_1").map { it.runId.value } shouldContainExactly listOf("run_1", "run_2", "run_3")
            store.byRepeatGroup("grp_2").map { it.runId.value } shouldContainExactly listOf("run_x")
        }

    @Test
    fun `resources are listed by creation time and removed one at a time`() =
        withStore(dir) { store, _ ->
            store.addResource(resource("company-2", createdAt = at(2)))
            store.addResource(resource("company-1", createdAt = at(1)))
            store.addResource(resource("dept-9", createdAt = at(3), kind = "department"))

            store.resources(RUN) shouldContainExactly
                listOf(
                    resource("company-1", createdAt = at(1)),
                    resource("company-2", createdAt = at(2)),
                    resource("dept-9", createdAt = at(3), kind = "department"),
                )

            store.removeResource(RUN, "company", "company-2")

            store.resources(RUN).map { it.externalId } shouldContainExactly listOf("company-1", "dept-9")
        }

    @Test
    fun `removing a resource matches run, kind and external id exactly`() =
        withStore(dir) { store, _ ->
            store.addResource(resource("42", kind = "company"))
            store.addResource(resource("42", kind = "department"))
            store.addResource(resource("42", kind = "company", runId = OTHER_RUN))

            store.removeResource(RUN, "company", "42")

            store.resources(RUN).map { it.kind } shouldContainExactly listOf("department")
            store.resources(OTHER_RUN).map { it.kind } shouldContainExactly listOf("company")
        }

    @Test
    fun `removing a missing resource is a harmless no-op so teardown can be retried`() =
        withStore(dir) { store, _ ->
            store.addResource(resource("company-1"))

            store.removeResource(RUN, "company", "company-1")
            store.removeResource(RUN, "company", "company-1")
            store.removeResource(RunId("run_missing"), "company", "company-1")

            store.resources(RUN).shouldBeEmpty()
        }

    @Test
    fun `adding the same resource twice keeps the first registration`() =
        withStore(dir) { store, _ ->
            store.addResource(resource("company-1", createdAt = at(1)))
            store.addResource(resource("company-1", createdAt = at(5)))

            store.resources(RUN) shouldContainExactly listOf(resource("company-1", createdAt = at(1)))
        }
}
