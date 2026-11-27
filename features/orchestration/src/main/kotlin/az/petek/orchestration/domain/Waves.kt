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

package az.petek.orchestration.domain

import az.petek.campaign.domain.ActorExpression
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Campaign
import az.petek.identity.domain.Identity

/**
 * Who is live in which wave of a run (`campaign.wave_size`, Faza 21 and 24.11). [residents] hold a role nobody else
 * has (the company's owner, a single manager): the steps of every wave need them, so they are live in every wave and
 * their setup steps run in the first one only. The other testers are dealt into [waves] of at most `wave_size` each.
 */
data class WavePlan(
    val residents: List<Identity>,
    val waves: List<List<Identity>>,
) {
    /** Everyone live in wave [index] (0-based): the residents and the wave's own testers, in agent order. */
    fun live(index: Int): List<Identity> = (residents + waves[index]).sortedBy { it.agentId }

    /** The most testers live at once, the residents included: what the browsers and proxies must carry. */
    val maxLive: Int get() = residents.size + (waves.maxOfOrNull { it.size } ?: 0)
}

/**
 * How a run cuts its testers into waves. The runner and the checks that warn before a run both use it, so they always
 * agree on who is in which wave. Deterministic: the same testers, size and races give the same waves.
 *
 * - A role with a single tester makes that tester a resident ([WavePlan.residents]).
 * - The racers of one race step (`only_one_succeeds`) share a wave, so the race has its racers; a race with more
 *   racers than a wave holds is split (the checks before the run say so, and such a wave's race never passes: inconclusive).
 * - Every role is dealt into the waves in turn, so each wave has its share of managers and employees instead of one
 *   wave of managers only.
 */
object Waves {
    /** The waves of [campaign] (its `wave_size` and races) for [identities]; see the other [plan]. */
    fun plan(
        campaign: Campaign,
        identities: List<Identity>,
        resolver: ActorResolver = DefaultActorResolver(),
    ): WavePlan? =
        plan(
            identities,
            campaign.settings.waveSize,
            campaign.allSteps.filter { step -> step.assertions.any { it is AssertionSpec.OnlyOneSucceeds } }.map { it.actors },
            resolver,
        )

    /**
     * The waves of [identities] at [size] testers each (residents come on top), keeping the racers of each of [races]
     * together; null without a size or when all the testers who are not residents fit into one wave.
     */
    fun plan(
        identities: List<Identity>,
        size: Int?,
        races: List<ActorExpression> = emptyList(),
        resolver: ActorResolver = DefaultActorResolver(),
    ): WavePlan? {
        if (size == null || size < 1) return null
        val ordered = identities.sortedBy { it.agentId }
        val perRole = ordered.groupingBy { it.role }.eachCount()
        val residents = ordered.filter { perRole.getValue(it.role) == 1 }
        val others = ordered.filterNot { perRole.getValue(it.role) == 1 }
        if (others.size <= size) return null
        val groups = groups(others, races.map { resolver.resolve(it, ordered) })
        val waves = MutableList((others.size + size - 1) / size) { mutableListOf<Identity>() }
        groups.forEach { group -> place(group, waves, size) }
        return WavePlan(residents, waves.filter { it.isNotEmpty() }.map { wave -> wave.sortedBy { it.agentId } })
    }

    /**
     * [testers] as the groups that must share a wave: the racers of each race (overlapping races join), everyone else
     * alone. Largest first, then in agent order.
     */
    private fun groups(
        testers: List<Identity>,
        racers: List<List<Identity>>,
    ): List<List<Identity>> {
        val groupOf = testers.associateWith { mutableSetOf(it) }.toMutableMap()
        racers.forEach { race ->
            val members = race.filter { it in groupOf }
            members.drop(1).forEach { member ->
                val joined = groupOf.getValue(members.first())
                val other = groupOf.getValue(member)
                if (other !== joined) {
                    joined += other
                    other.forEach { groupOf[it] = joined }
                }
            }
        }
        return groupOf.values
            .distinct()
            .map { group -> group.sortedBy { it.agentId } }
            .sortedWith(compareByDescending<List<Identity>> { it.size }.thenBy { it.first().agentId })
    }

    /**
     * Puts [group] into the wave with room for all of it that has the fewest of its role, then the fewest testers; a
     * new wave when none has room; in wave-sized parts when it is larger than a wave.
     */
    private fun place(
        group: List<Identity>,
        waves: MutableList<MutableList<Identity>>,
        size: Int,
    ) {
        if (group.size > size) {
            group.chunked(size).forEach { place(it, waves, size) }
            return
        }
        val role =
            group
                .groupingBy { it.role }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
        val target =
            waves.indices
                .filter { waves[it].size + group.size <= size }
                .minWithOrNull(compareBy<Int>({ i -> waves[i].count { it.role == role } }, { i -> waves[i].size }, { it }))
        if (target == null) waves += group.toMutableList() else waves[target] += group
    }
}
