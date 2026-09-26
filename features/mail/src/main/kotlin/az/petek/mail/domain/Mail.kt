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

    /**
     * The first http(s) link of [message] containing a match of [pattern] (a site's own link shape, e.g.
     * `set-password\?token=`), with the message's code if it has one; null when no link matches. The default only
     * checks the link [extract] picks for [MailPurpose.LINK]; extractors that see every link override it.
     */
    fun extractLink(
        message: MailMessage,
        pattern: Regex,
    ): VerificationCode? {
        val found = extract(message, MailPurpose.LINK) ?: return null
        val link = found.link?.toString() ?: return null
        return found.takeIf { pattern.containsMatchIn(link) }
    }
}

class MailTimeoutException(
    val to: String,
    val timeout: Duration,
) : PetekException("No verification e-mail for $to within $timeout (mail_timeout)")
