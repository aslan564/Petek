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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire shapes of the Mailpit REST API v1 (field names as Mailpit spells them).

@Serializable
internal data class MailpitAddress(
    @SerialName("Name") val name: String,
    @SerialName("Address") val address: String,
)

@Serializable
internal data class MailpitSummary(
    @SerialName("ID") val id: String,
    @SerialName("MessageID") val messageId: String,
    @SerialName("Read") val read: Boolean,
    @SerialName("From") val from: MailpitAddress,
    @SerialName("To") val to: List<MailpitAddress>,
    @SerialName("Cc") val cc: List<MailpitAddress> = emptyList(),
    @SerialName("Bcc") val bcc: List<MailpitAddress> = emptyList(),
    @SerialName("ReplyTo") val replyTo: List<MailpitAddress> = emptyList(),
    @SerialName("Subject") val subject: String,
    @SerialName("Created") val created: String,
    @SerialName("Username") val username: String = "",
    @SerialName("Tags") val tags: List<String> = emptyList(),
    @SerialName("Size") val size: Int,
    @SerialName("Attachments") val attachments: Int = 0,
    @SerialName("Snippet") val snippet: String,
)

@Serializable
internal data class MailpitList(
    /** Messages in the whole mailbox (Mailpit semantics, also for searches). */
    @SerialName("total") val total: Int,
    @SerialName("unread") val unread: Int,
    /** Messages on this page. */
    @SerialName("count") val count: Int,
    /** Messages matching the query (the whole mailbox for a plain listing). */
    @SerialName("messages_count") val messagesCount: Int,
    @SerialName("messages_unread") val messagesUnread: Int,
    @SerialName("start") val start: Int,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("messages") val messages: List<MailpitSummary>,
)

@Serializable
internal data class MailpitMessage(
    @SerialName("ID") val id: String,
    @SerialName("MessageID") val messageId: String,
    @SerialName("From") val from: MailpitAddress,
    @SerialName("To") val to: List<MailpitAddress>,
    @SerialName("Cc") val cc: List<MailpitAddress> = emptyList(),
    @SerialName("Bcc") val bcc: List<MailpitAddress> = emptyList(),
    @SerialName("ReplyTo") val replyTo: List<MailpitAddress> = emptyList(),
    @SerialName("ReturnPath") val returnPath: String,
    @SerialName("Subject") val subject: String,
    @SerialName("Date") val date: String,
    @SerialName("Tags") val tags: List<String> = emptyList(),
    @SerialName("Username") val username: String = "",
    @SerialName("Text") val text: String,
    @SerialName("HTML") val html: String,
    @SerialName("Size") val size: Int,
    @SerialName("Inline") val inline: List<String> = emptyList(),
    @SerialName("Attachments") val attachments: List<String> = emptyList(),
)

@Serializable
internal data class MailpitReadRequest(
    @SerialName("IDs") val ids: List<String> = emptyList(),
    @SerialName("Read") val read: Boolean = true,
    @SerialName("Search") val search: String = "",
)

@Serializable
internal data class MailpitDeleteRequest(
    @SerialName("IDs") val ids: List<String> = emptyList(),
)

@Serializable
internal data class MailpitInfo(
    @SerialName("Version") val version: String,
    @SerialName("Database") val database: String,
    @SerialName("Messages") val messages: Int,
    @SerialName("Unread") val unread: Int,
)
