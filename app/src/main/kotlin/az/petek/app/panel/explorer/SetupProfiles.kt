/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel.explorer

import az.petek.campaign.domain.TargetProfile
import az.petek.scenarios.application.ScenarioCatalog
import az.petek.scenarios.domain.ScenarioLifecycle
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioValidator
import az.petek.scenarios.domain.ScenarioVersion
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** The target profile (paths, selectors, sign-up and login flows) the explorer's setup campaign uses, and where it came from. */
internal data class SetupProfile(
    val profile: TargetProfile,
    /** Shown to the owner, e.g. `kadrohr-real v1` or the contract default. */
    val origin: String,
)

/** Where the explorer's setup campaign gets its target profile. */
internal fun interface SetupProfileSource {
    suspend fun profile(): SetupProfile
}

/**
 * The profile of the site's own scenario in the panel's catalog: the runnable (approved or frozen) version approved
 * last, else the newest version the owner wrote (a `scenarios/` file), else the newest stored version of any source;
 * the contract default of docs/TARGET_CONTRACT.md when nothing is stored or the text no longer loads. Every stored
 * scenario describes the configured site (runs go only there), so no site check is needed until target profiles
 * arrive (docs/PLAN.md Faza 10). Without this the setup campaign would sign up with the contract's `data-testid`
 * flows, which the real KadroHR does not have (docs/KADROHR_READINESS.md).
 */
internal class CatalogSetupProfiles(
    private val catalog: ScenarioCatalog,
    private val validator: ScenarioValidator,
) : SetupProfileSource {
    override suspend fun profile(): SetupProfile {
        val versions = catalog.list()
        val chosen =
            ScenarioLifecycle.current(versions)
                ?: versions.filter { it.source == ScenarioSource.USER }.maxByOrNull { it.createdAt }
                ?: versions.maxByOrNull { it.createdAt }
                ?: return DEFAULT
        return profileOf(chosen)
    }

    private suspend fun profileOf(version: ScenarioVersion): SetupProfile {
        val check = validator.check(version.yaml, version.fileName)
        val campaign = check.campaign
        if (campaign == null || check.issues.isNotEmpty()) {
            logger.warn { "Scenario ${version.label} no longer loads; the explorer signs up with the contract flows: ${check.issues}" }
            return DEFAULT
        }
        return SetupProfile(campaign.target, version.label)
    }

    private companion object {
        val DEFAULT = SetupProfile(TargetProfile.DEFAULT, "docs/TARGET_CONTRACT.md default")
    }
}
