/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.domain

/** Kinds of test the pattern library proposes (docs/PLAN.md Faza 6 "Test nümunələri kitabxanası"). */
enum class TestPattern {
    /** The normal use of the action works. */
    HAPPY_PATH,

    /** A role that must not use the action cannot see it and is refused by the server. */
    PERMISSION,

    /** Two users do it at the same instant; exactly one succeeds. */
    RACE,

    /** One user's action reaches the others live (1 -> N receivers). */
    REALTIME,

    /** Empty and too-long input is refused. */
    BOUNDARY,

    /** Doing it twice (double submit, second delete) does no harm. */
    IDEMPOTENCY,
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
 * | CREATE | HAPPY_PATH, BOUNDARY, IDEMPOTENCY; REALTIME when the trial touch saw the result arrive live |
 * | APPROVE, REJECT | RACE, PERMISSION |
 * | DELETE | PERMISSION, IDEMPOTENCY |
 * | UPDATE, ASSIGN | HAPPY_PATH, PERMISSION |
 * | REGISTER, LOGIN, SUBMIT | HAPPY_PATH, BOUNDARY |
 * | NAVIGATE, OTHER | none |
 *
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
        return model.actions
            .flatMap { action -> ideasFor(action, model.page(action.pageId), keywords) }
            .sortedWith(compareByDescending<TestIdea> { it.priority }.thenBy { it.actionId }.thenBy { it.pattern.ordinal })
    }

    private fun ideasFor(
        action: ActionModel,
        page: PageModel?,
        keywords: Set<String>,
    ): List<TestIdea> {
        val patterns = LinkedHashSet(PATTERNS_BY_KIND[action.kind].orEmpty())
        if (action.kind == ActionKind.CREATE && action.triggersRealtime == true) patterns += TestPattern.REALTIME
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
            }
        return "$subject: $why" + if (boost > 0) " (matches the owner's instructions)" else ""
    }

    companion object {
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
