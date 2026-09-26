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

import az.petek.faketarget.FakeBug
import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.model.Announcement
import az.petek.faketarget.model.NotificationType
import az.petek.faketarget.model.User
import az.petek.faketarget.model.UserRole
import az.petek.faketarget.store.FakeTargetStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Clock

private val logger = KotlinLogging.logger {}

/**
 * Announcements: only the admin publishes; every other member of the company is the audience and gets a notification
 * after [FakeTargetConfig.notificationDelay] (immediately, inside the request, when the delay is zero).
 */
internal class AnnouncementService(
    private val store: FakeTargetStore,
    private val config: FakeTargetConfig,
    private val notifications: NotificationService,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    fun create(
        author: User,
        title: String,
        body: String,
    ): Outcome<Announcement> {
        if (author.role != UserRole.ADMIN) return Failure.FORBIDDEN.failed()
        val cleanTitle = Inputs.validTitle(title) ?: return Failure.TITLE_REQUIRED.failed()
        val now = clock.instant()
        val announcement =
            store.transaction {
                val audience = membersOf(author.companyId).filter { it.email != author.email }
                val dropped = if (config.has(FakeBug.DROP_NOTIFICATION_FOR_ONE_USER)) setOfNotNull(dropVictim(audience)) else emptySet()
                Announcement(
                    id = nextId("a"),
                    companyId = author.companyId,
                    title = cleanTitle,
                    body = body.trim(),
                    createdBy = author.email,
                    createdAt = now,
                    audience = audience.map { it.email },
                    droppedRecipients = dropped,
                ).also { announcements[it.id] = it }
            }
        if (config.notificationDelay.isPositive()) {
            scope.launch {
                delay(config.notificationDelay)
                fanOut(announcement)
            }
        } else {
            fanOut(announcement)
        }
        return announcement.ok()
    }

    /** The company's announcements, newest first. Viewing the list counts as reading every one of them. */
    fun openList(user: User): List<Announcement> {
        val now = clock.instant()
        return store.transaction {
            announcements.values
                .filter { it.companyId == user.companyId }
                .onEach { recordAnnouncementRead(it.id, user.email, now) }
                .asReversed()
        }
    }

    fun open(
        user: User,
        id: String,
    ): Outcome<Announcement> {
        val now = clock.instant()
        return store.transaction {
            val announcement =
                announcements[id]?.takeIf { it.companyId == user.companyId } ?: return@transaction Failure.ANNOUNCEMENT_NOT_FOUND.failed()
            recordAnnouncementRead(id, user.email, now)
            announcement.ok()
        }
    }

    private fun fanOut(announcement: Announcement) {
        val delivered =
            notifications.notify(
                companyId = announcement.companyId,
                recipients = announcement.audience - announcement.droppedRecipients,
                type = NotificationType.ANNOUNCEMENT,
                objectId = announcement.id,
                text = announcement.title,
                link = "/announcements/${announcement.id}",
            )
        logger.debug { "Announcement ${announcement.id} delivered to ${delivered.size} of ${announcement.audience.size} recipients" }
    }

    internal companion object {
        /** The recipient [FakeBug.DROP_NOTIFICATION_FOR_ONE_USER] skips: the first employee by e-mail, else the first recipient. */
        fun dropVictim(audience: List<User>): String? =
            audience.sortedWith(compareBy<User> { it.role != UserRole.EMPLOYEE }.thenBy { it.email }).firstOrNull()?.email
    }
}
