package az.petek.agent.testing

import az.petek.mail.application.AwaitVerificationUseCase
import az.petek.mail.domain.MailPurpose
import az.petek.mail.domain.MailTimeoutException
import az.petek.mail.domain.MailboxException
import az.petek.mail.domain.VerificationCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Inbox-like [AwaitVerificationUseCase]: tests (or the simulated site) "send" codes and links; like the real use case
 * (`Mailbox.findLatest`), each await consumes the newest unread one for that address, polling in virtual time and
 * throwing [MailTimeoutException] when nothing arrives. Older unread messages stay unread. With an [outage] the inbox
 * cannot be read at all: like the real use case, an await then keeps trying for its whole timeout and throws the
 * [MailboxException].
 */
class FakeVerification : AwaitVerificationUseCase {
    private data class Mail(
        val to: String,
        val code: VerificationCode,
    )

    private val inbox = mutableListOf<Mail>()
    private val ids = AtomicInteger()

    /** Every await call: address and purpose. */
    val calls = CopyOnWriteArrayList<Pair<String, MailPurpose>>()

    /** Set to make the inbox unreachable. */
    @Volatile
    var outage: MailboxException? = null

    fun sendCode(
        to: String,
        code: String,
    ) = deliver(to, VerificationCode(code, null, "msg-${ids.incrementAndGet()}"))

    fun sendLink(
        to: String,
        link: String,
    ) = deliver(to, VerificationCode(null, URI(link), "msg-${ids.incrementAndGet()}"))

    private fun deliver(
        to: String,
        code: VerificationCode,
    ) {
        synchronized(inbox) { inbox += Mail(to.lowercase(), code) }
    }

    override suspend fun await(
        to: String,
        since: Instant,
        purpose: MailPurpose,
        timeout: Duration,
        pollInterval: Duration,
    ): VerificationCode {
        calls += to to purpose
        outage?.let { failure ->
            delay(timeout)
            throw failure
        }
        return withTimeoutOrNull(timeout) { next(to.lowercase(), purpose, pollInterval) } ?: throw MailTimeoutException(to, timeout)
    }

    private suspend fun next(
        to: String,
        purpose: MailPurpose,
        pollInterval: Duration,
    ): VerificationCode {
        while (true) {
            take(to, purpose)?.let { return it }
            delay(pollInterval)
        }
    }

    private fun take(
        to: String,
        purpose: MailPurpose,
    ): VerificationCode? =
        synchronized(inbox) {
            val mail =
                inbox.lastOrNull { mail ->
                    mail.to == to &&
                        when (purpose) {
                            MailPurpose.CODE -> mail.code.code != null
                            MailPurpose.LINK -> mail.code.link != null
                            MailPurpose.ANY -> true
                        }
                }
            mail?.also { inbox.remove(it) }?.code
        }
}
