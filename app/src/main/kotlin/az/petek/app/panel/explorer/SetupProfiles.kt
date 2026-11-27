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

package az.petek.app.panel.explorer

import az.petek.app.di.AppContainer
import az.petek.app.panel.PanelTargets
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.infrastructure.YamlCampaignSource
import az.petek.scenarios.application.ScenarioCatalog
import az.petek.scenarios.domain.ScenarioLifecycle
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioValidator
import az.petek.scenarios.domain.ScenarioVersion
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/** The target profile (paths, selectors, sign-up and login flows) the explorer's setup campaign uses, and where it came from. */
internal data class SetupProfile(
    val profile: TargetProfile,
    /** Shown to the owner, e.g. `portal-real v1` or the contract default. */
    val origin: String,
)

/** Where the explorer's setup campaign gets its target profile for a site. */
internal fun interface SetupProfileSource {
    suspend fun profile(site: URI): SetupProfile
}

/**
 * The profile of [site]'s own flows: first the campaign file its target profile points at (`profile:`, ADR-0010,
 * loaded by [pointed]); else the site's own scenario in the panel's catalog, among the versions that run on that site:
 * the runnable (approved or frozen) version approved last, else the newest version the owner wrote (a `scenarios/`
 * file), else the newest stored version of any source; the contract default of docs/TARGET_CONTRACT.md when the site
 * has none. Another site's scenario is never used: its flows are that site's, and with several sites (target profiles)
 * the catalog holds scenarios of each. Versions that no longer load are left out. Without this the setup campaign would
 * sign up with the contract's `data-testid` flows, which a real site does not have (docs/TARGET_CONTRACT.md).
 */
internal class CatalogSetupProfiles(
    private val catalog: ScenarioCatalog,
    private val validator: ScenarioValidator,
    /** The configured site (`PETEK_TARGET`): where a scenario that names no site of its own runs. */
    private val configured: URI? = null,
    private val pointed: suspend (URI) -> SetupProfile? = { null },
) : SetupProfileSource {
    override suspend fun profile(site: URI): SetupProfile {
        pointed(site)?.let { return it }
        val own =
            catalog
                .list()
                .filter { version -> (WRITTEN.writtenTarget(version.yaml) ?: configured)?.let { PanelTargets.sameSite(it, site) } ?: true }
                .mapNotNull { version -> load(version)?.let { version to it } }
        val versions = own.map { it.first }
        val chosen =
            ScenarioLifecycle.current(versions)
                ?: versions.filter { it.source == ScenarioSource.USER }.maxByOrNull { it.createdAt }
                ?: versions.maxByOrNull { it.createdAt }
                ?: return DEFAULT
        return SetupProfile(own.first { it.first == chosen }.second.target, chosen.label)
    }

    private suspend fun load(version: ScenarioVersion): Campaign? {
        val check = validator.check(version.yaml, version.fileName)
        val campaign = check.campaign
        if (campaign == null || check.issues.isNotEmpty()) {
            logger.debug { "Scenario ${version.label} no longer loads; it is not a setup profile: ${check.issues}" }
            return null
        }
        return campaign
    }

    companion object {
        val DEFAULT = SetupProfile(TargetProfile.DEFAULT, "docs/TARGET_CONTRACT.md default")

        /** Reads which site a stored scenario was written for, without the `PETEK_TARGET` override of loading. */
        private val WRITTEN = YamlCampaignSource()
    }
}

/**
 * The flows of the campaign file a site's target profile points at (`profile:`, ADR-0010): a path relative to
 * [workingDirectory]; an optional `#target_profile` suffix is ignored, the file's `target_profile` is what counts.
 */
internal class PointedProfiles(
    private val container: AppContainer,
    private val workingDirectory: Path,
) {
    /** [site]'s pointed profile; null when its target profile names no file, or the file does not load (logged). */
    suspend fun of(site: URI): SetupProfile? {
        val pointer =
            container.config
                .profileFor(site)
                ?.spec
                ?.profile
                ?.substringBefore('#')
                ?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val file = workingDirectory.resolve(pointer).normalize()
            val campaign = withContext(Dispatchers.IO) { container.campaigns.execute(file, container.knownRunFunctions) }
            SetupProfile(campaign.target, pointer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "The campaign file $pointer of ${PanelTargets.site(site)}'s target profile does not load" }
            null
        }
    }
}
