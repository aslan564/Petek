package az.petek.app.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** What one GET observed: an HTTP answer or the exact reason there was none. */
sealed interface HttpCheck {
    data class Answered(
        val status: Int,
        /** `Location` of a redirect, as sent. */
        val location: String?,
    ) : HttpCheck {
        val isRedirect: Boolean get() = status / 100 == 3

        override fun toString(): String = "HTTP $status" + (location?.let { " → $it" } ?: "")
    }

    /** No answer: [error] is the exception chain verbatim (firewall, DNS, TLS, timeout), as the user must see it. */
    data class Unreachable(
        val error: String,
    ) : HttpCheck {
        override fun toString(): String = error
    }
}

/**
 * Anonymous GET requests for `doctor` and `probe`, with the JDK's own client: HTTP/1.1, no redirects followed (a
 * redirect to `/login` is a finding worth showing) and bounded time. Headers passed in are sent as given and never
 * appear in results, so a token can be checked without being printed.
 */
class HttpProbe(
    private val timeout: Duration = 10.seconds,
) : AutoCloseable {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(minOf(timeout, 5.seconds).toJavaDuration())
            .build()

    suspend fun get(
        url: URI,
        headers: Map<String, String> = emptyMap(),
    ): HttpCheck {
        val request =
            try {
                HttpRequest
                    .newBuilder(url)
                    .timeout(timeout.toJavaDuration())
                    .GET()
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
            } catch (e: IllegalArgumentException) {
                return HttpCheck.Unreachable(describe(e))
            }
        return try {
            val response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
            HttpCheck.Answered(response.statusCode(), response.headers().firstValue("Location").orElse(null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HttpCheck.Unreachable(describe(e))
        }
    }

    override fun close() = client.close()

    companion object {
        /**
         * `SSLHandshakeException: PKIX path building failed (caused by …)`: every distinct message of the chain, since
         * the outer exception of the JDK client often has none and the useful reason sits in a cause.
         */
        fun describe(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.take(MAX_CHAIN).toList()
            val parts =
                chain
                    .map { cause -> cause::class.java.simpleName + (cause.message?.let { ": $it" } ?: "") }
                    .distinct()
            return parts.first() + parts.drop(1).joinToString("") { " (caused by $it)" }
        }

        private const val MAX_CHAIN = 5
    }
}
