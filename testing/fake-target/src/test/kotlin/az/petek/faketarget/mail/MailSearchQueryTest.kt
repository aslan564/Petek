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

package az.petek.faketarget.mail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class MailSearchQueryTest {
    private val invitation =
        SentMail(
            id = "id1",
            messageId = "id1@fake",
            from = MailAddress("Demo Portal", "no-reply@fake-target.local"),
            to = listOf(MailAddress("Ayan Əliyeva", "ayan@test.portal.example")),
            subject = "Dəvət",
            text = "Qoşulmaq üçün keçid: http://127.0.0.1/invite/abc",
            html = "<p>…</p>",
            createdAt = Instant.EPOCH,
            read = false,
        )

    private fun matches(query: String) = MailSearchQuery.parse(query).matches(invitation)

    @Test
    fun `to matches the address or the name as a case-insensitive substring`() {
        matches("to:ayan@test.portal.example") shouldBe true
        matches("to:\"AYAN@TEST.PORTAL.EXAMPLE\"") shouldBe true
        matches("to:test.portal.example") shouldBe true
        matches("to:\"Ayan Əliyeva\"") shouldBe true
        matches("to:other@test.portal.example") shouldBe false
    }

    @Test
    fun `terms combine with and and can be negated`() {
        matches("to:ayan subject:dəvət") shouldBe true
        matches("to:ayan subject:kod") shouldBe false
        matches("-subject:kod") shouldBe true
        matches("!to:ayan") shouldBe false
    }

    @Test
    fun `from, is and free text are supported`() {
        matches("from:no-reply") shouldBe true
        matches("is:unread") shouldBe true
        matches("is:read") shouldBe false
        matches(invitation.copy(read = true), "is:read") shouldBe true
        matches("invite/abc") shouldBe true
        matches("\"üçün keçid\"") shouldBe true
        matches("mailpit") shouldBe false
    }

    @Test
    fun `empty queries and unknown keys behave like Mailpit`() {
        matches("") shouldBe true
        matches("   ") shouldBe true
        matches("to:") shouldBe true
        matches("is:starred") shouldBe true
        matches("cc:ayan") shouldBe false
        matches("keçid:") shouldBe true
    }

    private fun matches(
        mail: SentMail,
        query: String,
    ) = MailSearchQuery.parse(query).matches(mail)
}
