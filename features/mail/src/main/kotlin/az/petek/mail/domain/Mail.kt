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

    /**
     * Messages to [to] received at or after [since], newest first, at most [limit]; unread only when [unreadOnly].
     * Reading them has no side effect on their read state (only [markRead] changes it). This lets a reader skip a newer
     * unrelated message (a welcome mail right after the code) and still reach the usable one below it.
     * The default only sees [findLatest]; adapters that can list the inbox override it.
     */
    suspend fun findRecent(
        to: String,
        since: Instant,
        unreadOnly: Boolean = true,
        limit: Int = 10,
    ): List<MailMessage> = listOfNotNull(findLatest(to, since, unreadOnly))

    /** Marks a message read so an old code is never reused. */
    suspend fun markRead(messageId: String)
}

/**
 * The inbox could not be read: unreachable, timed out or answered something unexpected. Distinct from
 * [MailTimeoutException] ("reachable, but the target sent nothing"), which is a finding about the target.
 */
class MailboxException(
    message: String,
    cause: Throwable? = null,
) : PetekException(message, cause)

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
