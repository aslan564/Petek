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

package az.petek.dashboard.domain

/**
 * The setup screen ("Quraşdırma"): whether what a test needs is in place for the panel's site, one part at a time,
 * so the owner can put it right in the page instead of in `.env`. [readiness] only reads what is configured; each
 * check that contacts something (the site, its ownership proof, the AI) runs when the page asks for it.
 */
interface PanelReadiness {
    /** The site, where the configuration lives and which AI is configured; contacts nothing. */
    suspend fun readiness(): ReadinessView = throw PanelUnavailableException(UNAVAILABLE)

    /** Asks the site now whether it answers (the same look a run takes before its first tester starts). */
    suspend fun checkSite(): SiteCheckView = throw PanelUnavailableException(UNAVAILABLE)

    /**
     * The site's ownership (ADR-0012): a proof remembered within its period, else a look for it; with [fresh], a look
     * now whatever is remembered.
     */
    suspend fun checkOwnership(fresh: Boolean): OwnershipView = throw PanelUnavailableException(UNAVAILABLE)

    /** Sends the configured AI one tiny structured request and says how it went. */
    suspend fun testAi(): AiCheckView = throw PanelUnavailableException(UNAVAILABLE)

    /** The AIs the owner may choose on this screen and what each needs; contacts nothing. */
    suspend fun aiOptions(): AiOptionsView = throw PanelUnavailableException(UNAVAILABLE)

    /**
     * Uses [choice] from the next exploration, test or run on (Faza 23), without restarting the panel, and writes it to
     * the configuration file so the next start uses it too; a key goes to that file only, never to the database or a
     * view. Fails with [PanelRequestException] naming the field, or [PanelConflictException] while a test, an
     * exploration or a run is going.
     */
    suspend fun chooseAi(choice: AiChoice): ReadinessView = throw PanelUnavailableException(UNAVAILABLE)

    private companion object {
        const val UNAVAILABLE = "Quraşdırma yoxlaması bu paneldə mümkün deyil."
    }
}

data class ReadinessView(
    /** The site under test, as the panel shows it (credentials masked). */
    val target: String,
    /** The configuration file the settings come from, when there is one (so the owner can find it). */
    val configuration: String?,
    val ai: AiView,
)

/** The AI the configuration chose, and why ([reason]: e.g. which command-line tool `auto` found). */
data class AiView(
    val provider: String,
    val model: String,
    val reason: String,
    /** The providers tried next when this one fails. */
    val fallbacks: List<String>,
    /** False when no AI is configured: code checks still run, `do` steps and the AI's help do not. */
    val configured: Boolean,
)

/** One AI the owner may choose; [needs] what it takes besides its name: `model`, `endpoint`, `key`. */
data class AiOptionView(
    val provider: String,
    /** What it is, in the owner's words. */
    val label: String,
    /** Whether it can be used on this computer now: its program is on PATH, or it is reached over the network. */
    val available: Boolean,
    val needs: List<String>,
)

data class AiOptionsView(
    val options: List<AiOptionView>,
    /** The provider as the configuration file names it (`auto` included); what it resolved to is in [ReadinessView.ai]. */
    val chosen: String,
    val model: String?,
    val endpoint: String?,
    /** Whether a key is configured; the key itself never leaves the configuration file. */
    val keySet: Boolean,
    /** Why the AI cannot be chosen here (no configuration file to keep it in), or null when it can. */
    val unavailable: String?,
)

/** What the owner chose; a null or blank [key] keeps the configured one. */
data class AiChoice(
    val provider: String,
    val model: String? = null,
    val endpoint: String? = null,
    val key: String? = null,
) {
    override fun toString(): String =
        "AiChoice($provider, model=$model, endpoint=$endpoint, key=${if (key.isNullOrBlank()) "kept" else "***"})"
}

data class SiteCheckView(
    val reachable: Boolean,
    /** What the site answered, or why it did not. */
    val detail: String,
)

/**
 * [state] is `EXEMPT` (a local address, no proof needed), `VERIFIED` (the proof was found, by [method]) or
 * `UNVERIFIED` (publish [proofLine] at [fileUrl] or as the DNS TXT record [dnsName]; [looked] says where it was looked for).
 */
data class OwnershipView(
    val state: String,
    val host: String,
    val method: String?,
    val fileUrl: String?,
    val proofLine: String?,
    val dnsName: String?,
    val looked: List<String>,
)

data class AiCheckView(
    val ok: Boolean,
    val provider: String,
    /** The model that answered, when it did. */
    val model: String?,
    /** The provider's own error when [ok] is false. */
    val detail: String,
    val millis: Long,
)
