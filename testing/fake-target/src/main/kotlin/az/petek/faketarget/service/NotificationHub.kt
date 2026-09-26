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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Live delivery of notifications to the open `/events` streams of each user. Publishing never blocks or drops
 * (unbounded per-stream buffers), so it is safe to call inside a store transaction, which keeps every stream in
 * notification order.
 *
 * Registering, closing and disconnecting are atomic per recipient ([ConcurrentHashMap.compute]), so a stream opened
 * while its owner is being disconnected is either closed by the disconnect or never registered, and recipients
 * without open streams leave no entry behind.
 */
internal class NotificationHub {
    private val streams = ConcurrentHashMap<String, Set<Subscription>>()

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
            streams.computeIfPresent(recipient) { _, open -> (open - this).ifEmpty { null } }
            buffer.close()
        }

        internal fun end() {
            buffer.close()
        }
    }

    fun subscribe(recipient: String): Subscription =
        Subscription(recipient).also { subscription -> streams.compute(recipient) { _, open -> open.orEmpty() + subscription } }

    fun publish(notification: Notification) {
        streams[notification.recipient]?.forEach { it.offer(notification) }
    }

    /** Number of open streams of [recipient] (for tests and diagnostics). */
    fun openStreams(recipient: String): Int = streams[recipient]?.size ?: 0

    /** Number of recipients with at least one open stream (for tests and diagnostics). */
    fun connectedRecipients(): Int = streams.size

    /** Ends the streams of people who no longer exist (their company was deleted). */
    fun disconnect(recipients: Collection<String>) {
        recipients.forEach { recipient -> streams.remove(recipient)?.forEach(Subscription::end) }
    }

    fun closeAll() {
        disconnect(streams.keys.toList())
    }
}
