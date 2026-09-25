package az.petek.mail.application

import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.VerificationCode
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Polls the mailbox for the verification message of one tester (every [pollInterval], at most [timeout]),
 * extracts the code/link, marks the message read. Throws MailTimeoutException when nothing usable arrives.
 */
interface AwaitVerificationUseCase {
    suspend fun await(
        to: String,
        since: Instant,
        purpose: MailPurpose,
        timeout: Duration = 60.seconds,
        pollInterval: Duration = 1.seconds,
    ): VerificationCode
}
