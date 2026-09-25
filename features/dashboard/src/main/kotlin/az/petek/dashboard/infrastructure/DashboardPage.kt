package az.petek.dashboard.infrastructure

import java.security.SecureRandom
import java.util.Base64

/**
 * The single self-contained page (inline CSS and vanilla JavaScript, no CDN), read once from the classpath. Its inline
 * `<style>` and `<script>` carry a fresh nonce per response, and the Content-Security-Policy allows nothing else: no
 * other script, no inline handlers, no foreign origin. The page itself escapes every value it renders (it only ever
 * writes data with `textContent`).
 */
internal class DashboardPage {
    private val template: String =
        checkNotNull(DashboardPage::class.java.getResourceAsStream(RESOURCE)) { "dashboard page $RESOURCE is missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val random = SecureRandom()

    init {
        check(NONCE_MARK in template) { "dashboard page $RESOURCE has no $NONCE_MARK placeholder" }
    }

    /** A page and the policy that goes with it. */
    class Rendered(
        val html: String,
        val contentSecurityPolicy: String,
    )

    fun render(): Rendered {
        val nonce = nonce()
        return Rendered(
            html = template.replace(NONCE_MARK, nonce),
            contentSecurityPolicy =
                "default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; img-src 'self' data:; " +
                    "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        )
    }

    private fun nonce(): String {
        val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private companion object {
        const val RESOURCE = "/az/petek/dashboard/infrastructure/dashboard.html"
        const val NONCE_MARK = "{{NONCE}}"
        const val NONCE_BYTES = 18
    }
}
