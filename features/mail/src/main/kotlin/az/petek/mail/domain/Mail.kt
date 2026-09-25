package az.petek.mail.domain

import az.petek.core.error.PetekException
import java.net.URI
import java.time.Instant
import kotlin.time.Duration

data class MailMessage(
    val id: String,
    val to: List<String>,
    val subject: String,
    val receivedAt: Instant,
    val text: String,
    val html: String?,
    val read: Boolean,
)

/** What a verification or invitation e-mail gives the tester: a numeric code, a link, or both. */
data class VerificationCode(
    val code: String?,
    val link: URI?,
    val messageId: String,
)

enum class MailPurpose { CODE, LINK, ANY }

/** Port over the catch-all test inbox (Mailpit in the MVP; IMAP later behind the same interface). */
interface Mailbox {
    /** Newest message to [to] received at or after [since]; unread only when [unreadOnly]. Null if none yet. */
    suspend fun findLatest(
        to: String,
        since: Instant,
        unreadOnly: Boolean = true,
    ): MailMessage?

    /** Marks a message read so an old code is never reused. */
    suspend fun markRead(messageId: String)
}

/** Pulls a 4–8 digit code and/or a confirmation/invite link out of a message. Pure. */
interface VerificationExtractor {
    fun extract(
        message: MailMessage,
        purpose: MailPurpose,
    ): VerificationCode?
}

class MailTimeoutException(
    val to: String,
    val timeout: Duration,
) : PetekException("No verification e-mail for $to within $timeout (mail_timeout)")
