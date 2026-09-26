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
import az.petek.faketarget.model.Receipt
import az.petek.faketarget.model.User
import az.petek.faketarget.store.FakeTargetStore
import az.petek.faketarget.store.StoreState
import java.time.Clock
import java.time.Instant

/** What the header of every logged-in page shows. */
internal data class NotificationPanel(
    /** Newest first, at most [NotificationService.PANEL_SIZE]. */
    val items: List<Notification>,
    val unread: Int,
    /** Id of the user's newest notification; the live stream resumes after it. */
    val lastId: String?,
)

/** A live stream plus what the client missed since [afterSequence], taken atomically so nothing slips in between. */
internal data class NotificationStream(
    val subscription: NotificationHub.Subscription,
    val backlog: List<Notification>,
)

/** Stores notifications, pushes them live and records reads (and announcement receipts). */
internal class NotificationService(
    private val store: FakeTargetStore,
    private val hub: NotificationHub,
    private val clock: Clock,
) {
    /** Stores one notification per recipient that still belongs to [companyId] and pushes each live. */
    fun notify(
        companyId: String,
        recipients: Collection<String>,
        type: NotificationType,
        objectId: String,
        text: String,
        link: String,
    ): List<Notification> {
        val now = clock.instant()
        return store.transaction {
            recipients
                .distinct()
                .filter { users[it]?.companyId == companyId }
                .map { recipient ->
                    val sequence = nextSequence("n")
                    Notification("n$sequence", sequence, companyId, recipient, type, objectId, text, link, now, readAt = null)
                        .also { notifications[it.id] = it }
                        // Published inside the lock so every stream sees notifications in sequence order.
                        .also(hub::publish)
                }
        }
    }

    /**
     * Opens a live stream. A recipient who no longer exists (their company was deleted after the session was checked)
     * gets an already ended stream, so it cannot outlive the [NotificationHub.disconnect] that already ran.
     */
    fun open(
        recipient: String,
        afterSequence: Long,
    ): NotificationStream =
        store.transaction {
            val subscription = hub.subscribe(recipient)
            if (recipient !in users) {
                subscription.close()
                return@transaction NotificationStream(subscription, emptyList())
            }
            val backlog = notifications.values.filter { it.recipient == recipient && it.sequence > afterSequence }
            NotificationStream(subscription, backlog)
        }

    fun panel(user: User): NotificationPanel =
        store.transaction {
            val mine = notifications.values.filter { it.recipient == user.email }
            NotificationPanel(
                items = mine.asReversed().take(PANEL_SIZE),
                unread = mine.count { it.readAt == null },
                lastId = mine.lastOrNull()?.id,
            )
        }

    /** Newest first. */
    fun all(user: User): List<Notification> = store.notifications(user.email).asReversed()

    /** Opening the notification list: everything becomes read and announcement receipts are recorded. */
    fun openList(user: User) {
        val now = clock.instant()
        store.transaction {
            notifications.values
                .filter { it.recipient == user.email }
                .forEach { notification ->
                    if (notification.type == NotificationType.ANNOUNCEMENT) recordAnnouncementRead(notification.objectId, user.email, now)
                    if (notification.readAt == null) notifications[notification.id] = notification.copy(readAt = now)
                }
        }
    }

    companion object {
        const val PANEL_SIZE = 20

        /** Parses `n12` (or `12`) into a sequence number; anything else means "from the start". */
        fun sequenceOf(id: String?): Long =
            id
                ?.trim()
                ?.removePrefix("n")
                ?.toLongOrNull()
                ?.coerceAtLeast(0) ?: 0
    }
}

/**
 * Records that [email] read an announcement: the first read time becomes the receipt and the matching notifications
 * are marked read. Ignored for people outside the audience and for recipients dropped by the notification bug.
 */
internal fun StoreState.recordAnnouncementRead(
    announcementId: String,
    email: String,
    now: Instant,
) {
    val announcement = announcements[announcementId] ?: return
    if (email !in announcement.audience || email in announcement.droppedRecipients) return
    receipts.putIfAbsent(receiptKey(announcementId, email), Receipt(announcementId, email, now))
    notifications.values
        .filter { it.recipient == email && it.type == NotificationType.ANNOUNCEMENT && it.objectId == announcementId && it.readAt == null }
        .forEach { notifications[it.id] = it.copy(readAt = now) }
}
