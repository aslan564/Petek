/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.domain

/**
 * The campaign with `{api}` ([Placeholder.API_PREFIX]) replaced by [TargetProfile.apiPrefix] wherever a path is sent to
 * the target: `http_status` and `oracle` paths, id sources read from the test API, `run` arguments and flow `goto`s.
 * The prefix is static per campaign, so it is expanded once when the file is loaded and the validator then checks the
 * real paths (`{api}/leave-requests/{last_id}/approve` becomes `/api/v1/leave-requests/{last_id}/approve`).
 */
fun Campaign.expandApiPrefix(): Campaign {
    val prefix = target.apiPrefix

    fun String.expanded(): String = replace(Placeholder.API_PREFIX, prefix)

    fun IdSource.expanded(): IdSource = if (this is IdSource.OracleField) copy(path = path.expanded()) else this

    fun AssertionSpec.expanded(): AssertionSpec =
        when (this) {
            is AssertionSpec.HttpStatus -> copy(path = path.expanded())
            is AssertionSpec.Oracle -> copy(path = path.expanded())
            else -> this
        }

    fun StepAction.expanded(): StepAction = if (this is StepAction.Run) copy(args = args.mapValues { it.value.expanded() }) else this

    fun ScenarioStep.expanded(): ScenarioStep =
        copy(
            action = action.expanded(),
            emits = emits?.let { it.copy(idSource = it.idSource?.expanded()) },
            assertions = assertions.map { it.expanded() },
        )

    return copy(
        target =
            target.copy(
                idSources = target.idSources.mapValues { it.value.expanded() },
                flows = target.flows.mapValues { (_, flow) -> Flow(flow.steps.map { it.withPaths(String::expanded) }) },
            ),
        setup = setup.map { it.expanded() },
        steps = steps.map { it.expanded() },
    )
}

/** This step with every `goto` path (nested ones included) passed through [transform]. */
private fun FlowStep.withPaths(transform: (String) -> String): FlowStep =
    when (this) {
        is FlowStep.Goto -> copy(path = transform(path))
        is FlowStep.IfVisible -> copy(then = then.map { it.withPaths(transform) })
        is FlowStep.Journey -> copy(pages = pages.map { page -> page.copy(steps = page.steps.map { it.withPaths(transform) }) })
        else -> this
    }
