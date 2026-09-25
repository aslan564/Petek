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
