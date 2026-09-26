/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.cli

import az.petek.app.campaign.IdentitySpecs
import az.petek.campaign.domain.Campaign
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTags
import az.petek.identity.domain.Identity
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `petek plan <campaign.yaml>`: validates the campaign and shows who would test (docs/PLAN.md Faza 1). The identities
 * are generated for a plan id and tag derived from the campaign's hash and seed, and stored with `replaceAll`, so
 * planning the same file again prints and stores exactly the same registry. Nothing touches the target.
 */
class PlanCommand : PetekSubcommand("plan") {
    private val file by argument("campaign", help = "campaign YAML file, e.g. scenarios/kadrohr.yaml").path()

    override fun help(context: Context): String = "Validate a campaign and print its identity registry (nothing is executed)."

    override suspend fun execute(): Int =
        withContainer { container ->
            val path = session.resolve(file)
            val campaign = withContext(Dispatchers.IO) { container.campaigns.execute(path, container.knownRunFunctions) }
            val runId = planRunId(campaign)
            val tag = RunTags.forPlan(campaign.sourceHash, campaign.settings.seed)
            val plan =
                container.planIdentities.execute(
                    runId,
                    tag,
                    IdentitySpecs.of(campaign.settings, container.config.mailDomain, container.config.mailInbox),
                )
            if (json) {
                emitJson(
                    buildJsonObject {
                        put("campaign", campaign.settings.name)
                        put("seed", campaign.settings.seed)
                        put("planId", runId.value)
                        put("tag", plan.runTag.value)
                        putJsonArray("identities") {
                            plan.identities.forEach { identity ->
                                addJsonObject {
                                    put("agentId", identity.agentId.value)
                                    put("name", identity.displayName)
                                    put("email", identity.email)
                                    put("role", identity.role.key)
                                    put("department", identity.department)
                                    put("registration", identity.registration.key)
                                    put("phone", identity.phone)
                                }
                            }
                        }
                    },
                )
                return@withContainer ExitCodes.OK
            }
            echo(
                "Campaign '${campaign.settings.name}' (seed ${campaign.settings.seed}) against ${container.config.targetLabel}: " +
                    "${plan.identities.size} identities, plan $runId, tag ${plan.runTag}.",
            )
            echo(identityTable(plan.identities))
            echo("Stored in ${container.config.dbPath}. Nothing was executed against the target.")
            ExitCodes.OK
        }

    private fun identityTable(identities: List<Identity>): String =
        TextTable.render(
            listOf("Agent", "Name", "E-mail", "Role", "Department", "Registration", "Phone"),
            identities.map {
                listOf(it.agentId.value, it.displayName, it.email, it.role.key, it.department ?: "-", it.registration.key, it.phone)
            },
        )

    companion object {
        /**
         * The run id under which `plan` stores a campaign's registry: the same file and seed always map to the same id
         * (so repeated planning replaces instead of adding), different files or seeds never share one.
         */
        fun planRunId(campaign: Campaign): RunId = RunId("plan_${campaign.sourceHash.take(HASH_PREFIX)}_${campaign.settings.seed}")

        private const val HASH_PREFIX = 16
    }
}
