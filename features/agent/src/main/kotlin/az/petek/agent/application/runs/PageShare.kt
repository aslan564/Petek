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

package az.petek.agent.application.runs

import az.petek.agent.domain.ActorShare

/**
 * How the testers of one page-check step (`site_health`, `page_checks`) share its work, by the argument `share`:
 *
 * - `work`: every page on every device of `devices` (default phone, tablet and desktop) is one job, and the jobs are
 *   dealt out, so N testers do N different jobs at the same time: actor i (from 0, in agent id order) of n takes jobs
 *   i, i + n, i + 2n, ... With fewer jobs than testers every tester still gets one: the jobs are dealt round again, as
 *   a second look. The links of a page are asked about once, by the first tester that has the page on the first device.
 * - `pages`: the step's pages are dealt out the same way (without devices).
 * - `links`: every tester checks every page itself, each in its own browser, but the links found on a page are dealt
 *   out, so each link is asked about by one tester only and N testers do not ask the site N times.
 *
 * Without `share` every actor does all of the step's work.
 */
internal object PageShare {
    const val ARG = "share"
    const val WORK = "work"
    const val PAGES = "pages"
    const val LINKS = "links"
    const val DEVICES = "devices"

    /** One page on one device (null: the session's own screen) a tester checks, and whether it asks about the links. */
    data class Job(
        val page: String,
        val device: Device?,
        val asksLinks: Boolean,
    ) {
        /** How problems name the job: the page, and the device when there is one. */
        fun where(path: String): String = device?.let { "$path (${it.key})" } ?: path
    }

    /** The jobs of this actor, in order. */
    fun jobs(
        pages: List<String>,
        args: Map<String, String>,
        share: ActorShare,
    ): List<Job> {
        if (mode(args) != WORK) return pages(pages, args, share).map { Job(it, null, asksLinks = true) }
        val devices = Device.parse(args[DEVICES])
        val jobs = pages.flatMap { page -> devices.mapIndexed { index, device -> Job(page, device, asksLinks = index == 0) } }
        if (jobs.isEmpty()) return emptyList()
        if (jobs.size <= share.of) {
            val job = jobs[share.position % jobs.size]
            // A tester dealt a job a second time looks again, but the links were asked about already.
            return listOf(if (share.position < jobs.size) job else job.copy(asksLinks = false))
        }
        return jobs.filterIndexed { index, _ -> index % share.of == share.position }
    }

    /** The pages this actor checks (`share: pages`). */
    fun pages(
        pages: List<String>,
        args: Map<String, String>,
        share: ActorShare,
    ): List<String> {
        if (mode(args) != PAGES || share.of <= 1 || pages.isEmpty()) return pages
        if (pages.size <= share.of) return listOf(pages[share.position % pages.size])
        return pages.filterIndexed { index, _ -> index % share.of == share.position }
    }

    /** The links (of one page, or the step's links to other sites) this actor asks about (`share: links`). */
    fun <T> links(
        links: List<T>,
        args: Map<String, String>,
        share: ActorShare,
    ): List<T> {
        if (mode(args) != LINKS || share.of <= 1) return links
        return links.filterIndexed { index, _ -> index % share.of == share.position }
    }

    /** What is wrong with `share` and `devices`, or null. */
    fun problem(args: Map<String, String>): String? {
        val mode = mode(args)
        if (mode != null && mode !in MODES) return "share is ${MODES.joinToString(", ")}, not ${args[ARG]?.trim()}"
        return args[DEVICES]?.let { Device.unknown(it) }?.let { "devices are ${Device.entries.joinToString { it.key }}, not $it" }
    }

    private fun mode(args: Map<String, String>): String? = args[ARG]?.trim()?.lowercase()?.ifEmpty { null }

    private val MODES = listOf(WORK, PAGES, LINKS)
}

/** A screen a tester may look at the pages on (`devices: phone,tablet,desktop`). */
internal enum class Device(
    val key: String,
    val width: Int,
    val height: Int,
) {
    PHONE("phone", 375, 812),
    TABLET("tablet", 768, 1024),
    DESKTOP("desktop", 1366, 768),
    ;

    companion object {
        /** The devices [text] names (comma-separated), all of them when it names none. */
        fun parse(text: String?): List<Device> =
            names(text).mapNotNull { name -> entries.firstOrNull { it.key == name } }.distinct().ifEmpty { entries.toList() }

        /** The first name in [text] that is no device, or null. */
        fun unknown(text: String): String? = names(text).firstOrNull { name -> entries.none { it.key == name } }

        private fun names(text: String?): List<String> =
            text
                .orEmpty()
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
    }
}
