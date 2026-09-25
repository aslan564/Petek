package az.petek.faketarget.service

import az.petek.faketarget.model.Notification
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Live delivery of notifications to the open `/events` streams of each user. Publishing never blocks or drops
 * (unbounded per-stream buffers), so it is safe to call inside a store transaction, which keeps every stream in
 * notification order.
 */
internal class NotificationHub {
    private val streams = ConcurrentHashMap<String, MutableSet<Subscription>>()

    /** One open stream. Closing it (or the hub) ends the stream's channel. */
    inner class Subscription(
        val recipient: String,
    ) : AutoCloseable {
        private val buffer = Channel<Notification>(Channel.UNLIMITED)
        val notifications: ReceiveChannel<Notification> get() = buffer

        internal fun offer(notification: Notification) {
            buffer.trySend(notification)
        }

        override fun close() {
            streams[recipient]?.remove(this)
            buffer.close()
        }
    }

    fun subscribe(recipient: String): Subscription =
        Subscription(recipient).also { streams.computeIfAbsent(recipient) { ConcurrentHashMap.newKeySet() }.add(it) }

    fun publish(notification: Notification) {
        streams[notification.recipient]?.forEach { it.offer(notification) }
    }

    /** Number of open streams of [recipient] (for tests and diagnostics). */
    fun openStreams(recipient: String): Int = streams[recipient]?.size ?: 0

    /** Ends the streams of people who no longer exist (their company was deleted). */
    fun disconnect(recipients: Collection<String>) {
        recipients.forEach { recipient -> streams.remove(recipient)?.forEach { it.close() } }
    }

    fun closeAll() {
        disconnect(streams.keys.toList())
    }
}
