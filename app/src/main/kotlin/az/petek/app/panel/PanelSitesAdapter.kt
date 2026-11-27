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

import az.petek.app.config.ConfigException
import az.petek.app.config.EnvFileWriter
import az.petek.app.config.MailSource
import az.petek.app.config.PetekConfig
import az.petek.app.config.ResolvedTarget
import az.petek.app.config.TargetProfileConfig
import az.petek.app.di.AppContainer
import az.petek.campaign.domain.TargetSpec
import az.petek.campaign.domain.TargetSpecException
import az.petek.campaign.infrastructure.YamlTargetSpecSource
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PanelSites
import az.petek.dashboard.domain.PanelUnavailableException
import az.petek.dashboard.domain.SiteRequest
import az.petek.dashboard.domain.SiteView
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

/**
 * The sites the panel knows ("Saytlar", Faza 23): the panel's own site (`PETEK_TARGET`, its settings the configuration
 * file's) and every target profile, each shown with the settings a run there takes ([TargetProfileConfig.forTarget]).
 *
 * Adding a site writes a small profile `targets/<name>.yaml` (its address, and only what the owner gave: the mail
 * source, the test API's address, the test token as a `${VARIABLE}` reference) and the token itself to [envFile], reads
 * the configuration again ([reload]) and takes its profiles into the running container ([AppContainer.refreshTargets]);
 * nothing else of the panel changes. A profile that does not load, or a configuration that no longer does, puts both
 * files back and adds nothing. The site's ownership, and whether it answers, are looked at when it is tested, as for
 * any site.
 */
internal class PanelSitesAdapter(
    private val container: AppContainer,
    private val envFile: Path,
    private val targetsDir: Path,
    /** Reads the configuration again from its file and the environment; null where sites cannot be added (`petek mcp`). */
    private val reload: (() -> PetekConfig)?,
) : PanelSites {
    private val adding = Mutex()

    override suspend fun sites(): List<SiteView> = withContext(Dispatchers.IO) { views(container.config) }

    private fun views(config: PetekConfig): List<SiteView> {
        val ownProfile = config.profileFor(config.target)
        val others =
            config.targets
                .filterNot { PanelTargets.sameSite(it.spec.url, config.target) }
                .sortedBy { it.spec.name }
                .map { view(TargetProfileConfig.apply(config, it), it, own = false) }
        return listOf(view(config, ownProfile, own = true)) + others
    }

    private fun view(
        settings: PetekConfig,
        profile: ResolvedTarget?,
        own: Boolean,
    ): SiteView =
        SiteView(
            name = profile?.spec?.name ?: TargetProfileFiles.nameFor(settings.target),
            url = PetekConfig.masked(settings.target),
            own = own,
            profile =
                profile?.let { TargetProfileFiles.named(targetsDir, it.spec.name) }?.let {
                    targetsDir.fileName?.resolve(it.fileName)?.toString() ?: it.fileName.toString()
                },
            testApi = settings.oracle && settings.testToken != null,
            mail = settings.mailSource.key,
            accounts = profile?.spec?.accounts?.size ?: 0,
        )

    override suspend fun addSite(request: SiteRequest): List<SiteView> {
        val load = reload ?: throw PanelUnavailableException("Saytı bu paneldə əlavə etmək olmur; targets/<ad>.yaml faylını əl ilə yazın.")
        val policy = container.config.targetPolicy
        val url = PanelTargets.allowed(request.url, policy, URL)
        val apiUrl =
            request.apiUrl
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { PanelTargets.allowed(it, policy, API_URL) }
        val name =
            request.name
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() } ?: TargetProfileFiles.nameFor(url)
        val mail =
            request.mail
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
        val token = request.token?.takeIf { it.isNotBlank() }
        return adding.withLock {
            val config = container.config
            problems(config, url, name, mail, token).takeIf { it.isNotEmpty() }?.let { throw PanelRequestException(it) }
            val fresh = withContext(Dispatchers.IO) { write(load, NewSite(name, url, mail, apiUrl, token)) }
            container.refreshTargets(fresh)
            logger.info { "A site was added in the panel: $name (${PetekConfig.masked(url)})" }
            withContext(Dispatchers.IO) { views(container.config) }
        }
    }

    private fun problems(
        config: PetekConfig,
        url: URI,
        name: String,
        mail: String?,
        token: String?,
    ): List<FieldProblem> =
        buildList {
            if (PanelTargets.sameSite(url, config.target)) {
                add(FieldProblem(URL, "Bu, panelin öz saytıdır; onun ayarları konfiqurasiya faylındadır."))
            } else {
                config.profileFor(url)?.let { add(FieldProblem(URL, "Bu sayt artıq siyahıdadır: ${it.spec.name}.")) }
            }
            if (!TargetSpec.NAME.matches(name)) {
                add(FieldProblem(NAME, "Ad kiçik hərf, rəqəm və '-' ilə yazılır, 1–63 simvol (məs. notes-staging)."))
            } else if (config.targets.any { it.spec.name == name } || Files.exists(targetsDir.resolve("$name.yaml"))) {
                add(FieldProblem(NAME, "Bu adda sayt artıq var: $name."))
            }
            val source = mail?.let(MailSource::fromKey)
            if (mail != null && source == null) {
                add(FieldProblem(MAIL, "Poçt mənbəyi ${MailSource.entries.joinToString { it.key }} ola bilər."))
            }
            if (source == MailSource.TEST_API && token == null) add(FieldProblem(TOKEN, "test-api poçtu saytın öz test tokeni ilə oxunur."))
            if (source == MailSource.IMAP && config.imap == null) {
                add(FieldProblem(MAIL, "IMAP qutusu konfiqurasiya faylında qurulmayıb (PETEK_IMAP_*)."))
            }
            if (token != null && (token.length > MAX_TOKEN || token.any(Char::isISOControl))) {
                add(FieldProblem(TOKEN, "Token 1–$MAX_TOKEN simvol olmalı, sətir keçidi olmamalıdır."))
            }
        }

    private class NewSite(
        val name: String,
        val url: URI,
        val mail: String?,
        val apiUrl: URI?,
        val token: String?,
    ) {
        /** Where its token lives in the configuration file. */
        val variable: String = "PETEK_SITE_" + name.uppercase().replace('-', '_') + "_TOKEN"
    }

    /** Writes the profile (and the token), reads the configuration again; puts both files back on any failure. */
    private fun write(
        load: () -> PetekConfig,
        site: NewSite,
    ): PetekConfig {
        Files.createDirectories(targetsDir)
        val file = targetsDir.resolve("${site.name}.yaml")
        val envBefore = if (Files.exists(envFile)) Files.readAllBytes(envFile) else null
        var written = false
        try {
            Files.writeString(file, profile(site), StandardOpenOption.CREATE_NEW)
            written = true
            site.token?.let { EnvFileWriter.set(envFile, site.variable, it) }
            YamlTargetSpecSource().load(file)
            val fresh = load()
            check(fresh.profileFor(site.url)?.spec?.name == site.name) { "the configuration did not take targets/${site.name}.yaml" }
            return fresh
        } catch (e: Exception) {
            if (written) Files.deleteIfExists(file)
            if (site.token != null) {
                if (envBefore == null) Files.deleteIfExists(envFile) else Files.write(envFile, envBefore)
            }
            throw refusal(e, site.name)
        }
    }

    /** The profile's text: only what the owner gave; the token as a reference to the configuration file (rule 10). */
    private fun profile(site: NewSite): String =
        buildString {
            appendLine("# Written by the Pətək panel (\"Saytlar\"); its secrets stay in the configuration file.")
            appendLine("target:")
            appendLine("  name: ${site.name}")
            appendLine("  url: ${quoted(site.url)}")
            site.apiUrl?.let { appendLine("  api_url: ${quoted(it)}") }
            site.mail?.let { appendLine("  mail: {source: $it}") }
            if (site.token != null) appendLine("  test_api: {token: '\${${site.variable}}'}")
        }

    /** [url] as a single-quoted YAML scalar, whatever it holds. */
    private fun quoted(url: URI): String = "'" + url.toString().replace("'", "''") + "'"

    /**
     * Takes the target profiles as their files now hold them (after "Hesablar" wrote one); a configuration that no
     * longer loads is left for the next start to report, and the panel keeps the profiles it had.
     */
    fun takeProfiles() {
        val load = reload ?: return
        try {
            container.refreshTargets(load())
        } catch (e: ConfigException) {
            logger.warn { "The target profiles were not read again: ${e.message}" }
        }
    }

    private fun refusal(
        e: Exception,
        name: String,
    ): PanelRequestException =
        when (e) {
            is PanelRequestException -> {
                e
            }

            is java.nio.file.FileAlreadyExistsException -> {
                PanelRequestException(listOf(FieldProblem(NAME, "targets/$name.yaml artıq var.")))
            }

            is TargetSpecException -> {
                PanelRequestException(listOf(FieldProblem(URL, "Profil yüklənmir: ${e.issues.joinToString("; ")} Heç nə əlavə edilmədi.")))
            }

            is ConfigException -> {
                PanelRequestException(listOf(FieldProblem(URL, "Konfiqurasiya bu saytla yüklənmir: ${e.message} Heç nə əlavə edilmədi.")))
            }

            else -> {
                logger.warn(e) { "Adding the site $name failed" }
                PanelRequestException(listOf(FieldProblem(URL, "Sayt əlavə edilmədi: ${e::class.simpleName}. Heç nə dəyişmədi.")))
            }
        }

    private companion object {
        const val NAME = "name"
        const val URL = "url"
        const val API_URL = "apiUrl"
        const val MAIL = "mail"
        const val TOKEN = "token"
        const val MAX_TOKEN = 512
    }
}
