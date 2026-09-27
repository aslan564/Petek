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

package az.petek.faketarget.service

import az.petek.faketarget.model.Notification
import az.petek.faketarget.model.NotificationType
import az.petek.faketarget.store.FakeTargetStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

class NotificationHubTest {
    private val hub = NotificationHub()

    private fun notification(
        recipient: String,
        sequence: Long,
    ) = Notification(
        "n$sequence",
        sequence,
        "c1",
        recipient,
        NotificationType.ANNOUNCEMENT,
        "a1",
        "Elan",
        "/announcements/a1",
        Instant.EPOCH,
        null,
    )

    @Test
    fun `every open stream of the recipient gets the notification, in order, and nobody else does`() =
        runTest {
            val tab1 = hub.subscribe("a@x.example")
            val tab2 = hub.subscribe("a@x.example")
            val other = hub.subscribe("b@x.example")
            hub.openStreams("a@x.example") shouldBe 2

            hub.publish(notification("a@x.example", 1))
            hub.publish(notification("a@x.example", 2))

            listOf(tab1.notifications.receive().id, tab1.notifications.receive().id) shouldBe listOf("n1", "n2")
            tab2.notifications.receive().id shouldBe "n1"
            other.notifications.tryReceive().isFailure shouldBe true
        }

    @Test
    fun `a closed stream is forgotten and publishing to nobody is harmless`() {
        val stream = hub.subscribe("a@x.example")
        stream.close()
        hub.openStreams("a@x.example") shouldBe 0
        stream.notifications.tryReceive().isClosed shouldBe true
        hub.publish(notification("a@x.example", 1))
        hub.publish(notification("nobody@x.example", 2))
    }

    @Test
    fun `recipients whose streams are all closed leave nothing behind`() {
        val tab1 = hub.subscribe("a@x.example")
        val tab2 = hub.subscribe("a@x.example")
        hub.connectedRecipients() shouldBe 1
        tab1.close()
        hub.openStreams("a@x.example") shouldBe 1
        tab2.close()
        tab2.close()
        hub.connectedRecipients() shouldBe 0
    }

    @Test
    fun `a stream opened after a disconnect is registered afresh and a late close of an ended stream is harmless`() {
        val old = hub.subscribe("a@x.example")
        hub.disconnect(listOf("a@x.example"))
        val fresh = hub.subscribe("a@x.example")
        old.close()
        hub.openStreams("a@x.example") shouldBe 1
        hub.publish(notification("a@x.example", 1))
        fresh.notifications
            .tryReceive()
            .getOrNull()
            ?.id shouldBe "n1"
    }

    @Test
    fun `a live stream for someone who no longer exists is ended at once`() {
        val service = NotificationService(FakeTargetStore(), hub, Clock.systemUTC())
        val stream = service.open("ghost@x.example", afterSequence = 0)
        stream.backlog shouldBe emptyList()
        stream.subscription.notifications
            .tryReceive()
            .isClosed shouldBe true
        hub.connectedRecipients() shouldBe 0
    }

    @Test
    fun `disconnecting people or the whole hub ends their streams`() {
        val a = hub.subscribe("a@x.example")
        val b = hub.subscribe("b@x.example")
        hub.disconnect(listOf("a@x.example"))
        a.notifications.tryReceive().isClosed shouldBe true
        b.notifications.tryReceive().isClosed shouldBe false
        hub.closeAll()
        b.notifications.tryReceive().isClosed shouldBe true
        hub.openStreams("b@x.example") shouldBe 0
    }
}
