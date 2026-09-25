package az.petek.verification.domain

import az.petek.browser.domain.HttpProbeResult
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.AssertionSpec.HttpStatus
import az.petek.campaign.domain.AssertionSpec.Oracle
import az.petek.core.testing.FakeHarnessClock
import az.petek.evidence.domain.EvidenceSource
import az.petek.evidence.domain.Verdict
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.OracleResponse
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.testing.FakeTargetOracle
import az.petek.verification.testing.FakeTemplateRenderer
import az.petek.verification.testing.ScriptedSession
import az.petek.verification.testing.SimpleJsonFieldSelector
import az.petek.verification.testing.assertionInput
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TargetAnswerAssertionsTest {
    private val clock = FakeHarnessClock()
    private val session = ScriptedSession(clock)
    private val oracle = FakeTargetOracle()

    private fun evaluator(target: TargetOracle = oracle) =
        DefaultAssertionEvaluator(target, FakeTemplateRenderer(), SimpleJsonFieldSelector(), clock)

    private suspend fun evaluateOne(
        spec: AssertionSpec,
        target: TargetOracle = oracle,
    ) = evaluator(target).evaluate(listOf(spec), assertionInput(session)).single()

    private val ticket =
        """
        {"id": "42", "title": "Printer", "department": "IT", "status": "in_progress",
         "assignee": {"email": "a03.k7x2@test.kadrohr.com"}, "created_by": "a05.k7x2@test.kadrohr.com",
         "history": [{"from": "open", "to": "in_progress", "by": "a03.k7x2@test.kadrohr.com"}],
         "watchers": 3, "is_test": true, "audience": ["a01.k7x2@test.kadrohr.com", "a02.k7x2@test.kadrohr.com"]}
        """.trimIndent()

    // --- oracle ---------------------------------------------------------------------------------------------------

    @Test
    fun `oracle is skipped with a note when the target has no test API`() =
        runTest {
            val result = evaluateOne(Oracle("/test/tickets/{last_id}", "status", "open", null), FakeTargetOracle(isAvailable = false))

            result.verdict shouldBe Verdict.SKIPPED
            result.source shouldBe EvidenceSource.ORACLE
            result.note shouldBe "no test API"
            result.rawEvidence.shouldBeNull()
            result.expected shouldBe "GET /test/tickets/42 field `status` = \"open\""
        }

    @Test
    fun `oracle renders the path and compares a field with equals`() =
        runTest {
            oracle.respond("/test/tickets/42", ticket)

            val result = evaluateOne(Oracle("/test/tickets/{last_id}", "status", "in_progress", null))

            result.verdict shouldBe Verdict.PASSED
            result.source shouldBe EvidenceSource.ORACLE
            result.expected shouldBe "GET /test/tickets/42 field `status` = \"in_progress\""
            result.observed shouldBe "status = in_progress"
            result.rawEvidence shouldBe ticket
            result.latency.shouldBeNull()
        }

    @Test
    fun `oracle equals fails with both values in the note`() =
        runTest {
            oracle.respond("/test/tickets/42", ticket)

            val result = evaluateOne(Oracle("/test/tickets/{last_id}", "status", "approved", null))

            result.verdict shouldBe Verdict.FAILED
            result.observed shouldBe "status = in_progress"
            result.note shouldBe "expected \"approved\" but was \"in_progress\""
            result.rawEvidence shouldBe ticket
        }

    @Test
    fun `oracle selects nested and indexed fields and renders the expected value`() =
        runTest {
            oracle.respond("/test/tickets/42", ticket)
            val input = assertionInput(session)
            val results =
                evaluator().evaluate(
                    listOf(
                        Oracle("/test/tickets/{last_id}", "assignee.email", "a03.k7x2@test.kadrohr.com", null),
                        Oracle("/test/tickets/{last_id}", "history[0].to", "in_progress", null),
                        Oracle("/test/tickets/{last_id}", "created_by", "{self.email}", null),
                    ),
                    input,
                )

            results[0].verdict shouldBe Verdict.PASSED
            results[1].verdict shouldBe Verdict.PASSED
            results[2].verdict shouldBe Verdict.FAILED
            results[2].expected shouldBe "GET /test/tickets/42 field `created_by` = \"a01.k7x2@test.kadrohr.com\""
        }

    @Test
    fun `oracle compares number and boolean primitives by their content`() =
        runTest {
            oracle.respond("/test/tickets/42", ticket)

            evaluateOne(Oracle("/test/tickets/42", "watchers", "3", null)).verdict shouldBe Verdict.PASSED
            evaluateOne(Oracle("/test/tickets/42", "is_test", "true", null)).verdict shouldBe Verdict.PASSED
            evaluateOne(Oracle("/test/tickets/42", "watchers", "3.0", null)).verdict shouldBe Verdict.FAILED
        }

    @Test
    fun `oracle equals on a null field compares with the text null`() =
        runTest {
            oracle.respond("/test/tickets/43", """{"id": "43", "assignee": null}""")

            evaluateOne(Oracle("/test/tickets/43", "assignee", "null", null)).verdict shouldBe Verdict.PASSED
        }

    @Test
    fun `oracle contains checks the text of the selected field`() =
        runTest {
            oracle.respond("/test/tickets/42", ticket)

            val inAudience = evaluateOne(Oracle("/test/tickets/42", "audience", null, "{self.email}"))
            val notInTitle = evaluateOne(Oracle("/test/tickets/42", "title", null, "{self.email}"))

            inAudience.verdict shouldBe Verdict.PASSED
            inAudience.expected shouldBe "GET /test/tickets/42 field `audience` contains \"a01.k7x2@test.kadrohr.com\""
            notInTitle.verdict shouldBe Verdict.FAILED
            notInTitle.note shouldBe "\"a01.k7x2@test.kadrohr.com\" not found in field `title`"
        }

    @Test
    fun `oracle contains without a field searches the raw body`() =
        runTest {
            val receipts = """{"announcement_id": "42", "receipts": [{"email": "a01.k7x2@test.kadrohr.com", "read_at": "t"}]}"""
            oracle.respond("/test/announcements/42/receipts", receipts)
            oracle.respond("/test/announcements/43/receipts", """{"announcement_id": "43", "receipts": []}""")

            val present = evaluateOne(Oracle("/test/announcements/{last_id}/receipts", null, null, "{self.email}"))
            val absent = evaluateOne(Oracle("/test/announcements/43/receipts", null, null, "{self.email}"))

            present.verdict shouldBe Verdict.PASSED
            present.observed shouldBe receipts
            present.rawEvidence shouldBe receipts
            absent.verdict shouldBe Verdict.FAILED
            absent.note shouldBe "\"a01.k7x2@test.kadrohr.com\" not found in the body"
        }

    @Test
    fun `oracle with equals and contains requires both`() =
        runTest {
            oracle.respond("/test/tickets/42", ticket)

            val both = evaluateOne(Oracle("/test/tickets/42", "status", "in_progress", "progress"))
            val onlyEquals = evaluateOne(Oracle("/test/tickets/42", "status", "in_progress", "approved"))
            val onlyContains = evaluateOne(Oracle("/test/tickets/42", "status", "open", "progress"))

            both.verdict shouldBe Verdict.PASSED
            both.expected shouldBe "GET /test/tickets/42 field `status` = \"in_progress\" and contains \"progress\""
            onlyEquals.verdict shouldBe Verdict.FAILED
            onlyEquals.note shouldBe "\"approved\" not found in field `status`"
            onlyContains.verdict shouldBe Verdict.FAILED
            onlyContains.note shouldBe "expected \"open\" but was \"in_progress\""
        }

    @Test
    fun `oracle equals without a field compares the whole body`() =
        runTest {
            oracle.respond("/test/flag", "\"published\"")
            oracle.respond("/test/text", """{"a": 1}""")

            evaluateOne(Oracle("/test/flag", null, "published", null)).verdict shouldBe Verdict.PASSED
            evaluateOne(Oracle("/test/text", null, """{"a": 1}""", null)).verdict shouldBe Verdict.PASSED
        }

    @Test
    fun `oracle without equals or contains only needs a 2xx answer and an existing field`() =
        runTest {
            oracle.respond("/test/tickets/42", ticket)

            evaluateOne(Oracle("/test/tickets/42", null, null, null)).apply {
                verdict shouldBe Verdict.PASSED
                expected shouldBe "GET /test/tickets/42 answers 2xx"
            }
            evaluateOne(Oracle("/test/tickets/42", "assignee", null, null)).apply {
                verdict shouldBe Verdict.PASSED
                expected shouldBe "GET /test/tickets/42 field `assignee` exists"
                observed shouldBe "assignee = {\"email\":\"a03.k7x2@test.kadrohr.com\"}"
            }
            evaluateOne(Oracle("/test/tickets/42", "approver", null, null)).apply {
                verdict shouldBe Verdict.FAILED
                observed shouldBe "field `approver` missing"
            }
        }

    @Test
    fun `oracle fails on a non-2xx answer and keeps the body as evidence`() =
        runTest {
            oracle.respond("/test/tickets/42", """{"error": "not found"}""", status = 404)
            oracle.respond("/test/tickets/43", """{"error": "boom"}""", status = 500)

            val missing = evaluateOne(Oracle("/test/tickets/{last_id}", "status", "open", null))
            val broken = evaluateOne(Oracle("/test/tickets/43", null, null, null))

            missing.verdict shouldBe Verdict.FAILED
            missing.observed shouldBe "HTTP 404"
            missing.note shouldBe "oracle answered 404, expected 2xx"
            missing.rawEvidence shouldBe """{"error": "not found"}"""
            broken.verdict shouldBe Verdict.FAILED
            broken.observed shouldBe "HTTP 500"
        }

    @Test
    fun `oracle field selection on a non-JSON body fails while contains still searches the raw text`() =
        runTest {
            oracle.responses["/test/page"] = OracleResponse(200, null, "<html>Printer ticket</html>")

            val field = evaluateOne(Oracle("/test/page", "status", "open", null))
            val text = evaluateOne(Oracle("/test/page", null, null, "Printer"))

            field.verdict shouldBe Verdict.FAILED
            field.observed shouldBe "body is not JSON"
            field.rawEvidence shouldBe "<html>Printer ticket</html>"
            text.verdict shouldBe Verdict.PASSED
        }

    @Test
    fun `oracle clips a long observed value but keeps the full body as evidence`() =
        runTest {
            val long = "x".repeat(2000)
            oracle.respond("/test/long", """{"text": "$long"}""")

            val result = evaluateOne(Oracle("/test/long", "text", "y", null))

            result.observed!!.length shouldBe "text = ".length + 500 + "… (1500 more chars)".length
            result.observed shouldEndWith "… (1500 more chars)"
            result.rawEvidence!! shouldContain long
        }

    @Test
    fun `an oracle failure becomes a FAILED result with a note`() =
        runTest {
            val broken =
                object : TargetOracle by FakeTargetOracle() {
                    override suspend fun get(path: String): OracleResponse = throw OracleException("connection refused")
                }

            val result = evaluateOne(Oracle("/test/tickets/42", "status", "open", null), broken)

            result.verdict shouldBe Verdict.FAILED
            result.note shouldBe "oracle check failed: OracleException: connection refused"
            result.rawEvidence.shouldBeNull()
        }

    // --- http_status ------------------------------------------------------------------------------------------------

    @Test
    fun `http_status passes when the target answers the expected status and keeps status and body as evidence`() =
        runTest {
            session.fake.httpResponses["POST /api/tickets/42/approve"] = HttpProbeResult(403, """{"error":"forbidden"}""")

            val result = evaluateOne(HttpStatus("/api/tickets/{last_id}/approve", "post", 403))

            result.verdict shouldBe Verdict.PASSED
            result.source shouldBe EvidenceSource.ORACLE
            result.expected shouldBe "POST /api/tickets/42/approve -> 403"
            result.observed shouldBe "403"
            result.rawEvidence shouldBe """403 {"error":"forbidden"}"""
            session.fake.actions shouldContain "request POST /api/tickets/42/approve"
        }

    @Test
    fun `http_status fails when the target answers another status`() =
        runTest {
            session.fake.httpResponses["POST /api/tickets/42/approve"] = HttpProbeResult(200, """{"status":"approved"}""")

            val result = evaluateOne(HttpStatus("/api/tickets/{last_id}/approve", "POST", 403))

            result.verdict shouldBe Verdict.FAILED
            result.observed shouldBe "200"
            result.note shouldBe "target answered 200, expected 403"
            result.rawEvidence shouldBe """200 {"status":"approved"}"""
        }

    @Test
    fun `http_status evidence keeps at most 2 KB of the body without splitting characters`() =
        runTest {
            session.fake.httpResponses["GET /api/big"] = HttpProbeResult(200, "ə".repeat(3000))

            val result = evaluateOne(HttpStatus("/api/big", "GET", 200))

            val body = result.rawEvidence!!.removePrefix("200 ")
            body shouldBe "ə".repeat(1024)
            body.toByteArray().size shouldBe 2048
        }

    @Test
    fun `http_status with an empty body still records the status`() =
        runTest {
            val result = evaluateOne(HttpStatus("/api/unknown", "DELETE", 404))

            result.verdict shouldBe Verdict.PASSED
            result.rawEvidence shouldBe "404 "
        }
}
