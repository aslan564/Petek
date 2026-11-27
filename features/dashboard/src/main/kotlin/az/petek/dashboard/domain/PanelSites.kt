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
 * The sites the panel knows ("Saytlar", Faza 23): its own site and those of the target profiles (`targets/<name>.yaml`),
 * each with its own settings. A site added here gets its profile, and its test token goes to the configuration file,
 * never to the database or a view; the panel knows it at once, without a restart.
 */
interface PanelSites {
    /** The panel's own site first, then the others by name; contacts nothing. */
    suspend fun sites(): List<SiteView> = emptyList()

    /**
     * Writes [request] as a new target profile and takes it into the running panel. Fails with [PanelRequestException]
     * naming the field (an address that is not a site, a production host, a name or site already known), or with
     * [PanelUnavailableException] where the panel cannot read its configuration again.
     */
    suspend fun addSite(request: SiteRequest): List<SiteView> = throw PanelUnavailableException("Sayt əlavə etmək bu paneldə mümkün deyil.")
}

/** A site as the panel shows it; never its token. */
data class SiteView(
    val name: String,
    /** Its address, credentials masked. */
    val url: String,
    /** The panel's own site (`PETEK_TARGET`), whose settings are the configuration file's. */
    val own: Boolean,
    /** Its profile file, when it has one. */
    val profile: String?,
    /** Whether its test API may be used (a token of its own): oracle checks and test mail. */
    val testApi: Boolean,
    /** Where its testers' verification mail is read from (`mailpit`, `test-api`, `imap`, `manual`). */
    val mail: String,
    /** How many accounts the owner gave for it. */
    val accounts: Int,
)

/**
 * A site to add: a blank [name] is made from the host; a null [mail] keeps the panel's mail setting; [apiUrl] is the
 * test API when it is not on the site's own origin; [token] the site's own test token, written to the configuration
 * file only.
 */
data class SiteRequest(
    val name: String?,
    val url: String,
    val mail: String? = null,
    val apiUrl: String? = null,
    val token: String? = null,
) {
    override fun toString(): String =
        "SiteRequest($name, $url, mail=$mail, apiUrl=$apiUrl, token=${if (token.isNullOrBlank()) "none" else "***"})"
}
