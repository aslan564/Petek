package az.petek.mail.application

import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailTimeoutException
import az.petek.mail.domain.Mailbox
import az.petek.mail.domain.MailboxException
import az.petek.mail.domain.VerificationCode
import az.petek.mail.domain.VerificationExtractor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Polls [mailbox] until a message satisfying the purpose arrives, then marks exactly that message read.
 *
 * Each poll looks at up to [candidatesPerPoll] of the newest unread messages received since `since` and takes the
 * newest usable one, so an unrelated newer mail (a welcome message after the code) does not hide the code. Messages
 * that do not satisfy the purpose are left unread: another step may still need them (the invitation link is read
 * after the code in some flows).
 *
 * A [MailboxException] is retried at the next poll. If the mailbox is still failing when the time is up, that
 * failure is thrown instead of [MailTimeoutException]: "the inbox is unreachable" is an environment problem, while
 * `mail_timeout` is a finding about the target. Only `delay`/`withTimeoutOrNull` measure time, so virtual time works.
 * [awaitLink] polls the same way, with the extractor's pattern rule deciding which message is usable.
 */
class DefaultAwaitVerificationUseCase(
    private val mailbox: Mailbox,
    private val extractor: VerificationExtractor,
    private val candidatesPerPoll: Int = 10,
) : AwaitVerificationUseCase {
    init {
        require(candidatesPerPoll > 0) { "candidatesPerPoll must be positive, was $candidatesPerPoll" }
    }

    override suspend fun await(
        to: String,
        since: Instant,
        purpose: MailPurpose,
        timeout: Duration,
        pollInterval: Duration,
    ): VerificationCode {
        val poller = Poller(to, since, purpose.name.lowercase()) { extractor.extract(it, purpose) }
        return poller.await(timeout, pollInterval)
    }

    override suspend fun awaitLink(
        to: String,
        since: Instant,
        pattern: Regex,
        timeout: Duration,
        pollInterval: Duration,
    ): VerificationCode {
        val poller = Poller(to, since, "link matching '${pattern.pattern}'") { extractor.extractLink(it, pattern) }
        return poller.await(timeout, pollInterval)
    }

    /** One wait for one tester's message; [wanted] names what is looked for in log lines. */
    private inner class Poller(
        private val to: String,
        private val since: Instant,
        private val wanted: String,
        private val extract: (MailMessage) -> VerificationCode?,
    ) {
        /** Failure of the most recent poll; cleared by a successful one. */
        var lastFailure: MailboxException? = null
            private set
        private val reportedSkips = mutableSetOf<String>()

        suspend fun await(
            timeout: Duration,
            pollInterval: Duration,
        ): VerificationCode {
            require(timeout.isPositive()) { "timeout must be positive, was $timeout" }
            require(pollInterval.isPositive()) { "pollInterval must be positive, was $pollInterval" }
            val found = withTimeoutOrNull(timeout) { pollUntilFound(pollInterval) }
            return found ?: throw (lastFailure ?: MailTimeoutException(to, timeout))
        }

        suspend fun pollUntilFound(pollInterval: Duration): VerificationCode {
            while (true) {
                try {
                    val code = pollOnce()
                    lastFailure = null
                    if (code != null) return code
                } catch (e: MailboxException) {
                    if (lastFailure == null) logger.warn { "Mailbox unavailable while waiting for mail to $to, retrying: ${e.message}" }
                    lastFailure = e
                }
                delay(pollInterval)
            }
        }

        private suspend fun pollOnce(): VerificationCode? {
            val candidates =
                mailbox
                    .findRecent(to, since, unreadOnly = true, limit = candidatesPerPoll)
                    .filter { !it.read && !it.receivedAt.isBefore(since) }
                    .sortedByDescending { it.receivedAt }
            for (message in candidates) {
                val code = extract(message)
                if (code != null) {
                    mailbox.markRead(message.id)
                    return code
                }
                reportSkip(message)
            }
            return null
        }

        private fun reportSkip(message: MailMessage) {
            if (reportedSkips.add(message.id)) {
                logger.info { "Mail ${message.id} to $to ('${message.subject}') has no $wanted; still waiting" }
            }
        }
    }
}
