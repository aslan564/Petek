package az.petek.app.panel

import az.petek.app.config.WebUrls
import az.petek.core.security.TargetPolicy
import az.petek.core.security.TargetVerdict
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.PanelRequestException
import java.net.URI
import java.net.URISyntaxException

/**
 * The owner's "Hədəf sayt" as the panel checks it before anything contacts it (CLAUDE.md rule 8): a full http(s)
 * address without credentials, in its canonical spelling ([WebUrls.canonical]), allowed by the [TargetPolicy]. Every
 * refusal is a [PanelRequestException] naming the form field, with a reason the owner can act on in Azerbaijani.
 */
internal object PanelTargets {
    /** Parses and checks [text]; throws [PanelRequestException] for [field] when it is not a usable, allowed site. */
    fun allowed(
        text: String,
        policy: TargetPolicy,
        field: String,
    ): URI {
        val target = parse(text, field)
        val verdict = policy.verify(target)
        if (verdict is TargetVerdict.Refused) throw PanelRequestException(listOf(FieldProblem(field, refusal(target, policy, verdict))))
        return target
    }

    /** The canonical URL of [text], or a [PanelRequestException] for [field]. */
    fun parse(
        text: String,
        field: String,
    ): URI {
        val uri =
            try {
                URI(text.trim())
            } catch (_: URISyntaxException) {
                null
            }
        val problem =
            when {
                uri == null || uri.scheme?.lowercase() !in WEB_SCHEMES || uri.host.isNullOrBlank() -> {
                    "Hədəf http:// və ya https:// ilə başlayan tam ünvan olmalıdır."
                }

                uri.rawUserInfo != null -> {
                    "Hədəf ünvanında istifadəçi adı və ya parol olmamalıdır."
                }

                else -> {
                    null
                }
            }
        if (problem != null || uri == null) throw PanelRequestException(listOf(FieldProblem(field, problem ?: "")))
        return WebUrls.canonical(uri)
    }

    /**
     * Scheme, host and effective port: two addresses of one site share them, whatever their paths. The target's test
     * API (and its token) belongs to one site, so this decides where the token may go.
     */
    fun site(url: URI): String {
        val scheme = url.scheme?.lowercase().orEmpty()
        val port =
            when {
                url.port != -1 -> url.port
                scheme == "https" -> HTTPS_PORT
                scheme == "http" -> HTTP_PORT
                else -> -1
            }
        return "$scheme://${url.host?.lowercase().orEmpty()}:$port"
    }

    fun sameSite(
        a: URI,
        b: URI,
    ): Boolean = site(a) == site(b)

    private fun refusal(
        target: URI,
        policy: TargetPolicy,
        verdict: TargetVerdict.Refused,
    ): String {
        val host = target.host?.lowercase().orEmpty()
        return if (policy.productionHosts.any { it.trim().lowercase() == host }) {
            "'$host' istehsal ünvanıdır (PETEK_PRODUCTION_HOSTS). Test mühitindən istifadə edin və ya onu bilərəkdən " +
                "yoxlamaq üçün .env faylında PETEK_ALLOW_PRODUCTION=true yazın."
        } else {
            "Bu hədəf qəbul edilmədi: ${verdict.reason}"
        }
    }

    private val WEB_SCHEMES = setOf("http", "https")
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443
}
