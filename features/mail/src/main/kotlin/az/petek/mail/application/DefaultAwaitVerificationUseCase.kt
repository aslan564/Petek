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
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Polls [mailbox] until a message satisfying the purpose arrives, then marks exactly that message read.
 *
 * Each poll looks at up to [candidatesPerPoll] of the newest unread messages received since `since` and takes the
 * newest usable one, so an unrelated newer mail (a welcome message after the code) does not hide the code. Which
 * messages count as received since `since` is the mailbox's to say ([Mailbox.findRecent]): only it knows how precisely
 * its server keeps arrivals (an IMAP server to the second), so its answer is never filtered by time again here. Messages
 * that do not satisfy the purpose are left unread: another step may still need them (the invitation link is read
 * after the code in some flows).
 *
 * A [MailboxException] is retried at the next poll. When the time is up, one last look is taken, bounded by
 * [lastLookTimeout] rather than by the wait: a poll the deadline cut short, or one that queued behind other testers'
 * polls of a shared inbox, must not turn a message that did arrive in time into `mail_timeout`, a finding about the
 * target. Only that last look decides: a usable message is returned, an inbox that fails it (or does not answer it in
 * time) throws [MailboxException], "the inbox could not be read", an environment problem, and only an inbox that
 * answered "nothing usable" ends in [MailTimeoutException]. Only `delay`/`withTimeoutOrNull` measure time, so virtual
 * time works. [awaitLink] polls the same way, with the extractor's pattern rule deciding which message is usable.
 */
class DefaultAwaitVerificationUseCase(
    private val mailbox: Mailbox,
    private val extractor: VerificationExtractor,
    private val candidatesPerPoll: Int = 10,
    private val lastLookTimeout: Duration = DEFAULT_LAST_LOOK_TIMEOUT,
) : AwaitVerificationUseCase {
    init {
        require(candidatesPerPoll > 0) { "candidatesPerPoll must be positive, was $candidatesPerPoll" }
        require(lastLookTimeout.isPositive() && lastLookTimeout.isFinite()) {
            "lastLookTimeout must be positive and finite, was $lastLookTimeout"
        }
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
            return withTimeoutOrNull(timeout) { pollUntilFound(pollInterval) } ?: lastLook(timeout)
        }

        /** The look once [timeout] is over; see the class comment for what each answer means. */
        private suspend fun lastLook(timeout: Duration): VerificationCode {
            val answer =
                withTimeoutOrNull(lastLookTimeout) {
                    try {
                        Result.success(pollOnce())
                    } catch (e: MailboxException) {
                        Result.failure(e)
                    }
                } ?: throw MailboxException(
                    "The inbox did not answer the last look for mail to $to within $lastLookTimeout after the $timeout wait",
                    lastFailure,
                )
            val code = answer.getOrThrow() ?: throw MailTimeoutException(to, timeout)
            logger.info { "Mail to $to found by the last look after the $timeout wait (the inbox answered slowly)" }
            return code
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
                    .filter { !it.read }
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

    companion object {
        /** How long the last look may take: a few slow answers of a busy inbox, far less than a stuck one would take. */
        val DEFAULT_LAST_LOOK_TIMEOUT: Duration = 30.seconds
    }
}
