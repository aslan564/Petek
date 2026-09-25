/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.application

import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.SiteModelDiff
import az.petek.explorer.domain.SiteModelVersions
import java.net.URI

/** What changed on a site between explorations (docs/PLAN.md Faza 7 "fərq kəşfiyyatı"). */
class CompareExplorationsUseCase(
    private val models: SiteModelVersions,
) {
    /**
     * The diff between the two newest complete model versions of [target] (partial models of explorations that timed
     * out, were cancelled, failed or stopped at the page budget are skipped: a page they did not reach is not a
     * removed page); null while fewer than two complete versions exist.
     */
    suspend fun latest(target: URI): SiteModelDiff? {
        val complete = mutableListOf<SiteModel>()
        for (version in models.versions(target).asReversed()) {
            val model = models.model(target, version) ?: continue
            if (!model.partial) complete += model
            if (complete.size == 2) return SiteModelDiff.between(complete[1], complete[0])
        }
        return null
    }

    /** The diff from version [from] to version [to] of [target]; a missing version is an [IllegalArgumentException]. */
    suspend fun between(
        target: URI,
        from: Int,
        to: Int,
    ): SiteModelDiff {
        val before = requireNotNull(models.model(target, from)) { "No site model v$from for $target" }
        val after = requireNotNull(models.model(target, to)) { "No site model v$to for $target" }
        return SiteModelDiff.between(before, after)
    }
}
