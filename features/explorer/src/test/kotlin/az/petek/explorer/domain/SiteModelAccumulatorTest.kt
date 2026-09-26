/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.explorer.domain

import az.petek.browser.domain.RealtimeTransport
import az.petek.core.ids.ArtifactId
import az.petek.explorer.support.Models
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

class SiteModelAccumulatorTest {
    private val target = URI("https://portal.test")
    private val accumulator = SiteModelAccumulator(target)

    private fun visit(
        role: String,
        pattern: String,
        vararg forms: FormModel,
        purpose: String? = null,
    ): PageModel =
        accumulator.recordPage(
            PageObservation(
                role = role,
                urlPattern = pattern,
                url = target.resolve(pattern.replace("{id}", "t1")),
                title = "Title of $pattern",
                purpose = purpose,
                forms = forms.toList(),
                testIds = listOf("x-$role"),
                linkCount = 2,
                loadMs = 100,
                evidence = listOf(ArtifactId("art_${role}_$pattern")),
            ),
        )

    private fun candidate(
        testId: String,
        kind: ActionKind = ActionKind.CREATE,
        provenance: Provenance = Provenance.OBSERVED,
        name: String = testId,
    ) = ActionCandidate(name, kind, Selectors.testId(testId), "POST", "/p", provenance)

    @Test
    fun `a page seen by several roles is one entry that remembers who reached it`() {
        accumulator.role("admin", anonymous = false)
        accumulator.role("employee", anonymous = false)
        visit("admin", "/tickets", Models.form(ActionKind.CREATE, "ticket-submit", "/tickets", Models.field("title")), purpose = "Tickets")
        visit(
            "employee",
            "/tickets",
            Models.form(ActionKind.CREATE, "ticket-submit", "/tickets", Models.field("title"), Models.field("priority")),
        )

        val page = accumulator.build(1, ExplorationId("exp_1"), Models.AT).pages.single()

        page.reachableBy shouldBe setOf("admin", "employee")
        page.purpose shouldBe "Tickets"
        page.forms
            .single()
            .fields
            .map { it.name } shouldContainExactly listOf("title", "priority")
        page.testIds shouldContainExactly listOf("x-admin", "x-employee")
        page.evidence.size shouldBe 2
        accumulator.exampleUrl("/tickets", "employee") shouldBe URI("https://portal.test/tickets")
    }

    @Test
    fun `an action offered to one role and not to another role that saw the page is forbidden for that role`() {
        accumulator.role("anonymous", anonymous = true)
        accumulator.role("admin", anonymous = false)
        accumulator.role("employee", anonymous = false)
        accumulator.role("manager", anonymous = false)
        val page = visit("admin", "/announcements")
        visit("employee", "/announcements")
        visit("anonymous", "/login")
        accumulator.recordAction("admin", page.id, candidate("announcement-submit"), emptyList()).shouldNotBeNull()

        val action = accumulator.build(1, ExplorationId("exp_1"), Models.AT).actions.single()

        action.allowedRoles shouldBe setOf("admin")
        // employee saw the page without the button; manager never got there, so nothing is inferred about it.
        action.forbiddenRoles shouldBe setOf("employee")
    }

    @Test
    fun `a role refused the page is forbidden too, anonymous never is`() {
        accumulator.role("anonymous", anonymous = true)
        accumulator.role("admin", anonymous = false)
        accumulator.role("employee", anonymous = false)
        val page = visit("admin", "/company")
        accumulator.recordDenied("employee", "/company")
        accumulator.recordDenied("anonymous", "/company")
        accumulator.recordAction("admin", page.id, candidate("company-invite"), emptyList())

        val model = accumulator.build(1, ExplorationId("exp_1"), Models.AT)

        model.actions.single().forbiddenRoles shouldBe setOf("employee")
        model.roles.first { it.name == "employee" }.deniedPatterns shouldBe setOf("/company")
    }

    @Test
    fun `an element seen again merges roles, and code-found kinds win over the LLM's reading`() {
        accumulator.role("manager", anonymous = false)
        accumulator.role("admin", anonymous = false)
        val page = visit("manager", "/tickets/{id}")
        val first =
            accumulator.recordAction(
                "manager",
                page.id,
                candidate("ticket-approve", ActionKind.OTHER, Provenance.INFERRED, "Approve it"),
                emptyList(),
            )
        val again =
            accumulator.recordAction(
                "admin",
                page.id,
                candidate("ticket-approve", ActionKind.APPROVE, Provenance.OBSERVED, "Təsdiqlə"),
                emptyList(),
            )
        val llmAgain =
            accumulator.recordAction(
                "admin",
                page.id,
                candidate("ticket-approve", ActionKind.REJECT, Provenance.INFERRED),
                emptyList(),
            )

        first.shouldNotBeNull().id shouldBe "ticket-approve"
        again.shouldBeNull()
        llmAgain.shouldBeNull()
        val action = accumulator.actions().single()
        action.kind shouldBe ActionKind.APPROVE
        action.name shouldBe "Təsdiqlə"
        action.provenance shouldBe Provenance.OBSERVED
        action.allowedRoles shouldBe setOf("manager", "admin")
    }

    @Test
    fun `actions without a test id are identified per page and get readable unique ids`() {
        val page = visit("anonymous", "/contact")
        val selector = Selectors.role("button", "Göndər")
        val a =
            accumulator.recordAction(
                "anonymous",
                page.id,
                ActionCandidate("Göndər", ActionKind.SUBMIT, selector, null, null, Provenance.OBSERVED),
                emptyList(),
            )
        val b =
            accumulator.recordAction(
                "anonymous",
                page.id,
                ActionCandidate("Göndər", ActionKind.SUBMIT, Selectors.role("link", "Göndər"), null, null, Provenance.INFERRED),
                emptyList(),
            )

        a.shouldNotBeNull().id shouldBe "contact.gonder"
        b.shouldNotBeNull().id shouldBe "contact.gonder-2"
    }

    @Test
    fun `pages with patterns that slug alike still get distinct ids`() {
        visit("anonymous", "/a-b")
        visit("anonymous", "/a/b")

        accumulator.pages().map { it.id } shouldContainExactly listOf("a-b", "a-b-2")
        accumulator.pageIdOf("/a/b") shouldBe "a-b-2"
    }

    @Test
    fun `the same question is asked only once and trials mark live actions`() {
        val page = visit("admin", "/announcements")
        accumulator.recordAction("admin", page.id, candidate("announcement-submit"), emptyList())

        accumulator.recordUnknown("Kim görməlidir?", "", page.id, Provenance.INFERRED, emptyList()).shouldNotBeNull().id shouldBe "u1"
        accumulator.recordUnknown("  KIM   görməlidir ", "", page.id, Provenance.INFERRED, emptyList()).shouldBeNull()
        accumulator.recordTrial("announcement-submit", Models.trial(setOf("employee"), role = "admin"))
        accumulator.recordRealtime(RealtimeTransport.SSE, listOf("SSE /events"), page.id, "admin", emptyList()) shouldBe true
        accumulator.recordRealtime(RealtimeTransport.SSE, listOf("SSE /events"), "other", "employee", emptyList()) shouldBe false

        val model = accumulator.build(3, ExplorationId("exp_3"), Models.AT)
        model.actions.single().triggersRealtime shouldBe true
        model.actions
            .single()
            .trial!!
            .seenLiveBy shouldBe setOf("employee")
        model.realtime.single().pages shouldBe setOf(page.id, "other")
        model.realtime.single().roles shouldBe setOf("admin", "employee")
        model.unknowns.single().question shouldBe "Kim görməlidir?"
        model.counts(findings = 4) shouldBe ModelCounts(pages = 1, forms = 0, actions = 1, realtime = 1, unknowns = 1, findings = 4)
    }
}
