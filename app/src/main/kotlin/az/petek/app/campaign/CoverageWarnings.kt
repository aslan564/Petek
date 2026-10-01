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

package az.petek.app.campaign

import az.petek.campaign.domain.Campaign
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.ActorResolver
import az.petek.orchestration.domain.DefaultActorResolver

/**
 * What the coverage checks of [CampaignScaler] find in a campaign, worded once for `petek run` and `petek plan`, which
 * say it before anything starts (with or without `--testers`): every step that would start with nobody to perform it,
 * wave by wave; every wait a wave leaves without the tester that emits its event; every race a wave leaves with one
 * racer. One line each, without the `Warning: ` the commands put in front; advice only, it never blocks a run.
 */
object CoverageWarnings {
    /**
     * The lines for [campaign] planned with [identities] (the registry the run will plan); [testers] is the
     * `--testers` count when the campaign was resized by it, so the lines say what made the step lose its actors.
     */
    fun of(
        campaign: Campaign,
        identities: List<Identity>,
        testers: Int? = null,
        resolver: ActorResolver = DefaultActorResolver(),
    ): List<String> {
        val size = campaign.settings.waveSize
        val context =
            listOfNotNull(testers?.let { "--testers $it" }, size?.let { "campaign.wave_size $it" })
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" and ", prefix = "with ", postfix = " ")
                .orEmpty()
        val uncovered =
            CampaignScaler.stepsWithoutActors(campaign, identities, resolver).map { gap ->
                val step = "step '${gap.step.id}' (line ${gap.step.line})"
                val matches = "${context}no tester matches '${gap.step.actors.raw}'"
                val waves = gap.waves.joinToString()
                when {
                    gap.waves.isEmpty() -> "$matches, so nobody performs $step $FAILS"
                    gap.covered -> "$matches in wave $waves, so $step is skipped there; other waves perform it."
                    else -> "$matches in wave $waves, every wave it starts in, so nobody performs $step $FAILS"
                }
            }
        val waits =
            CampaignScaler.waitsWithoutEmitter(campaign, identities, resolver).map { gap ->
                val never = if (gap.covered) "" else "; no wave holds both, so it is never checked (not_covered)"
                "with campaign.wave_size $size, step '${gap.step.id}' (line ${gap.step.line}) waits for " +
                    "'${gap.step.waitFor?.event}' in wave ${gap.waves.joinToString()}, which has no tester of step " +
                    "'${gap.emitter.id}' that emits it; its receivers there are skipped$never."
            }
        val races =
            CampaignScaler.racesSplitByWaves(campaign, identities, resolver).map { split ->
                "with campaign.wave_size $size, race step '${split.step.id}' (line ${split.step.line}) has a single racer in " +
                    "wave ${split.waves.joinToString()}, where it never passes (inconclusive): a race needs at least 2 racers in " +
                    "the same wave."
            }
        return uncovered + waits + races
    }

    /** A step nobody performs fails the run (the runner records it `uncovered`, `not_covered`). */
    private const val FAILS = "and the run fails for it (not_covered)."
}
