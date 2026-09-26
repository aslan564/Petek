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

package az.petek.campaign.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class DefaultTemplateRendererTest {
    private val renderer = DefaultTemplateRenderer()

    private val context =
        TemplateContext(
            lastId = "42",
            self =
                mapOf(
                    "email" to "eli.k7x2.a07@test.portal.example",
                    "name" to "Əli Kərimov",
                    "agent_id" to "a07",
                    "department" to "IT",
                    "role" to "employee",
                    "phone" to "+994501234567",
                ),
            eventIds = mapOf("announcement_created" to "17", "ticket_created" to "99"),
            testers = mapOf("manager.1" to mapOf("name" to "Sahil Quliyev", "email" to "sahil.k7x2.a02@test.portal.example")),
        )

    @Test
    fun `last_id is replaced by the latest object id`() {
        renderer.render("/test/announcements/{last_id}/receipts", context) shouldBe "/test/announcements/42/receipts"
    }

    @Test
    fun `self fields come from the tester identity`() {
        renderer.render("{self.name} <{self.email}> {self.agent_id} {self.department} {self.role} {self.phone}", context) shouldBe
            "Əli Kərimov <eli.k7x2.a07@test.portal.example> a07 IT employee +994501234567"
    }

    @Test
    fun `event ids are looked up by event name`() {
        renderer.render("/api/tickets/{event.ticket_created.id}/approve?a={event.announcement_created.id}", context) shouldBe
            "/api/tickets/99/approve?a=17"
    }

    @Test
    fun `another tester's name and e-mail come by role and number, and nothing else of them`() {
        renderer.render("Dəvət et: {tester.manager.1.name} <{tester.manager.1.email}>", context) shouldBe
            "Dəvət et: Sahil Quliyev <sahil.k7x2.a02@test.portal.example>"
        shouldThrow<TemplateException> { renderer.render("{tester.manager.2.email}", context) }.message shouldContain
            "there is no tester 2 of role 'manager'"
        shouldThrow<TemplateException> { renderer.render("{tester.manager.1.password}", context) }
    }

    @Test
    fun `text without braces is returned unchanged`() {
        val text = "Sabah 10:00 ümumi iclas"
        renderer.render(text, context) shouldBe text
        renderer.render("", context) shouldBe ""
    }

    @Test
    fun `brace text that is not a placeholder stays literal`() {
        renderer.render("""/items/(\d{3})""", context) shouldBe """/items/(\d{3})"""
        renderer.render("""{"id": {last_id}}""", context) shouldBe """{"id": 42}"""
        renderer.render("{Self.Email} {} { last_id }", context) shouldBe "{Self.Email} {} { last_id }"
    }

    @Test
    fun `the same placeholder may appear several times`() {
        renderer.render("{last_id}-{last_id}", context) shouldBe "42-42"
    }

    @Test
    fun `values are inserted verbatim and not rendered again`() {
        val tricky = context.copy(lastId = "\$1 {self.email} \\n")
        renderer.render("id={last_id}", tricky) shouldBe "id=\$1 {self.email} \\n"
    }

    @Test
    fun `an unknown placeholder fails naming it`() {
        val error = shouldThrow<TemplateException> { renderer.render("/x/{foo}", context) }
        error.message shouldContain "Unknown placeholder {foo}"
    }

    @Test
    fun `malformed event placeholders are unknown`() {
        shouldThrow<TemplateException> { renderer.render("{event.id}", context) }.message shouldContain "{event.id}"
        shouldThrow<TemplateException> { renderer.render("{event.ticket_created.name}", context) }.message shouldContain
            "Unknown placeholder {event.ticket_created.name}"
    }

    @Test
    fun `a missing last id fails instead of producing a wrong URL`() {
        val error = shouldThrow<TemplateException> { renderer.render("/t/{last_id}", context.copy(lastId = null)) }
        error.message shouldContain "{last_id}"
        error.message shouldContain "no object id has been emitted yet"
    }

    @Test
    fun `a blank value counts as missing instead of producing a wrong URL`() {
        val blank = context.copy(lastId = " ", self = context.self + ("department" to ""), eventIds = mapOf("ticket_created" to ""))
        val error =
            shouldThrow<TemplateException> {
                renderer.render("/t/{last_id}/{event.ticket_created.id}?d={self.department}", blank)
            }
        error.message shouldContain "{last_id} cannot be resolved"
        error.message shouldContain "{event.ticket_created.id} cannot be resolved"
        error.message shouldContain "has no 'department' (available: agent_id, email, name, phone, role)"
    }

    @Test
    fun `a missing self field fails naming the field and the available ones`() {
        val error = shouldThrow<TemplateException> { renderer.render("{self.password}", context) }
        error.message shouldContain "{self.password}"
        error.message shouldContain "has no 'password'"
        error.message shouldContain "agent_id, department, email"
    }

    @Test
    fun `an event that was not emitted fails naming it`() {
        val error = shouldThrow<TemplateException> { renderer.render("{event.vote_cast.id}", context) }
        error.message shouldContain "event 'vote_cast' has not been emitted yet"
    }

    @Test
    fun `all unresolvable placeholders are reported together`() {
        val error = shouldThrow<TemplateException> { renderer.render("{foo} {bar}", context) }
        error.message shouldContain "{foo}"
        error.message shouldContain "{bar}"
    }

    @Test
    fun `placeholders are listed in order of first appearance without duplicates`() {
        renderer.placeholders("/a/{last_id}/{self.email}/{last_id}/{event.x.id}/{Nope}/{2,3}") shouldContainExactly
            listOf("last_id", "self.email", "event.x.id")
    }

    @Test
    fun `text without placeholders has none`() {
        renderer.placeholders("no braces here").shouldBeEmpty()
        renderer.placeholders("""\d{3}""").shouldBeEmpty()
    }

    @Test
    fun `placeholder names are classified`() {
        Placeholder.parse("last_id") shouldBe Placeholder.LastId
        Placeholder.parse("self.email") shouldBe Placeholder.Self("email")
        Placeholder.parse("event.ticket_created.id") shouldBe Placeholder.EventId("ticket_created")
        Placeholder.parse("event.a.b.id") shouldBe Placeholder.EventId("a.b")
        Placeholder.parse("self.") shouldBe null
        Placeholder.parse("event..id") shouldBe null
        Placeholder.parse("lastid") shouldBe null
        Placeholder.EventId("ticket_created").name shouldBe "event.ticket_created.id"
        Placeholder.Self("phone").name shouldBe "self.phone"
    }

    @Test
    fun `campaign files cannot reach the password`() {
        ("password" in Placeholder.CAMPAIGN_SELF_FIELDS) shouldBe false
    }
}
