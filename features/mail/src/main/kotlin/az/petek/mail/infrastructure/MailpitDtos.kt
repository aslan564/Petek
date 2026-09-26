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

package az.petek.mail.infrastructure

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire shapes of Mailpit's REST API v1 (Go field names). Only the fields Pətək reads; unknown keys are ignored and
// Go's `null` for empty slices is tolerated.

@Serializable
internal data class MailpitSearchResult(
    @SerialName("messages") val messages: List<MailpitSummary>? = null,
)

@Serializable
internal data class MailpitSummary(
    @SerialName("ID") val id: String,
    @SerialName("Read") val read: Boolean = false,
    @SerialName("Created") val created: String? = null,
    @SerialName("To") val to: List<MailpitAddress>? = null,
    @SerialName("Subject") val subject: String? = null,
)

@Serializable
internal data class MailpitAddress(
    @SerialName("Name") val name: String? = null,
    @SerialName("Address") val address: String? = null,
)

@Serializable
internal data class MailpitMessage(
    @SerialName("ID") val id: String? = null,
    @SerialName("To") val to: List<MailpitAddress>? = null,
    @SerialName("Subject") val subject: String? = null,
    @SerialName("Text") val text: String? = null,
    @SerialName("HTML") val html: String? = null,
)

@Serializable
internal data class MailpitReadStatus(
    @SerialName("IDs") val ids: List<String>,
    @SerialName("Read") val read: Boolean,
)
