package az.petek.scenarios.testing

import az.petek.scenarios.domain.ScenarioIdGenerator
import az.petek.scenarios.domain.ScenarioVersionId
import java.util.concurrent.atomic.AtomicInteger

/** Predictable version ids for tests: `scn_1`, `scn_2`, ... */
class SequentialScenarioIds : ScenarioIdGenerator {
    private val counter = AtomicInteger(0)

    override fun versionId(): ScenarioVersionId = ScenarioVersionId("scn_${counter.incrementAndGet()}")
}
