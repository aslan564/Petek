package az.petek.faketarget.service

import az.petek.faketarget.model.Notification
import az.petek.faketarget.model.NotificationType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
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
            val tab1 = hub.subscribe("a@x.az")
            val tab2 = hub.subscribe("a@x.az")
            val other = hub.subscribe("b@x.az")
            hub.openStreams("a@x.az") shouldBe 2

            hub.publish(notification("a@x.az", 1))
            hub.publish(notification("a@x.az", 2))

            listOf(tab1.notifications.receive().id, tab1.notifications.receive().id) shouldBe listOf("n1", "n2")
            tab2.notifications.receive().id shouldBe "n1"
            other.notifications.tryReceive().isFailure shouldBe true
        }

    @Test
    fun `a closed stream is forgotten and publishing to nobody is harmless`() {
        val stream = hub.subscribe("a@x.az")
        stream.close()
        hub.openStreams("a@x.az") shouldBe 0
        stream.notifications.tryReceive().isClosed shouldBe true
        hub.publish(notification("a@x.az", 1))
        hub.publish(notification("nobody@x.az", 2))
    }

    @Test
    fun `disconnecting people or the whole hub ends their streams`() {
        val a = hub.subscribe("a@x.az")
        val b = hub.subscribe("b@x.az")
        hub.disconnect(listOf("a@x.az"))
        a.notifications.tryReceive().isClosed shouldBe true
        b.notifications.tryReceive().isClosed shouldBe false
        hub.closeAll()
        b.notifications.tryReceive().isClosed shouldBe true
        hub.openStreams("b@x.az") shouldBe 0
    }
}
