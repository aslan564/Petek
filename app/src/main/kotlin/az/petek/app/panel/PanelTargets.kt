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

package az.petek.app.panel

import az.petek.app.config.PetekConfig
import az.petek.app.config.WebUrls
import az.petek.app.diagnostics.TargetAnswer
import az.petek.app.diagnostics.TargetReachability
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.VisitorRun
import az.petek.core.security.TargetPolicy
import az.petek.core.security.TargetVerdict
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.PanelRequestException
import az.petek.ownership.application.SiteOwnership
import az.petek.ownership.domain.OwnershipStatus
import java.net.URI
import java.net.URISyntaxException

/**
 * The owner's "Hədəf sayt" as the panel checks it before anything contacts it (AGENTS.md rule 8): a full http(s)
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

    /**
     * Looks at [target] with [reachability] before anything is tested (rule 12): a site that does not answer is a
     * [PanelRequestException] for [field] naming the reason, never a run against something else.
     */
    suspend fun reachable(
        target: URI,
        reachability: TargetReachability,
        field: String,
    ) {
        val answer = reachability.check(target)
        if (answer is TargetAnswer.Unreachable) {
            throw PanelRequestException(
                listOf(
                    FieldProblem(
                        field,
                        "Verilən sayt cavab vermir, ona görə heç nə test edilmədi: ${PetekConfig.masked(target)} (${answer.reason}). " +
                            "Ünvanı, şəbəkəni və saytın işlədiyini yoxlayın, sonra yenidən cəhd edin.",
                    ),
                ),
            )
        }
    }

    /** A campaign cleared to start ([runnable]), and whether its site's owner proved it is theirs or it is local. */
    data class Cleared(
        val campaign: Campaign,
        /** Only then may its testers go out through the owner's proxies (Faza 21). */
        val ownSite: Boolean,
    )

    /**
     * The run of [campaign] as it may start: on a site whose owner proved it is theirs (ADR-0012) or a local one any
     * campaign, on any other site a visitor run ([VisitorRun]: visitors that only read, as many as the owner chose).
     * Any other campaign is refused with a [PanelRequestException] for [field], in Azerbaijani, with the proof to
     * publish and why the campaign is not a visitor run. Nothing is written before.
     */
    suspend fun runnable(
        campaign: Campaign,
        ownership: SiteOwnership,
        field: String,
    ): Cleared {
        val status = ownership.check(campaign.settings.target)
        if (status !is OwnershipStatus.Unverified) return Cleared(campaign, ownSite = true)
        val problems = VisitorRun.findProblems(campaign)
        if (problems.isEmpty()) return Cleared(campaign, ownSite = false)
        throw PanelRequestException(
            listOf(
                FieldProblem(
                    field,
                    "Pətək sayta yalnız sahibliyi təsdiqləndikdən sonra yazır, ona görə ${status.host} üzərində heç nə test " +
                        "edilmədi. Təsdiqsiz saytda yalnız ziyarətçi run başlaya bilər: hamısı qonaq olan istənilən sayda tester, " +
                        "yalnız oxuyan `run` addımları, `do` addımı yox. Bu kampaniya belə deyil: " +
                        "${problems.joinToString("; ", transform = ::inAzerbaijani)}. " +
                        "${proofHowTo(status)} Sonra yenidən başladın.",
                ),
            ),
        )
    }

    /** Why a campaign is not a visitor run, said to the owner. */
    private fun inAzerbaijani(problem: VisitorRun.Problem): String =
        when (problem) {
            VisitorRun.Problem.Companies -> {
                "şirkətlidir (tenant: company)"
            }

            is VisitorRun.Problem.NotAllVisitors -> {
                "testerlərin hamısı qonaq deyil (registration: {guest: ${problem.testers}} olmalıdır)"
            }

            is VisitorRun.Problem.AiStep -> {
                "'${problem.step}' addımı `do` addımıdır, orada AI agent klikləyə və yaza bilər"
            }

            is VisitorRun.Problem.WritingFunction -> {
                "'${problem.step}' addımı `${problem.function}` işlədir, o yalnız oxumur"
            }

            is VisitorRun.Problem.WritingCheck -> {
                "'${problem.step}' addımı `${problem.check}` yoxlayır, o yazır və ya saytın test API-sini istəyir"
            }

            is VisitorRun.Problem.CreatedObject -> {
                "'${problem.step}' addımı yaradılmış obyekti saytın test API-si ilə oxuyur"
            }
        }

    /** Why an exploration of an unproved site only reads, and how to change that; shown under "Sessiyalar". */
    fun readOnlyExploration(status: OwnershipStatus.Unverified): String =
        "Sahiblik təsdiqlənmədiyi üçün kəşfiyyat yalnız anonim oxuyur: rollarla gəzinti və sınaq toxunuşu buraxıldı. " +
            proofHowTo(status)

    /** The proof to publish, in Azerbaijani: the file, the DNS record, and where Pətək looked. */
    fun proofHowTo(status: OwnershipStatus.Unverified): String {
        val challenge = status.challenge
        val dns = challenge.dnsName?.let { " Və ya DNS-ə TXT qeydi əlavə edin: $it, dəyəri ${challenge.proofLine}." }.orEmpty()
        val looked =
            status.looked
                .takeIf { it.isNotEmpty() }
                ?.joinToString("; ", prefix = " Yoxlanıldı: ", postfix = ".")
                .orEmpty()
        return "Təsdiq üçün saytda ${challenge.fileUrl} faylını yerləşdirin, içində bu sətir olsun: ${challenge.proofLine}." +
            dns + looked + " Localhost və daxili şəbəkə ünvanları təsdiq tələb etmir."
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
