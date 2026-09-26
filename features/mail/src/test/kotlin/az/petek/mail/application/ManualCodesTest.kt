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

package az.petek.mail.application

import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.VerificationCode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ManualCodesTest {
    private var now = Instant.parse("2026-09-26T10:00:00Z")
    private val desk = ManualCodeDesk { now }
    private val mailbox = ManualCodeMailbox(desk)
    private val since = now.minusSeconds(1)

    @Test
    fun `a waiting tester is listed once until the owner types its code, which then reads as a message`() =
        runBlocking<Unit> {
            mailbox.findLatest("Test+A01@company.az", since).shouldBeNull()
            mailbox.findLatest("test+a01@company.az", since).shouldBeNull()
            val request = desk.pending().single()
            request.to shouldBe "test+a01@company.az"

            now = now.plusSeconds(30)
            desk.answer(request.id, " 123456 ") shouldBe true

            desk.pending().shouldBeEmpty()
            val message = mailbox.findLatest("test+a01@company.az", since).shouldNotBeNull()
            message.text shouldBe "123456"
            message.receivedAt shouldBe now
            mailbox.markRead(message.id)
            mailbox.findLatest("test+a01@company.az", since).shouldBeNull()
            desk.pending() shouldHaveSize 1
        }

    @Test
    fun `an unknown request or a code with spaces or symbols is refused`() {
        desk.answer("m99", "123456") shouldBe false
        val request = desk.ask("test+a02@company.az")
        desk.answer(request.id, "12 34") shouldBe false
        desk.answer(request.id, "<script>") shouldBe false
        desk.pending() shouldHaveSize 1
    }

    @Test
    fun `a human gets minutes to type the code, a longer wait stays as asked`() =
        runBlocking<Unit> {
            val waits = mutableListOf<Duration>()
            val base =
                object : AwaitVerificationUseCase {
                    override suspend fun await(
                        to: String,
                        since: Instant,
                        purpose: MailPurpose,
                        timeout: Duration,
                        pollInterval: Duration,
                    ): VerificationCode {
                        waits += timeout
                        return VerificationCode("1", null, "m")
                    }
                }
            val patient = PatientVerification(base)

            patient.await("a@b.az", since, MailPurpose.CODE, 60.seconds)
            patient.await("a@b.az", since, MailPurpose.CODE, 10.minutes)

            waits shouldBe listOf(5.minutes, 10.minutes)
        }
}
