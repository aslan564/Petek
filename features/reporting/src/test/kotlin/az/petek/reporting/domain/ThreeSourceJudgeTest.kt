package az.petek.reporting.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.testing.SequentialIdGenerator
import az.petek.evidence.domain.AssertionRecord
import az.petek.evidence.domain.EvidenceSource.HARNESS
import az.petek.evidence.domain.EvidenceSource.ORACLE
import az.petek.evidence.domain.EvidenceSource.RECEIVER
import az.petek.evidence.domain.EvidenceSource.SENDER
import az.petek.evidence.domain.FindingClass
import az.petek.evidence.domain.FindingRecord
import az.petek.evidence.domain.RunRecord
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepStatus
import az.petek.evidence.domain.Verdict.FAILED
import az.petek.evidence.domain.Verdict.PASSED
import az.petek.evidence.domain.Verdict.SKIPPED
import az.petek.reporting.ReportTestData.assertion
import az.petek.reporting.ReportTestData.run
import az.petek.reporting.ReportTestData.step
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ThreeSourceJudgeTest {
    private val judge = ThreeSourceJudge(SequentialIdGenerator())

    @Nested
    inner class Classify {
        /** Every combination of absent (-), passed (P) and failed (F) for A, B and C, with the expected class. */
        private val truthTable =
            listOf(
                "---" to null,
                "--P" to null,
                "--F" to FindingClass.BACKEND,
                "-P-" to null,
                "-PP" to null,
                "-PF" to FindingClass.BACKEND,
                "-F-" to FindingClass.INVESTIGATE,
                "-FP" to FindingClass.DELIVERY_UI,
                "-FF" to FindingClass.BACKEND,
                "P--" to null,
                "P-P" to null,
                "P-F" to FindingClass.BACKEND,
                "PP-" to null,
                "PPP" to null,
                "PPF" to FindingClass.BACKEND,
                "PF-" to FindingClass.INVESTIGATE,
                "PFP" to FindingClass.DELIVERY_UI,
                "PFF" to FindingClass.BACKEND,
                "F--" to FindingClass.INVESTIGATE,
                "F-P" to FindingClass.INVESTIGATE,
                "F-F" to FindingClass.INVESTIGATE,
                "FP-" to FindingClass.INVESTIGATE,
                "FPP" to FindingClass.INVESTIGATE,
                "FPF" to FindingClass.INVESTIGATE,
                "FF-" to FindingClass.INVESTIGATE,
                "FFP" to FindingClass.DELIVERY_UI,
                "FFF" to FindingClass.INVESTIGATE,
            )

        private fun obs(
            source: String,
            code: Char,
        ): Observation? =
            when (code) {
                'P' -> Observation(source, "ok", passed = true)
                'F' -> Observation(source, "bad", passed = false)
                else -> null
            }

        @Test
        fun `the truth table covers every combination of the three sources`() {
            truthTable.map { it.first }.toSet() shouldHaveSize 27
        }

        @Test
        fun `every combination is classified by the three-source rule`() {
            truthTable.forEach { (row, expected) ->
                withClue("A B C = $row") {
                    val verdict = judge.classify(obs("A", row[0]), obs("B", row[1]), obs("C", row[2]))
                    if (expected == null) {
                        verdict shouldBe JudgeVerdict.Pass
                    } else {
                        verdict.shouldBeInstanceOf<JudgeVerdict.Finding>().findingClass shouldBe expected
                    }
                }
            }
        }

        @Test
        fun `an observation without a verdict counts as absent`() {
            val unknownOracle = Observation("C", "oracle unavailable", passed = null)

            judge.classify(null, Observation("B", "x", false), unknownOracle) shouldBe
                JudgeVerdict.Finding(
                    FindingClass.INVESTIGATE,
                    "The receiver (B) disagrees and there is no oracle evidence (C) to attribute it.",
                )
            judge.classify(null, null, unknownOracle) shouldBe JudgeVerdict.Pass
        }

        @Test
        fun `every finding carries a note naming the sources involved`() {
            judge.classify(null, null, obs("C", 'F')).shouldBeInstanceOf<JudgeVerdict.Finding>().note shouldContain "backend"
            judge.classify(null, obs("B", 'F'), obs("C", 'P')).shouldBeInstanceOf<JudgeVerdict.Finding>().note shouldContain
                "delivery or UI"
            judge.classify(obs("A", 'F'), null, null).shouldBeInstanceOf<JudgeVerdict.Finding>().note shouldContain "sender"
        }
    }

    @Nested
    inner class FindingsFromAssertions {
        private val run = run()

        @Test
        fun `receiver and harness checks form B and the oracle forms C`() {
            val assertions =
                listOf(
                    assertion("read_announce", "a02", RECEIVER, FAILED, "Sabah 10:00 ümumi iclas", null, artifacts = listOf("art_1")),
                    assertion("read_announce", "a02", HARNESS, FAILED, "<= 5000 ms", "7000 ms", type = "latency_max"),
                    assertion("read_announce", "a02", ORACLE, PASSED, "contains a02@test", "[a02@test]", artifacts = listOf("art_2")),
                )

            val finding = judge.findings(run, assertions).single()

            finding.findingClass shouldBe FindingClass.DELIVERY_UI
            finding.scenarioStep shouldBe "read_announce"
            finding.agentId shouldBe AgentId("a02")
            finding.a.shouldBeNull()
            finding.b shouldBe "Sabah 10:00 ümumi iclas -> (none); <= 5000 ms -> 7000 ms"
            finding.c shouldBe "contains a02@test -> [a02@test]"
            finding.artifactIds shouldContainExactly listOf(ArtifactId("art_1"), ArtifactId("art_2"))
            finding.runId shouldBe run.runId
        }

        @Test
        fun `a failing oracle after a successful sender is a backend finding`() {
            val assertions =
                listOf(
                    assertion("announce", "a01", SENDER, PASSED, "announcement created", "created #42"),
                    assertion("announce", "a01", ORACLE, FAILED, "status = published", "draft", type = "oracle"),
                )

            val finding = judge.findings(run, assertions).single()

            finding.findingClass shouldBe FindingClass.BACKEND
            finding.a shouldBe "announcement created -> created #42"
            finding.c shouldBe "status = published -> draft"
        }

        @Test
        fun `a source shows only its failing checks when any failed`() {
            val assertions =
                listOf(
                    assertion("read_announce", "a02", RECEIVER, PASSED, "menu visible", "visible"),
                    assertion("read_announce", "a02", RECEIVER, FAILED, "Sabah 10:00", "Dünən"),
                    assertion("read_announce", "a02", ORACLE, PASSED, "receipt", "receipt"),
                )

            judge.findings(run, assertions).single().b shouldBe "Sabah 10:00 -> Dünən"
        }

        @Test
        fun `groups that agree produce no finding`() {
            val assertions =
                listOf(
                    assertion("announce", "a01", SENDER, PASSED),
                    assertion("announce", "a01", ORACLE, PASSED),
                    assertion("read_announce", "a02", RECEIVER, PASSED),
                )

            judge.findings(run, assertions).shouldBeEmpty()
        }

        @Test
        fun `each failing group yields exactly one finding in order of first appearance`() {
            val assertions =
                listOf(
                    assertion("read_announce", "a03", RECEIVER, FAILED),
                    assertion("read_announce", "a02", RECEIVER, PASSED),
                    assertion("announce", "a01", ORACLE, FAILED),
                    assertion("read_announce", "a03", ORACLE, PASSED),
                    assertion("read_announce", "a03", HARNESS, FAILED),
                )

            val findings = judge.findings(run, assertions)

            findings.map { it.scenarioStep to it.agentId?.value } shouldContainExactly
                listOf("read_announce" to "a03", "announce" to "a01")
            findings.map { it.findingClass } shouldContainExactly listOf(FindingClass.DELIVERY_UI, FindingClass.BACKEND)
        }

        @Test
        fun `finding ids come from the injected generator`() {
            val assertions =
                listOf(
                    assertion("announce", "a01", ORACLE, FAILED),
                    assertion("ticket", "a05", ORACLE, FAILED),
                )

            judge.findings(run, assertions).map { it.findingId } shouldContainExactly listOf(FindingId("fnd_1"), FindingId("fnd_2"))
        }

        @Test
        fun `skipped assertions are treated as absent`() {
            val assertions =
                listOf(
                    assertion("read_announce", "a02", RECEIVER, FAILED, artifacts = listOf("art_1")),
                    assertion(
                        "read_announce",
                        "a02",
                        ORACLE,
                        SKIPPED,
                        observed = null,
                        note = "oracle unavailable",
                        artifacts = listOf("art_9"),
                    ),
                )

            val finding = judge.findings(run, assertions).single()

            finding.findingClass shouldBe FindingClass.INVESTIGATE
            finding.c.shouldBeNull()
            finding.artifactIds shouldContainExactly listOf(ArtifactId("art_1"))
        }

        @Test
        fun `a group with only skipped assertions yields no finding`() {
            val assertions = listOf(assertion("announce", "a01", ORACLE, SKIPPED), assertion("announce", "a01", SENDER, SKIPPED))

            judge.findings(run, assertions).shouldBeEmpty()
        }

        @Test
        fun `a failing harness check alone counts as the receiver view`() {
            val assertions =
                listOf(
                    assertion("read_announce", "a04", HARNESS, FAILED, "<= 5000 ms", "9100 ms", type = "latency_max"),
                    assertion("read_announce", "a04", ORACLE, PASSED),
                )

            val finding = judge.findings(run, assertions).single()

            finding.findingClass shouldBe FindingClass.DELIVERY_UI
            finding.b shouldBe "<= 5000 ms -> 9100 ms"
        }

        @Test
        fun `group level assertions without an agent form their own group`() {
            val assertions =
                listOf(
                    assertion("race", null, SENDER, FAILED, "exactly one success", "2 succeeded", type = "only_one_succeeds"),
                    assertion("race", "a03", SENDER, PASSED),
                )

            val finding = judge.findings(run, assertions).single()

            finding.agentId.shouldBeNull()
            finding.findingClass shouldBe FindingClass.INVESTIGATE
            finding.a shouldBe "exactly one success -> 2 succeeded"
        }

        @Test
        fun `the finding points at the step of its first failing assertion`() {
            val assertions =
                listOf(
                    assertion("read_announce", "a02", ORACLE, PASSED, stepId = "stp_oracle"),
                    assertion("read_announce", "a02", RECEIVER, FAILED, stepId = "stp_visible"),
                )

            judge.findings(run, assertions).single().stepId shouldBe StepId("stp_visible")
        }

        @Test
        fun `artifact ids are the distinct union of the group's evidence`() {
            val assertions =
                listOf(
                    assertion("read_announce", "a02", RECEIVER, FAILED, artifacts = listOf("art_1", "art_2")),
                    assertion("read_announce", "a02", HARNESS, FAILED, artifacts = listOf("art_2")),
                    assertion("read_announce", "a02", ORACLE, PASSED, artifacts = listOf("art_3")),
                )

            judge.findings(run, assertions).single().artifactIds shouldContainExactly
                listOf(ArtifactId("art_1"), ArtifactId("art_2"), ArtifactId("art_3"))
        }

        @Test
        fun `notes of failing assertions are kept in the finding note`() {
            val assertions =
                listOf(
                    assertion("read_announce", "a02", RECEIVER, FAILED, note = "text not visible within 5s"),
                    assertion("read_announce", "a02", ORACLE, PASSED, note = "ignored because passed"),
                )

            val note = judge.findings(run, assertions).single().note

            note shouldContain "delivery or UI error"
            note shouldEndWith "Details: text not visible within 5s"
            note shouldNotContain "ignored because passed"
        }

        @Test
        fun `values are compacted to one bounded line`() {
            val body = "{\n  \"items\": [" + "x".repeat(500) + "]\n}"
            val assertions = listOf(assertion("announce", "a01", ORACLE, FAILED, "status = published", body))

            val c = judge.findings(run, assertions).single().c!!

            c shouldNotContain "\n"
            c.length shouldBe 200 + "status = published -> ".length
            c shouldEndWith "…"
        }

        @Test
        fun `assertions of other runs are ignored`() {
            val other = assertion("announce", "a01", ORACLE, FAILED, runId = RunId("run_other"))

            judge.findings(run, listOf(other)).shouldBeEmpty()
        }
    }

    @Nested
    inner class FindingsFromSteps {
        private val run = run()

        @Test
        fun `a mail timeout is a backend finding because no e-mail was sent`() {
            val steps =
                listOf(
                    step(
                        "join",
                        "a07",
                        StepStatus.FAILED,
                        StepKind.RUN,
                        detail = "mail_timeout: no verification e-mail within 60s",
                        action = "run register_and_login",
                        stepId = "stp_join_a07",
                    ),
                )

            val finding = judge.findings(run, emptyList(), steps).single()

            finding.findingClass shouldBe FindingClass.BACKEND
            finding.c shouldBe "no e-mail sent"
            finding.a shouldBe "run register_and_login"
            finding.b shouldBe "mail_timeout: no verification e-mail within 60s"
            finding.note shouldContain "no e-mail sent"
            finding.stepId shouldBe StepId("stp_join_a07")
            finding.agentId shouldBe AgentId("a07")
            finding.scenarioStep shouldBe "join"
        }

        @Test
        fun `any other failure key is an agent failure`() {
            val steps =
                listOf(
                    step("join", "a08", StepStatus.FAILED, StepKind.RUN, detail = "otp_rejected: code 123456 refused"),
                    step("announce", "a01", StepStatus.ERROR, detail = "browser_error: context crashed"),
                )

            val findings = judge.findings(run, emptyList(), steps)

            findings.map { it.findingClass } shouldContainExactly listOf(FindingClass.AGENT_FAILURE, FindingClass.AGENT_FAILURE)
            findings.map { it.note } shouldContainExactly listOf("Agent failure: otp_rejected.", "Agent failure: browser_error.")
            findings.forEach { it.c.shouldBeNull() }
        }

        @Test
        fun `a racer whose own request the target turned down is investigated, not blamed on the agent`() {
            val detail = "request_failed: POST /tickets/t2/approve -> 500; agent: Ticket approved"
            val steps = listOf(step("race", "a03", StepStatus.FAILED, detail = detail, action = "do: Eyni ticketi approve et"))

            val finding = judge.findings(run, emptyList(), steps).single()

            finding.findingClass shouldBe FindingClass.INVESTIGATE
            finding.note shouldContain "turned down the actor's own request"
            finding.b shouldBe detail
            finding.c.shouldBeNull()
        }

        @Test
        fun `an unreachable test inbox is an agent failure explained as an environment problem`() {
            val steps =
                listOf(
                    step(
                        "join",
                        "a07",
                        StepStatus.ERROR,
                        StepKind.RUN,
                        detail = "mail_unavailable: Test inbox unreachable: Mailpit at http://127.0.0.1:8025: search failed",
                        action = "run register_and_login",
                    ),
                )

            val finding = judge.findings(run, emptyList(), steps).single()

            finding.findingClass shouldBe FindingClass.AGENT_FAILURE
            finding.note shouldBe
                "Agent failure: mail_unavailable (test inbox unreachable: an environment problem, not an error of the target)."
            finding.c.shouldBeNull()
            finding.a shouldBe "run register_and_login"
            finding.b shouldBe "mail_unavailable: Test inbox unreachable: Mailpit at http://127.0.0.1:8025: search failed"
        }

        @Test
        fun `an inbox that timed out is one mail_unavailable finding, not also a timeout`() {
            val mailpit = "Mailpit at http://127.0.0.1:8025: search failed (HttpRequestTimeoutException: Request timeout has expired)"
            val unreachable = "Test inbox unreachable: $mailpit"
            val steps =
                listOf(
                    // The agent loop's last turn of the `do` step, then the orchestrator's record of its outcome.
                    step(
                        "owner_signup",
                        "a01",
                        StepStatus.ERROR,
                        detail = "ERROR: $unreachable | outcome: ERROR mail_unavailable: $unreachable",
                        action = "get_email_code",
                        stepId = "stp_turn",
                    ),
                    step("owner_signup", "a01", StepStatus.ERROR, detail = "mail_unavailable: $unreachable"),
                    // A run function's sub-action that threw, then its concluding record.
                    step("join", "a07", StepStatus.ERROR, StepKind.RUN, detail = "mail_unavailable: $mailpit", stepId = "stp_sub"),
                    step("join", "a07", StepStatus.ERROR, StepKind.RUN, detail = "mail_unavailable: $unreachable"),
                )

            val findings = judge.findings(run, emptyList(), steps)

            findings.map { it.scenarioStep to it.agentId?.value } shouldContainExactly listOf("owner_signup" to "a01", "join" to "a07")
            findings.forEach {
                it.findingClass shouldBe FindingClass.AGENT_FAILURE
                it.note shouldContain "Agent failure: mail_unavailable (test inbox unreachable"
            }
        }

        @Test
        fun `a blocked step without a key is an agent failure named blocked`() {
            val steps = listOf(step("read_announce", "a09", StepStatus.BLOCKED, detail = "no progress for 120s"))

            val finding = judge.findings(run, emptyList(), steps).single()

            finding.findingClass shouldBe FindingClass.AGENT_FAILURE
            finding.note shouldBe "Agent failure: blocked."
        }

        @Test
        fun `failed steps without a failure key and completed steps yield no finding`() {
            val steps =
                listOf(
                    step("announce", "a01", StepStatus.FAILED, detail = "element not found"),
                    step("announce", "a01", StepStatus.FAILED, detail = null),
                    step("join", "a02", StepStatus.PASSED, detail = "mail_timeout recovered on retry"),
                    step("join", "a03", StepStatus.SKIPPED, detail = "mail_timeout"),
                )

            judge.findings(run, emptyList(), steps).shouldBeEmpty()
        }

        @Test
        fun `assertion steps and harness steps are not agent failures`() {
            val steps =
                listOf(
                    step("read_announce", "a02", StepStatus.FAILED, StepKind.ASSERT, detail = "timeout"),
                    step("teardown", null, StepStatus.ERROR, StepKind.SYSTEM, detail = "timeout: oracle did not answer"),
                )

            judge.findings(run, emptyList(), steps).shouldBeEmpty()
        }

        @Test
        fun `a forbidden action the target refused is the expected outcome and no finding`() {
            val steps =
                listOf(
                    step("forbidden", "a12", StepStatus.BLOCKED, detail = "permission_denied: no approve button", stepId = "stp_1"),
                    step(
                        "forbidden",
                        "a13",
                        StepStatus.BLOCKED,
                        detail = null,
                        action = "report_problem permission_denied",
                        stepId = "stp_2",
                    ),
                )

            judge.findings(run, emptyList(), steps).shouldBeEmpty()
        }

        @Test
        fun `a wait that timed out is not an agent failure`() {
            val steps =
                listOf(
                    step("read_announce", "a05", StepStatus.FAILED, StepKind.WAIT, detail = "not_received: announcement_created in 300s"),
                    step("read_announce", "a06", StepStatus.FAILED, StepKind.WAIT, detail = "timeout"),
                )

            judge.findings(run, emptyList(), steps).shouldBeEmpty()
        }

        @Test
        fun `repeated failures of one agent in one step collapse into one finding`() {
            val steps =
                listOf(
                    step("join", "a07", StepStatus.FAILED, StepKind.RUN, detail = "mail_timeout: first", stepId = "stp_1"),
                    step("join", "a07", StepStatus.FAILED, StepKind.RUN, detail = "mail_timeout: retry", stepId = "stp_2"),
                    step("join", "a07", StepStatus.FAILED, StepKind.RUN, detail = "login_failed", stepId = "stp_3"),
                )

            val findings = judge.findings(run, emptyList(), steps)

            findings.map { it.stepId } shouldContainExactly listOf(StepId("stp_1"), StepId("stp_3"))
        }

        @Test
        fun `assertion findings come first and step findings follow`() {
            val assertions = listOf(assertion("announce", "a01", ORACLE, FAILED))
            val steps = listOf(step("join", "a07", StepStatus.FAILED, StepKind.RUN, detail = "mail_timeout"))

            val findings = judge.findings(run, assertions, steps)

            findings.map { it.scenarioStep } shouldContainExactly listOf("announce", "join")
            findings.map { it.findingId } shouldContainExactly listOf(FindingId("fnd_1"), FindingId("fnd_2"))
        }

        @Test
        fun `steps of other runs are ignored`() {
            val steps = listOf(step("join", "a07", StepStatus.FAILED, detail = "mail_timeout", runId = RunId("run_other")))

            judge.findings(run, emptyList(), steps).shouldBeEmpty()
        }

        @Test
        fun `a judge written against the two-argument contract ignores steps by default`() {
            val legacy =
                object : Judge {
                    override fun classify(
                        a: Observation?,
                        b: Observation?,
                        c: Observation?,
                    ): JudgeVerdict = JudgeVerdict.Pass

                    override fun findings(
                        run: RunRecord,
                        assertions: List<AssertionRecord>,
                    ): List<FindingRecord> = emptyList()
                }
            val steps = listOf(step("join", "a07", StepStatus.FAILED, detail = "mail_timeout"))

            legacy.findings(run, emptyList(), steps).shouldBeEmpty()
        }
    }
}
