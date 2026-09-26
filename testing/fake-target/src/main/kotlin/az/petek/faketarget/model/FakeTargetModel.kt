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

package az.petek.faketarget.model

import java.time.Instant

/*
 * Immutable snapshots of the fake target's state. People are referenced by their (lower-case) e-mail, which is unique
 * and never changes, exactly like the test API reports them.
 */

/** Role inside a company; [key] is the wire and UI form (`admin`, `manager`, `employee`). */
enum class UserRole(
    val key: String,
    /** Azerbaijani label shown in the UI. */
    val label: String,
) {
    ADMIN("admin", "Admin"),
    MANAGER("manager", "Menecer"),
    EMPLOYEE("employee", "İşçi"),
    ;

    companion object {
        fun fromKey(key: String): UserRole? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** A tenant. Only companies owned by a test-domain e-mail are `is_test` and may be seeded or deleted. */
data class Company(
    val id: String,
    val name: String,
    /** Join code such as `PTK-4821`, unique per server. */
    val code: String,
    val ownerEmail: String,
    val isTest: Boolean,
    val createdAt: Instant,
)

/** A department of one company; names are unique within the company. */
data class Department(
    val id: String,
    val companyId: String,
    val name: String,
)

/** A member of one company. A session exists only once the required verifications are done. */
data class User(
    val id: String,
    val companyId: String,
    val name: String,
    val email: String,
    val phone: String,
    val role: UserRole,
    /** Null for the owner (admin) and for invitees without a department. */
    val department: Department?,
    val emailVerified: Boolean,
    val phoneVerified: Boolean,
    val createdAt: Instant,
)

/** A pending or accepted invitation; the token is the last path segment of the e-mailed `/invite/{token}` link. */
data class Invitation(
    val token: String,
    val companyId: String,
    val email: String,
    val name: String,
    val role: UserRole,
    val department: Department?,
    val createdAt: Instant,
    /** Set once the invitee registered (through the link or by joining with the company code). */
    val acceptedAt: Instant?,
)

/** An admin's announcement, always `published` once created. */
data class Announcement(
    val id: String,
    val companyId: String,
    val title: String,
    val body: String,
    val createdBy: String,
    val createdAt: Instant,
    /** Every other member of the company at creation time. */
    val audience: List<String>,
    /** Recipients silently skipped by [az.petek.faketarget.FakeBug.DROP_NOTIFICATION_FOR_ONE_USER]. */
    val droppedRecipients: Set<String>,
) {
    val status: String get() = "published"
}

/** First time a recipient read an announcement (list, detail page or notification list). */
data class Receipt(
    val announcementId: String,
    val email: String,
    val readAt: Instant,
)

/** What a notification is about; [key] is the test API form. */
enum class NotificationType(
    val key: String,
) {
    ANNOUNCEMENT("announcement"),
    TICKET_CREATED("ticket_created"),
    TICKET_ASSIGNED("ticket_assigned"),
    TICKET_STATUS("ticket_status"),
}

/** One notification of one recipient, stored and pushed live over `/events`. */
data class Notification(
    /** `n<sequence>`. */
    val id: String,
    /** Server-wide, strictly increasing; live streams resume after it. */
    val sequence: Long,
    val companyId: String,
    val recipient: String,
    val type: NotificationType,
    /** Id of the announcement or ticket the notification is about. */
    val objectId: String,
    val text: String,
    /** Page that shows the object, e.g. `/announcements/a1`. */
    val link: String,
    val createdAt: Instant,
    val readAt: Instant?,
)

/** Ticket workflow `open -> in_progress -> approved | rejected`; [key] is the wire form, [label] the UI text. */
enum class TicketStatus(
    val key: String,
    val label: String,
) {
    OPEN("open", "Açıq"),
    IN_PROGRESS("in_progress", "İcrada"),
    APPROVED("approved", "Təsdiqlənib"),
    REJECTED("rejected", "Rədd edilib"),
    ;

    val isDecided: Boolean get() = this == APPROVED || this == REJECTED
}

/** One status change, as `/test/tickets/{id}` reports it in `history`. */
data class TicketHistoryEntry(
    val from: TicketStatus,
    val to: TicketStatus,
    val by: String,
    val at: Instant,
)

/** A request raised by any member for one department. */
data class Ticket(
    val id: String,
    val companyId: String,
    val title: String,
    val description: String,
    val department: Department,
    val status: TicketStatus,
    val assignee: String?,
    val createdBy: String,
    val createdAt: Instant,
    /** Every status change, oldest first. */
    val history: List<TicketHistoryEntry>,
)
