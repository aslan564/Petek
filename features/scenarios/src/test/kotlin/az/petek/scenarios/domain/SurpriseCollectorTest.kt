package az.petek.scenarios.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.security.Secret
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.scenarios.testing.RunStories
import az.petek.scenarios.testing.RunStories.plus
import az.petek.scenarios.testing.ScenarioTestKit.MINI_CAMPAIGN
import az.petek.scenarios.testing.ScenarioTestKit.RUN
import az.petek.scenarios.testing.ScenarioTestKit.finding
import az.petek.scenarios.testing.ScenarioTestKit.step
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class SurpriseCollectorTest {
    private val collector = SurpriseCollector(SecretRedactor())

    private fun collect(evidence: RunEvidence): SurpriseCollection = collector.collect(RUN, MINI_CAMPAIGN, evidence)

    @Test
    fun `a reported problem is one surprise with everything the actor recorded in that step`() {
        val surprise = collect(RunStories.problemReported()).surprises.single()

        surprise.id shouldBe SurpriseId.of(RUN, "read_announce", AgentId("a03"))
        surprise.kind shouldBe SurpriseKind.PROBLEM_REPORTED
        surprise.scenarioStep shouldBe "read_announce"
        surprise.agentId shouldBe AgentId("a03")
        surprise.text shouldBe "a03 reported a problem in read_announce: report_problem bug \"Elan siyahıda yoxdur\""
        surprise.evidence.stepIds shouldBe listOf("stp_a03_1", "stp_a03_2", "stp_a03_3", "stp_a03_4").map(::StepId)
        surprise.evidence.artifactIds shouldBe listOf("art_a03_1", "art_a03_2", "art_a03_3").map(::ArtifactId)
        surprise.evidence.findingIds shouldBe listOf(FindingId("fnd_a03"))
    }

    @Test
    fun `the facts show the actions, the agent's reasons, the observations, the checks, the findings and the artifacts`() {
        val facts =
            collect(RunStories.problemReported())
                .surprises
                .single()
                .evidence.facts

        facts.first() shouldBe "[stp_a03_1] WAIT wait_for announcement_created -> PASSED (1000 ms); observed: received"
        facts[1] shouldBe
            "[stp_a03_2] DO click [3] \"Bildirişlər\" -> PASSED (1000 ms); agent's reason: The bell opens the notifications; " +
            "observed: Opened the notification list"
        facts.shouldContainAll(
            listOf(
                "[stp_a03_4] assertion visible_text (RECEIVER) -> FAILED; expected: Sabah 10:00 ümumi iclas within 5s; observed: -",
                "[fnd_a03] finding AGENT_FAILURE; A (sender): sender did it; B (receiver): receiver saw nothing; " +
                    "note: Agent failure: problem_reported.",
                "[art_a03_1] screenshot recorded for [stp_a03_3]",
                "[art_a03_2] a11y recorded for [stp_a03_3]",
            ),
        )
    }

    @Test
    fun `a failed concluding step is a surprise but failed decisions inside a passing action are not`() {
        val collection = collect(RunStories.announcementPassed() + RunStories.failedJoin())

        val surprise = collection.surprises.single()
        surprise.kind shouldBe SurpriseKind.FAILED_STEP
        surprise.agentId shouldBe AgentId("a04")
        surprise.scenarioStep shouldBe "join"
        surprise.text shouldBe "a04 in join: run register_and_login -> FAILED: otp_rejected: code rejected twice"
        surprise.evidence.findingIds shouldBe listOf(FindingId("fnd_a04_join"))
        collection.ignored.shouldBeEmpty()
    }

    @Test
    fun `an expected refusal in a main step is ignored with its reason, not triaged`() {
        val collection = collect(RunStories.expectedRefusal())

        collection.surprises.shouldBeEmpty()
        collection.ignored.map { it.reason }.distinct() shouldBe listOf(IgnoreReason.EXPECTED_REFUSAL)
        collection.ignored.map { it.ref.id } shouldBe listOf("stp_a04_f1", "stp_a04_f2")
        collection.ignored.first().agentId shouldBe AgentId("a04")
    }

    @Test
    fun `an expected refusal whose status check failed is still a surprise`() {
        val collection = collect(RunStories.expectedRefusal(statusFails = true))

        val surprise = collection.surprises.single()
        surprise.kind shouldBe SurpriseKind.FINDING
        surprise.text shouldBe "BACKEND finding in forbidden for a04: The target let an employee approve."
        surprise.evidence.stepIds shouldBe listOf(StepId("stp_a04_f1"), StepId("stp_a04_f2"))
        collection.ignored.shouldBeEmpty()
    }

    @Test
    fun `a refusal during setup is a real surprise`() {
        val refused =
            RunEvidence(
                steps =
                    listOf(
                        step(
                            "stp_s1",
                            "a03",
                            "join",
                            StepKind.RUN,
                            "run register_and_login",
                            StepStatus.BLOCKED,
                            "permission_denied: code refused",
                        ),
                    ),
                assertions = emptyList(),
                findings = emptyList(),
                artifacts = emptyList(),
            )

        val surprise = collect(refused).surprises.single()

        surprise.kind shouldBe SurpriseKind.FAILED_STEP
        surprise.text shouldContain "permission_denied"
    }

    @Test
    fun `the loser of a race whose single-winner check passed is ignored, with the judge's finding about it`() {
        val collection = collect(RunStories.race(won = true))

        collection.surprises.shouldBeEmpty()
        collection.ignored.map { it.ref.id to it.reason } shouldBe
            listOf(
                "stp_race_a03_t" to IgnoreReason.LOST_RACE,
                "stp_race_a03" to IgnoreReason.LOST_RACE,
                "fnd_race_a03" to IgnoreReason.LOST_RACE,
            )
        collection.ignored.map { it.ref.type }.last() shouldBe EvidenceRefType.FINDING
    }

    @Test
    fun `a race whose single-winner check failed makes the group and the losing actor surprises`() {
        val collection = collect(RunStories.race(won = false))

        collection.surprises.map { it.agentId to it.kind } shouldBe
            listOf(AgentId("a03") to SurpriseKind.PROBLEM_REPORTED, null to SurpriseKind.FAILED_STEP)
        val group = collection.surprises.last()
        group.id shouldBe SurpriseId.of(RUN, "race", null)
        group.text shouldBe "the step as a whole in race: verify_group only_one_succeeds -> FAILED: only_one_succeeds: FAILED"
        collection.ignored.shouldBeEmpty()
    }

    @Test
    fun `failures of the test environment are ignored, never blamed on target, model or scenario`() {
        val collection = collect(RunStories.environmentFailure())

        collection.surprises.shouldBeEmpty()
        collection.ignored.map { it.ref.id to it.reason } shouldBe
            listOf("stp_env_1" to IgnoreReason.ENVIRONMENT, "fnd_env" to IgnoreReason.ENVIRONMENT)
    }

    @Test
    fun `an unreachable inbox reported only in the last decision is recognised too`() {
        val evidence =
            RunEvidence(
                steps =
                    listOf(
                        step(
                            "stp_m1",
                            "a02",
                            "announce",
                            StepKind.DO,
                            "get_email_code",
                            StepStatus.ERROR,
                            "Request timeout has expired | outcome: ERROR mail_unavailable: inbox unreachable",
                        ),
                        step(
                            "stp_m2",
                            "a02",
                            "announce",
                            StepKind.DO,
                            "do: Elan yarat",
                            StepStatus.ERROR,
                            "mail_unavailable: inbox unreachable",
                        ),
                    ),
                assertions = emptyList(),
                findings =
                    listOf(
                        finding("fnd_m", "stp_m1", "announce", "a02", FindingClass.AGENT_FAILURE, "Agent failure: mail_unavailable."),
                    ),
                artifacts = emptyList(),
            )

        val collection = collect(evidence)

        collection.surprises.shouldBeEmpty()
        collection.ignored.map { it.reason }.distinct() shouldBe listOf(IgnoreReason.ENVIRONMENT)
    }

    @Test
    fun `a finding without steps, such as a flaky step, is a surprise of the step as a whole`() {
        val flaky =
            RunEvidence(
                steps = emptyList(),
                assertions = emptyList(),
                findings = listOf(finding("fnd_flaky", null, "read_announce", null, FindingClass.FLAKY, "passed in 2 of 3 runs")),
                artifacts = emptyList(),
            )

        val surprise = collect(flaky).surprises.single()

        surprise.kind shouldBe SurpriseKind.FINDING
        surprise.agentId shouldBe null
        surprise.evidence.stepIds.shouldBeEmpty()
        surprise.evidence.refs shouldBe listOf(EvidenceRef(EvidenceRefType.FINDING, "fnd_flaky"))
    }

    @Test
    fun `skipped steps and passing actors are not surprises`() {
        val evidence =
            RunEvidence(
                steps =
                    listOf(
                        step(
                            "stp_k1",
                            "a04",
                            "read_announce",
                            StepKind.SYSTEM,
                            "skip",
                            StepStatus.SKIPPED,
                            "agent failed earlier (otp_rejected)",
                        ),
                        step("stp_k2", "a03", "read_announce", StepKind.DO, "do: Bildirişləri aç", StepStatus.PASSED),
                    ),
                assertions = emptyList(),
                findings = emptyList(),
                artifacts = emptyList(),
            )

        collect(evidence) shouldBe SurpriseCollection(emptyList(), emptyList())
    }

    @Test
    fun `surprises come in the order their evidence was recorded and their ids are stable`() {
        val evidence = RunStories.failedJoin() + RunStories.problemReported() + RunStories.expectedRefusal(statusFails = true)

        val first = collect(evidence)
        val second = collect(evidence)

        first.surprises.map { it.scenarioStep } shouldBe listOf("join", "read_announce", "forbidden")
        second.surprises.map { it.id } shouldBe first.surprises.map { it.id }
        first.surprises.map { it.id }.distinct() shouldHaveSize 3
    }

    @Test
    fun `evidence of other runs is left out`() {
        val other =
            RunEvidence(
                steps =
                    listOf(
                        step("stp_o1", "a03", "announce", StepKind.DO, "do: x", StepStatus.FAILED, "boom", runId = RunId("run_other")),
                    ),
                assertions = emptyList(),
                findings = emptyList(),
                artifacts = emptyList(),
            )

        collect(other).surprises.shouldBeEmpty()
    }

    @Test
    fun `secrets never reach the facts or the summary`() {
        val secretCollector = SurpriseCollector(SecretRedactor(listOf(Secret("Gizli-Parol-2026"))))
        val leaky =
            RunEvidence(
                steps =
                    listOf(
                        step(
                            "stp_x1",
                            "a03",
                            "announce",
                            StepKind.DO,
                            "type [4] \"Gizli-Parol-2026\"",
                            StepStatus.FAILED,
                            "password=Gizli-Parol-2026 rejected; token: abcdef",
                        ),
                        step(
                            "stp_x2",
                            "a03",
                            "announce",
                            StepKind.DO,
                            "do: Elan yarat",
                            StepStatus.FAILED,
                            "login_failed: password=Gizli-Parol-2026",
                        ),
                        // A secret right at the clipping limit must not survive in part.
                        step(
                            "stp_x3",
                            "a03",
                            "announce",
                            StepKind.DO,
                            "click [1]",
                            StepStatus.PASSED,
                            "x".repeat(SurpriseCollector.MAX_FIELD - 8) + " Gizli-Parol-2026",
                        ),
                    ),
                assertions = emptyList(),
                findings = emptyList(),
                artifacts = emptyList(),
            )

        val surprise = secretCollector.collect(RUN, MINI_CAMPAIGN, leaky).surprises.single()

        (surprise.evidence.facts + surprise.text).forEach {
            it shouldNotContain "Gizli-Par"
            it shouldNotContain "abcdef"
        }
    }

    @Test
    fun `long texts are clipped and only the latest steps of a long action are shown`() {
        val turns =
            (1..40).map { i ->
                step("stp_t$i", "a01", "announce", StepKind.DO, "click [$i]", StepStatus.PASSED, "x".repeat(2_000), second = i.toLong())
            } +
                step(
                    "stp_t41",
                    "a01",
                    "announce",
                    StepKind.DO,
                    "do: Elan yarat",
                    StepStatus.FAILED,
                    "step_limit: ${"y".repeat(2_000)}",
                    second = 41,
                )
        val surprise = collect(RunEvidence(turns, emptyList(), emptyList(), emptyList())).surprises.single()

        surprise.evidence.stepIds shouldHaveSize 41
        surprise.evidence.facts.first() shouldBe "(16 earlier steps of this actor in this scenario step are not shown)"
        surprise.evidence.facts[1] shouldContain "[stp_t17]"
        surprise.evidence.facts.forEach { (it.length <= SurpriseCollector.MAX_FACT) shouldBe true }
        (surprise.text.length <= SurpriseCollector.MAX_TEXT) shouldBe true
        surprise.text shouldContain "…"
    }

    @Test
    fun `a group mixing a problem, a failed step and a finding is named after the problem`() {
        val collection = collect(RunStories.problemReported("a03") + RunStories.problemReported("a04"))

        collection.surprises.map { it.agentId } shouldContainExactlyInAnyOrder listOf(AgentId("a03"), AgentId("a04"))
        collection.surprises.map { it.kind }.distinct() shouldBe listOf(SurpriseKind.PROBLEM_REPORTED)
    }
}
