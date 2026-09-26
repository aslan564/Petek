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

package az.petek.explorer.domain

import az.petek.evidence.domain.EvidenceTier

/**
 * Kinds of test the pattern library proposes (docs/PLAN.md Faza 6 "Test nümunələri kitabxanası", Faza 13 blind
 * patterns). [evidence] is the strongest tier a test of the kind can reach: an oracle check when the site has a test
 * API, else what the browser saw (UI and network), never the model's judgement. [siteWide] kinds need no action: they
 * are proposed even when the site model is empty.
 */
enum class TestPattern(
    val evidence: EvidenceTier,
    val siteWide: Boolean = false,
) {
    /** The normal use of the action works. */
    HAPPY_PATH(EvidenceTier.ORACLE_CONFIRMED),

    /** A role that must not use the action cannot see it and is refused by the server. */
    PERMISSION(EvidenceTier.UI_NETWORK),

    /** Two users do it at the same instant; exactly one succeeds. */
    RACE(EvidenceTier.UI_NETWORK),

    /** One user's action reaches the others live (1 -> N receivers). */
    REALTIME(EvidenceTier.UI_NETWORK),

    /** Empty and too-long input is refused. */
    BOUNDARY(EvidenceTier.UI_NETWORK),

    /** Doing it twice (double submit, second delete) does no harm. */
    IDEMPOTENCY(EvidenceTier.ORACLE_CONFIRMED),

    /** Someone else's object opened straight from the address bar is refused. */
    DIRECT_URL(EvidenceTier.UI_NETWORK),

    /** Links of the site's pages answer (no 4xx/5xx). */
    BROKEN_LINKS(EvidenceTier.UI_NETWORK, siteWide = true),

    /** Pages log no console error and none of their requests fail. */
    CONSOLE_ERRORS(EvidenceTier.UI_NETWORK, siteWide = true),

    /** No request of the site's pages is slow. */
    SLOW_ENDPOINTS(EvidenceTier.UI_NETWORK, siteWide = true),

    /** The back button returns to the page a user came from. */
    BACK_BUTTON(EvidenceTier.UI_NETWORK, siteWide = true),

    /** Pages fit a phone's screen. */
    MOBILE_VIEWPORT(EvidenceTier.UI_NETWORK, siteWide = true),

    /** A session whose cookies are gone no longer shows the signed-in user. */
    SESSION_EXPIRY(EvidenceTier.UI_NETWORK, siteWide = true),
}

/**
 * A test worth writing for action [actionId]. [priority] is 0..100 (higher first); [roles] are the roles the idea is
 * about: for PERMISSION the roles that must be refused, otherwise the roles seen using the action.
 */
data class TestIdea(
    val pattern: TestPattern,
    val actionId: String,
    val rationale: String,
    val priority: Int,
    val roles: List<String>,
)

/**
 * Pure rules that turn a site model into test ideas. Per action kind:
 *
 * | kind | ideas |
 * |---|---|
 * | CREATE | HAPPY_PATH, BOUNDARY, IDEMPOTENCY, DIRECT_URL; REALTIME when the trial touch saw the result arrive live |
 * | APPROVE, REJECT | RACE, PERMISSION |
 * | DELETE | PERMISSION, IDEMPOTENCY |
 * | UPDATE, ASSIGN | HAPPY_PATH, PERMISSION |
 * | REGISTER, LOGIN, SUBMIT | HAPPY_PATH, BOUNDARY |
 * | NAVIGATE, OTHER | none |
 *
 * Every site also gets the site-wide blind checks ([TestPattern.siteWide], action id [SITE]), even with an empty model.
 * Any action some logged-in role was not offered also gets PERMISSION (with a higher priority, because the model saw
 * the difference). Owner instructions raise the priority of actions whose name matches them (+25) or, failing that,
 * whose page matches them (+15), using [Keywords]. Ideas are ordered by priority, then action id, then pattern.
 */
class TestPatternLibrary {
    fun ideas(
        model: SiteModel,
        instructions: String? = null,
    ): List<TestIdea> {
        val keywords = Keywords.of(instructions)
        return (model.actions.flatMap { action -> ideasFor(action, model.page(action.pageId), keywords) } + siteWide(model))
            .sortedWith(compareByDescending<TestIdea> { it.priority }.thenBy { it.actionId }.thenBy { it.pattern.ordinal })
    }

    /** The blind checks every site gets, even one the explorer could not see into ([TestPattern.siteWide]). */
    private fun siteWide(model: SiteModel): List<TestIdea> {
        val pages = model.pages.count { UrlPatterns.ID !in it.urlPattern }.coerceAtLeast(1)
        return TestPattern.entries.filter { it.siteWide }.map { pattern ->
            TestIdea(
                pattern = pattern,
                actionId = SITE,
                rationale = "the site's pages ($pages seen): ${SITE_RATIONALE.getValue(pattern)} (${pattern.evidence.name.lowercase()})",
                priority = BASE_PRIORITY.getValue(pattern),
                roles = emptyList(),
            )
        }
    }

    private fun ideasFor(
        action: ActionModel,
        page: PageModel?,
        keywords: Set<String>,
    ): List<TestIdea> {
        val patterns = LinkedHashSet(PATTERNS_BY_KIND[action.kind].orEmpty())
        if (action.kind == ActionKind.CREATE && action.triggersRealtime == true) patterns += TestPattern.REALTIME
        if (action.kind == ActionKind.CREATE) patterns += TestPattern.DIRECT_URL
        val loggedInDifference = action.forbiddenRoles.isNotEmpty()
        if (loggedInDifference && action.kind !in NO_IDEAS) patterns += TestPattern.PERMISSION
        val boost =
            when {
                Keywords.score(keywords, "${action.name} ${action.id}") > 0 -> NAME_BOOST
                page != null && Keywords.score(keywords, "${page.purpose} ${page.title} ${page.urlPattern}") > 0 -> PAGE_BOOST
                else -> 0
            }
        return patterns.map { pattern ->
            val observedBonus = if (pattern == TestPattern.PERMISSION && loggedInDifference) OBSERVED_DIFFERENCE_BONUS else 0
            TestIdea(
                pattern = pattern,
                actionId = action.id,
                rationale = rationale(pattern, action, page, boost),
                priority = (BASE_PRIORITY.getValue(pattern) + observedBonus + boost).coerceAtMost(MAX_PRIORITY),
                roles = if (pattern == TestPattern.PERMISSION) action.forbiddenRoles.sorted() else action.allowedRoles.sorted(),
            )
        }
    }

    private fun rationale(
        pattern: TestPattern,
        action: ActionModel,
        page: PageModel?,
        boost: Int,
    ): String {
        val subject = "${action.kind} '${action.name}'" + (page?.let { " on ${it.urlPattern}" } ?: "")
        val why =
            when (pattern) {
                TestPattern.HAPPY_PATH -> {
                    "the normal use must work"
                }

                TestPattern.PERMISSION -> {
                    if (action.forbiddenRoles.isEmpty()) {
                        "a role that must not use it has to be refused"
                    } else {
                        "${action.forbiddenRoles.sorted().joinToString()} were not offered it; the server must refuse them too"
                    }
                }

                TestPattern.RACE -> {
                    "two users deciding at the same instant must not both succeed"
                }

                TestPattern.REALTIME -> {
                    val receivers =
                        action.trial
                            ?.seenLiveBy
                            ?.sorted()
                            ?.joinToString()
                            .orEmpty()
                    "the trial touch saw $receivers receive it live; delivery to everyone must be measured"
                }

                TestPattern.BOUNDARY -> {
                    "empty and too long input must be refused"
                }

                TestPattern.IDEMPOTENCY -> {
                    val deletes = action.kind == ActionKind.DELETE
                    if (deletes) "deleting twice must do no harm" else "a double submit must not create two objects"
                }

                TestPattern.DIRECT_URL -> {
                    "what it creates must not open for another tester who types its address"
                }

                else -> {
                    SITE_RATIONALE.getValue(pattern)
                }
            }
        return "$subject: $why" + if (boost > 0) " (matches the owner's instructions)" else ""
    }

    companion object {
        /** The action id of a site-wide idea: it is about the site's pages, not one action. */
        const val SITE = "site"
        const val MAX_PRIORITY = 100
        const val NAME_BOOST = 25
        const val PAGE_BOOST = 15
        const val OBSERVED_DIFFERENCE_BONUS = 10

        val BASE_PRIORITY: Map<TestPattern, Int> =
            mapOf(
                TestPattern.HAPPY_PATH to 60,
                TestPattern.REALTIME to 55,
                TestPattern.PERMISSION to 50,
                TestPattern.RACE to 50,
                TestPattern.IDEMPOTENCY to 35,
                TestPattern.BOUNDARY to 30,
                TestPattern.DIRECT_URL to 45,
                TestPattern.CONSOLE_ERRORS to 40,
                TestPattern.BROKEN_LINKS to 35,
                TestPattern.SESSION_EXPIRY to 30,
                TestPattern.SLOW_ENDPOINTS to 25,
                TestPattern.BACK_BUTTON to 20,
                TestPattern.MOBILE_VIEWPORT to 20,
            )

        private val SITE_RATIONALE: Map<TestPattern, String> =
            mapOf(
                TestPattern.BROKEN_LINKS to "every link must answer",
                TestPattern.CONSOLE_ERRORS to "no console error and no failed request",
                TestPattern.SLOW_ENDPOINTS to "no request may be slow",
                TestPattern.BACK_BUTTON to "the back button must return to the page",
                TestPattern.MOBILE_VIEWPORT to "the pages must fit a phone's screen",
                TestPattern.SESSION_EXPIRY to "an expired session must not show the signed-in user",
            )

        private val NO_IDEAS = setOf(ActionKind.NAVIGATE, ActionKind.OTHER)

        val PATTERNS_BY_KIND: Map<ActionKind, List<TestPattern>> =
            mapOf(
                ActionKind.CREATE to listOf(TestPattern.HAPPY_PATH, TestPattern.BOUNDARY, TestPattern.IDEMPOTENCY),
                ActionKind.APPROVE to listOf(TestPattern.RACE, TestPattern.PERMISSION),
                ActionKind.REJECT to listOf(TestPattern.RACE, TestPattern.PERMISSION),
                ActionKind.DELETE to listOf(TestPattern.PERMISSION, TestPattern.IDEMPOTENCY),
                ActionKind.UPDATE to listOf(TestPattern.HAPPY_PATH, TestPattern.PERMISSION),
                ActionKind.ASSIGN to listOf(TestPattern.HAPPY_PATH, TestPattern.PERMISSION),
                ActionKind.REGISTER to listOf(TestPattern.HAPPY_PATH, TestPattern.BOUNDARY),
                ActionKind.LOGIN to listOf(TestPattern.HAPPY_PATH, TestPattern.BOUNDARY),
                ActionKind.SUBMIT to listOf(TestPattern.HAPPY_PATH, TestPattern.BOUNDARY),
                ActionKind.NAVIGATE to emptyList(),
                ActionKind.OTHER to emptyList(),
            )
    }
}
