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

package az.petek.faketarget.api

import az.petek.faketarget.model.Announcement
import az.petek.faketarget.model.Company
import az.petek.faketarget.model.Notification
import az.petek.faketarget.model.Receipt
import az.petek.faketarget.model.Ticket
import az.petek.faketarget.model.User
import az.petek.faketarget.service.Failure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// JSON shapes of the test API (docs/TARGET_CONTRACT.md section 4, snake_case) and of the regular JSON API.

@Serializable
internal data class ErrorJson(
    val error: String,
    val message: String,
) {
    constructor(failure: Failure) : this(failure.code, failure.message)
}

@Serializable
internal data class OtpJson(
    val phone: String,
    val code: String,
)

@Serializable
internal data class CompanyJson(
    val id: String,
    val name: String,
    val code: String,
    @SerialName("is_test") val isTest: Boolean,
    val owner: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
internal data class SeedRequestJson(
    @SerialName("company_id") val companyId: String,
    val departments: List<String> = emptyList(),
    val invites: List<SeedInviteJson> = emptyList(),
)

@Serializable
internal data class SeedInviteJson(
    val email: String,
    val name: String = "",
    val role: String = "employee",
    val department: String? = null,
)

@Serializable
internal data class SeedResponseJson(
    @SerialName("company_id") val companyId: String,
    val code: String,
    val departments: Map<String, String>,
    val invites: List<InviteLinkJson>,
)

@Serializable
internal data class InviteLinkJson(
    val email: String,
    val link: String,
)

@Serializable
internal data class AnnouncementJson(
    val id: String,
    val title: String,
    val body: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("created_by") val createdBy: String,
    val audience: List<String>,
)

@Serializable
internal data class ReceiptJson(
    val email: String,
    @SerialName("read_at") val readAt: String,
)

@Serializable
internal data class ReceiptsJson(
    @SerialName("announcement_id") val announcementId: String,
    val receipts: List<ReceiptJson>,
)

@Serializable
internal data class AssigneeJson(
    val email: String,
)

@Serializable
internal data class HistoryJson(
    val from: String,
    val to: String,
    val by: String,
    val at: String,
)

@Serializable
internal data class TicketJson(
    val id: String,
    val title: String,
    val description: String,
    val department: String,
    val status: String,
    val assignee: AssigneeJson?,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    val history: List<HistoryJson>,
)

@Serializable
internal data class NotificationJson(
    val id: String,
    val type: String,
    @SerialName("object_id") val objectId: String,
    val text: String,
    val link: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String?,
)

@Serializable
internal data class NotificationsJson(
    val notifications: List<NotificationJson>,
)

@Serializable
internal data class MeJson(
    val id: String,
    val name: String,
    val email: String,
    val role: String,
    val department: String?,
    @SerialName("company_id") val companyId: String,
)

@Serializable
internal data class AssignRequestJson(
    val email: String = "",
)

internal fun Company.toJson() = CompanyJson(id, name, code, isTest, ownerEmail, createdAt.toString())

internal fun Announcement.toJson() = AnnouncementJson(id, title, body, status, createdAt.toString(), createdBy, audience)

internal fun Receipt.toJson() = ReceiptJson(email, readAt.toString())

internal fun Ticket.toJson() =
    TicketJson(
        id = id,
        title = title,
        description = description,
        department = department.name,
        status = status.key,
        assignee = assignee?.let(::AssigneeJson),
        createdBy = createdBy,
        createdAt = createdAt.toString(),
        history = history.map { HistoryJson(it.from.key, it.to.key, it.by, it.at.toString()) },
    )

internal fun Notification.toJson() = NotificationJson(id, type.key, objectId, text, link, createdAt.toString(), readAt?.toString())

internal fun User.toMeJson() = MeJson(id, name, email, role.key, department?.name, companyId)
