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

package az.petek.campaign.domain

import java.net.URI

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

/**
 * Where the site's API lives when `target_profile.api_prefix` is the full address of an API on its own host
 * (`https://api.example.com/v1`; the owner's decision of 2026-09-30): that host's origin, `https://api.example.com`.
 * Null when the API is a path on the target (`/api/v1`, the usual case). Only `http_status` checks go there, and a run
 * that sends them must first find the host allowed by the production-host policy and proved as the owner's own (the
 * app checks both before a tester starts).
 */
val TargetProfile.apiOrigin: URI? get() = ApiAddress.originOf(apiPrefix)

/** The API origin this campaign's `http_status` checks call; null when none of them goes to an API on its own host. */
val Campaign.apiOriginInUse: URI?
    get() {
        val origin = target.apiOrigin ?: return null
        val used =
            (setup + steps).any { step ->
                step.assertions.any {
                    it is AssertionSpec.HttpStatus &&
                        ApiAddress.onOrigin(it.path, origin)
                }
            }
        return origin.takeIf { used }
    }

/** A full address of an API on its own host, as `api_prefix` may give it. */
object ApiAddress {
    /** `http(s)://host[:port][/segment…]`: no credentials, no trailing '/', no query or fragment. */
    private val FULL =
        Regex("""https?://[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::\d{1,5})?(?:/[A-Za-z0-9._~\-]+)*""", RegexOption.IGNORE_CASE)

    /** The origin of [prefix] (scheme and host in lower case, the port as written); null when it is not a full address. */
    fun originOf(prefix: String): URI? {
        if (!FULL.matches(prefix)) return null
        val uri = URI(prefix)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return URI("${uri.scheme.lowercase()}://${uri.host.lowercase()}$port")
    }

    /** Whether [path] is an address on [origin] (a `/` right after it), however the scheme and host are cased. */
    fun onOrigin(
        path: String,
        origin: URI,
    ): Boolean {
        val prefix = origin.toString()
        return path.length > prefix.length && path.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true) &&
            path[prefix.length] == '/'
    }
}
