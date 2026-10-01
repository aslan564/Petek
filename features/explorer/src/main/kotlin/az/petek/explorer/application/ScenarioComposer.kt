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

package az.petek.explorer.application

import az.petek.campaign.domain.ActorExpression
import az.petek.campaign.domain.ActorExpressionParser
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.EmitSpec
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TemplateRenderer
import az.petek.campaign.domain.Tenant
import az.petek.campaign.domain.WaitForSpec
import az.petek.core.model.Role
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ActionModel
import az.petek.explorer.domain.CoveredIdea
import az.petek.explorer.domain.Drafts
import az.petek.explorer.domain.Keywords
import az.petek.explorer.domain.PageModel
import az.petek.explorer.domain.Resources
import az.petek.explorer.domain.Selectors
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.SkippedIdea
import az.petek.explorer.domain.Slugs
import az.petek.explorer.domain.TestIdea
import az.petek.explorer.domain.TestPattern
import az.petek.explorer.domain.UrlPatterns

/**
 * Turns test ideas into campaign steps, by code only (the LLM is not involved): short `do` texts made from action
 * names, and the assertions that make each idea checkable by code (AGENTS.md rule 2):
 *
 * | idea | steps and assertions |
 * |---|---|
 * | HAPPY_PATH of CREATE | the creator types a marker text; `visible_text` of it, `oracle` contains it when the test API serves the resource; emits `<resource>_created` |
 * | HAPPY_PATH of UPDATE/ASSIGN/SUBMIT | the action on the page (on the created object when the page needs one) |
 * | REALTIME | receivers open the page before the creation and `wait_for` it right after; `visible_text` of the marker and `latency_max` |
 * | PERMISSION | a role that was not offered the action tries it; `not_visible` of its element, `http_status` 403 on its form's path |
 * | RACE | two testers of an allowed role, `parallel`, on the created object; `only_one_succeeds` |
 * | IDEMPOTENCY of CREATE | double submit; `count` of list items with the marker equals 1 |
 *
 * Ideas it cannot express are skipped with the reason: no campaign role (admin, manager, employee) was seen using the
 * action, no observed action creates the object an action needs (or a page needs more than one object), no list
 * selector, boundary rules that were never observed, sign-up and sign-in (the setup run functions do those), deletions
 * (never generated from an exploration), selectors with braces. Texts taken from the site never become template
 * placeholders: braces are replaced in texts, and a selector with braces is never written, so every draft validates.
 *
 * Objects are named by what the form creates: its action path's last segment (`/company/departments` ->
 * `departments`, `/tickets/new` -> `tickets`), and an object page by the segment before its id (`/tickets/{id}`).
 * A create form on an object page (a comment on `/tickets/{id}`) first needs that object created.
 */
internal class ScenarioComposer(
    private val model: SiteModel,
    private val settings: ScenarioSettings,
    private val testApi: Boolean,
    private val templates: TemplateRenderer,
    private val actors: ActorExpressionParser,
) {
    private sealed interface Outcome {
        data class Covered(
            val stepIds: List<String>,
        ) : Outcome

        data class Skipped(
            val reason: String,
        ) : Outcome
    }

    /**
     * A step that creates objects of a resource, what it needs first, and what the others need to refer to them.
     * [page] is the address the creating step opens (a template when it is an object's page).
     */
    private data class Creator(
        val stepId: String,
        val event: String,
        val marker: String,
        val prerequisites: List<String>,
        val page: String,
    ) {
        /** The steps that bring such an object into being, in order. */
        val steps: List<String> get() = prerequisites + stepId
    }

    private val steps = mutableListOf<ScenarioStep>()
    private val stepIds = HashSet<String>()
    private val creators = LinkedHashMap<String, Creator>()
    private val creatorsByResource = LinkedHashMap<String, Creator>()

    /** Actions whose creator is being composed right now: a create form that needs its own kind of object first fails. */
    private val composing = HashSet<String>()
    val idSources = LinkedHashMap<String, IdSource>()

    /** Checks of the pages anyone may see, done in setup before anyone signs in (see [siteChecks]). */
    private val beforeSignIn = mutableListOf<ScenarioStep>()

    val mainSteps: List<ScenarioStep> get() = steps.toList()

    /**
     * The main steps of [ideas]: first the steps of each action idea, in the ideas' order, then the site-wide checks of
     * all site-wide ideas together ([siteChecks]). Call it before [setupSteps]: the checks done before signing in are
     * setup steps.
     */
    fun compose(ideas: List<TestIdea>): Pair<List<CoveredIdea>, List<SkippedIdea>> {
        val covered = mutableListOf<CoveredIdea>()
        val skipped = mutableListOf<SkippedIdea>()
        val (siteWide, actions) = ideas.partition { it.pattern.siteWide }
        val outcomes =
            actions.map { idea ->
                val action = model.action(idea.actionId)
                idea to
                    if (action == null) Outcome.Skipped("the action is not in site model v${model.version}") else ideaSteps(idea, action)
            } + siteChecks(siteWide)
        outcomes.forEach { (idea, outcome) ->
            when (outcome) {
                is Outcome.Covered -> covered += CoveredIdea(idea, outcome.stepIds)
                is Outcome.Skipped -> skipped += SkippedIdea(idea, outcome.reason)
            }
        }
        return covered to skipped
    }

    /** The setup: the checks done before signing in ([compose] makes them), then every tester passes its gate. */
    fun setupSteps(): List<ScenarioStep> = beforeSignIn + gateSteps()

    private fun gateSteps(): List<ScenarioStep> {
        val functions = settings.setup
        if (settings.tenant == Tenant.NONE) {
            // No company to create: every tester passes its own gate (sign-up, owner's account or visit).
            return listOf(step("gates", StepPhase.SETUP, everybody(), StepAction.Run(functions.join)))
        }
        return buildList {
            add(step("owner_signup", StepPhase.SETUP, "admin", StepAction.Run(functions.registerOwner)))
            add(step("seed", StepPhase.SETUP, "admin", StepAction.Run(functions.seedCompany)))
            val joiners =
                listOfNotNull("employee[*]".takeIf { settings.team.employee > 0 }, "manager[*]".takeIf { settings.team.manager > 0 })
            if (joiners.isNotEmpty()) add(step("join", StepPhase.SETUP, joiners.joinToString(" | "), StepAction.Run(functions.join)))
        }
    }

    private fun ideaSteps(
        idea: TestIdea,
        action: ActionModel,
    ): Outcome {
        val page = model.page(action.pageId) ?: return Outcome.Skipped("the page of the action is not in the model")
        return when (idea.pattern) {
            TestPattern.HAPPY_PATH -> happyPath(action, page)
            TestPattern.REALTIME -> realtime(action, page)
            TestPattern.PERMISSION -> permission(action, page)
            TestPattern.RACE -> race(action, page)
            TestPattern.IDEMPOTENCY -> idempotency(action, page)
            TestPattern.BOUNDARY -> boundary(action)
            TestPattern.DIRECT_URL -> directUrl(action, page)
            else -> Outcome.Skipped("${idea.pattern.name.lowercase()} is a site-wide check")
        }
    }

    /**
     * The site-wide checks (Faza 13, 19) of [ideas], done by code: `site_health` for what a visitor meets while a page
     * loads (console and failed requests, slow requests, the back button, the phone layout, an expired session, the
     * site's own links) and `page_checks` for what is on it (in-page links, images, alt texts, titles and headings,
     * links to other sites, language versions). Every tester of a group works at once on a job of its own (`share: work`): each page on a
     * phone, a tablet and a desktop, dealt out among them, and dealt round again as a second look when there are more
     * testers than jobs; the links of a page are asked about once.
     *
     * Where people sign in (a site with companies, or roles seen signed in), the pages a visitor may see are checked in
     * setup, before the testers sign in, and then each role checks the pages only it saw after the scenario; the expired
     * session is checked there. A site without sign-in has one group, checked by everyone in the main steps.
     */
    private fun siteChecks(ideas: List<TestIdea>): List<Pair<TestIdea, Outcome>> {
        if (ideas.isEmpty()) return emptyList()
        val patterns = ideas.map { it.pattern }.toSet()
        // Where requests are timed, the pages are too: `perf` records how fast each became usable, for comparing releases.
        val health =
            patterns
                .mapNotNull { HEALTH_CHECKS[it] }
                .filter { it != SESSION }
                .flatMap { if (it == SLOW) listOf(it, PERF) else listOf(it) }
                .sortedBy { CHECK_ORDER.indexOf(it) }
        val content = patterns.mapNotNull { PAGE_CHECKS[it] }.sortedBy { CHECK_ORDER.indexOf(it) }
        val signIn = settings.tenant == Tenant.COMPANY || settings.team.roles.any { it != ScenarioSettings.VISITOR }
        val stepsOf = mutableMapOf<String, MutableList<String>>()

        fun group(
            prefix: String,
            phase: StepPhase,
            actor: String,
            pages: List<String>,
            healthChecks: List<String>,
        ) {
            if (healthChecks.isNotEmpty()) {
                val id = Slugs.firstFree("$prefix-pages") { it !in stepIds }
                val step = checkStep(id, phase, actor, settings.setup.siteHealth, healthChecks, pages)
                if (phase == StepPhase.SETUP) beforeSignIn += step else steps += step
                healthChecks.forEach { stepsOf.getOrPut(it) { mutableListOf() } += id }
            }
            // A group checks lists only where its pages have some (the visitor's).
            val lists = listsOf(pages)
            val checks = if (lists.isEmpty()) content - LISTS else content
            if (checks.isNotEmpty()) {
                val id = Slugs.firstFree("$prefix-content") { it !in stepIds }
                val step = checkStep(id, phase, actor, settings.setup.pageChecks, checks, pages, lists)
                if (phase == StepPhase.SETUP) beforeSignIn += step else steps += step
                checks.forEach { stepsOf.getOrPut(it) { mutableListOf() } += id }
            }
        }

        val public = pagesSeenBy { ScenarioSettings.VISITOR.key in it.reachableBy }
        if (!signIn) {
            group("site", StepPhase.MAIN, everybody(), public.ifEmpty { listOf("home") }, health)
        } else {
            group("public", StepPhase.SETUP, everybody(), public.ifEmpty { listOf("home") }, health)
            val session = if (TestPattern.SESSION_EXPIRY in patterns) listOf(SESSION) else emptyList()
            settings.team.roles.filter { it != ScenarioSettings.VISITOR }.forEach { role ->
                val own = pagesSeenBy { role.key in it.reachableBy && ScenarioSettings.VISITOR.key !in it.reachableBy }
                if (own.isEmpty() && session.isEmpty()) return@forEach
                val checks = if (own.isEmpty()) session else health + session
                group(role.key, StepPhase.MAIN, everyone(role), own.ifEmpty { listOf("home") }, checks)
            }
        }
        return ideas.map { idea ->
            val check = HEALTH_CHECKS[idea.pattern] ?: PAGE_CHECKS[idea.pattern]
            val ids = check?.let { stepsOf[it] }.orEmpty()
            idea to if (ids.isEmpty()) Outcome.Skipped("no page to check for ${idea.pattern.name.lowercase()}") else Outcome.Covered(ids)
        }
    }

    private fun checkStep(
        id: String,
        phase: StepPhase,
        actor: String,
        function: String,
        checks: List<String>,
        pages: List<String>,
        lists: String = "",
    ): ScenarioStep =
        step(
            id = id,
            phase = phase,
            actor = actor,
            action =
                StepAction.Run(
                    function,
                    mapOf(
                        "checks" to checks.joinToString(","),
                        "pages" to pages.joinToString(","),
                        SHARE to SHARE_WORK,
                        DEVICES to ALL_DEVICES,
                    ) + (if (lists.isEmpty()) emptyMap() else mapOf(LISTS to lists)),
                ),
        )

    /**
     * The `lists` argument of `page_checks` for [pages]: `page>objects>seen` per list the visitor saw, with the object
     * page's id segment written as an asterisk (braces would be read as a placeholder).
     */
    private fun listsOf(pages: List<String>): String =
        pages
            .flatMap { page ->
                model.pageByPattern(page)?.lists.orEmpty().toSortedMap().map { (objects, seen) ->
                    "$page>${objects.replace(UrlPatterns.ID, "*")}>$seen"
                }
            }.filter { literal(it.substringBefore('>')) }
            .joinToString(",")

    /** The site's own pages (no object id) seen by whom [seen] accepts, at most [MAX_SITE_PAGES]. */
    private fun pagesSeenBy(seen: (PageModel) -> Boolean): List<String> =
        model.pages
            .filter(seen)
            .map { it.urlPattern }
            .filter { UrlPatterns.ID !in it && it.startsWith("/") && literal(it) }
            .distinct()
            .take(MAX_SITE_PAGES)

    /** Every tester of the campaign. */
    private fun everybody(): String = settings.team.roles.joinToString(" | ") { everyone(it) }

    /**
     * What [action] creates, opened by its address by a second tester who did not create it: the same role's second
     * tester when there is one, else another role's. Needs the object's own page (`/notes/{id}`) in the model or seen
     * after the trial touch. Not written for objects whose pages the visitor saw (an article, a public post: the site
     * shows them to everyone), except a draft, which must not open for anyone else ([Drafts], Faza 19).
     */
    private fun directUrl(
        action: ActionModel,
        page: PageModel,
    ): Outcome {
        val role = actorRole(action) ?: return noRole(action)
        val resource = Resources.created(action, page)
        val objectPattern =
            model.pages.map { it.urlPattern }.firstOrNull { pattern ->
                pattern.split('/').count { it == UrlPatterns.ID } == 1 && Resources.objectOf(pattern) == resource
            } ?: action.trial?.urlPatternAfter?.takeIf { pattern -> pattern.split('/').count { it == UrlPatterns.ID } == 1 }
                ?: return Outcome.Skipped("no page of one ${site(resource)} object was seen, so there is no address to type")
        // What a visitor sees is the site's to show to everyone; a draft must not open for anyone else all the same.
        if (Drafts.public(model, objectPattern) && !Drafts.saves(action)) {
            return Outcome.Skipped("visitors see ${site(resource)} pages ($objectPattern): the site shows them to everyone")
        }
        val other =
            when {
                settings.team.count(role) >= 2 -> {
                    "${role.key}[n=2]"
                }

                else -> {
                    settings.team.roles
                        .firstOrNull { it != role }
                        ?.let(::single)
                        ?: return Outcome.Skipped("a second tester is needed to open someone else's ${site(resource)}")
                }
            }
        val creator = creator(action, page, role) ?: return noCreator(action, page)
        val id = stepId(action, "direct-url")
        steps +=
            step(
                id = id,
                phase = StepPhase.MAIN,
                actor = other,
                action =
                    StepAction.Run(
                        settings.setup.directUrl,
                        mapOf("path" to withObject(objectPattern, creator.event), "text" to creator.marker),
                    ),
            )
        return Outcome.Covered(creator.steps + id)
    }

    private fun happyPath(
        action: ActionModel,
        page: PageModel,
    ): Outcome {
        if (action.kind == ActionKind.LOGIN || action.kind == ActionKind.REGISTER) {
            return Outcome.Skipped("sign-up and sign-in are done by the setup run functions of every generated campaign")
        }
        val role = actorRole(action) ?: return noRole(action)
        if (action.kind == ActionKind.CREATE) {
            val creator = creator(action, page, role) ?: return noCreator(action, page)
            return Outcome.Covered(creator.steps)
        }
        val (open, prerequisites) = objectPage(page) ?: return noCreator(action, page)
        val id = stepId(action, "happy")
        steps += step(id, StepPhase.MAIN, single(role), StepAction.Do("$open səhifəsini aç və '${site(action.name)}' əməliyyatını icra et"))
        return Outcome.Covered(prerequisites + id)
    }

    /**
     * Live delivery is only visible on a page that is open when the object is created, and the runner checks
     * `visible_text` of a `wait_for` step against the creation time (t0 + within), running steps in order. So the
     * receivers open the creator's page in a step right before the creation, and the check follows right after it:
     * a check placed after other steps would find its window already over.
     */
    private fun realtime(
        action: ActionModel,
        page: PageModel,
    ): Outcome {
        val role = actorRole(action) ?: return noRole(action)
        val seenBy = campaignRoles(action.trial?.seenLiveBy.orEmpty()).filter { it != role }
        val receivers = seenBy.ifEmpty { PREFERENCE.filter { it != role && settings.team.count(it) > 0 } }
        if (receivers.isEmpty()) return Outcome.Skipped("the generated team has no other role to receive it")
        val creator = creator(action, page, role) ?: return noCreator(action, page)
        val actor = receivers.joinToString(" | ", transform = ::everyone)
        val watchId = stepId(action, "watch")
        val id = stepId(action, "realtime")
        val watch = step(watchId, StepPhase.MAIN, actor, StepAction.Do("${creator.page} səhifəsini aç və orada qal"))
        val check =
            step(
                id = id,
                phase = StepPhase.MAIN,
                actor = actor,
                action = StepAction.None,
                waitFor = WaitForSpec(creator.event, settings.waitTimeout),
                assertions =
                    listOf(
                        AssertionSpec.VisibleText(creator.marker, settings.visibleWithin),
                        AssertionSpec.LatencyMax(settings.maxLatency),
                    ),
            )
        val at = steps.indexOfFirst { it.id == creator.stepId }
        steps.add(at + 1, check)
        steps.add(at, watch)
        return Outcome.Covered(creator.prerequisites + listOf(watchId, creator.stepId, id))
    }

    private fun permission(
        action: ActionModel,
        page: PageModel,
    ): Outcome {
        val role =
            campaignRoles(action.forbiddenRoles).firstOrNull()
                ?: return Outcome.Skipped(
                    "no campaign role was seen without '${site(action.name)}'; tell Pətək which role must not use it",
                )
        if (!literal(action.selector)) return braces(action.selector)
        val (open, prerequisites) = objectPage(page) ?: return noCreator(action, page)
        val assertions = mutableListOf<AssertionSpec>(AssertionSpec.NotVisible(text = null, selector = action.selector))
        httpCheck(action, page)?.let { assertions += it }
        val id = stepId(action, "permission")
        steps +=
            step(
                id = id,
                phase = StepPhase.MAIN,
                actor = single(role),
                action = StepAction.Do("$open səhifəsini aç və '${site(action.name)}' etməyə çalış"),
                assertions = assertions,
            )
        return Outcome.Covered(prerequisites + id)
    }

    private fun race(
        action: ActionModel,
        page: PageModel,
    ): Outcome {
        val name = site(action.name)
        val seen = action.allowedRoles.sorted().joinToString()
        val role =
            campaignRoles(action.allowedRoles).firstOrNull {
                settings.team.count(it) >= 2 && (it != Role.ADMIN || settings.tenant == Tenant.NONE)
            }
                ?: return Outcome.Skipped("a race needs two testers of a role that was offered '$name' (seen: $seen)")
        val (open, prerequisites) = objectPage(page) ?: return noCreator(action, page)
        val request =
            requestOf(action)
                ?: return Outcome.Skipped("the request '$name' sends was not seen, so code could not decide who won a race")
        val id = stepId(action, "race")
        steps +=
            step(
                id = id,
                phase = StepPhase.MAIN,
                actor = actors.parseList(listOf("${role.key}[n=1]", "${role.key}[n=2]")),
                action = StepAction.Do("$open səhifəsini aç və '${site(action.name)}' et"),
                parallel = true,
                assertions = listOf(AssertionSpec.OnlyOneSucceeds(request)),
            )
        return Outcome.Covered(prerequisites + id)
    }

    private fun idempotency(
        action: ActionModel,
        page: PageModel,
    ): Outcome {
        if (action.kind != ActionKind.CREATE) {
            return Outcome.Skipped("deleting steps are never generated from an exploration; add them to the draft by hand if wanted")
        }
        val role = actorRole(action) ?: return noRole(action)
        // What the form creates, not what the page shows: a comment form on a post's page makes comments.
        val resource = Resources.created(action, page)
        val item =
            page.testIds.firstOrNull { testId ->
                testId.endsWith(ITEM_SUFFIX) &&
                    Keywords.words(testId.removeSuffix(ITEM_SUFFIX)).any { word -> Keywords.matches(word, resource) }
            }
                ?: return Outcome.Skipped(
                    "no list item ('<name>-item' test id) was observed on ${page.urlPattern} to count the created objects",
                )
        if (!literal(item)) return braces(Selectors.testId(item))
        val (open, prerequisites) = objectPage(page) ?: return noCreator(action, page)
        // `{pass}`: every wave and the account swap submit their own text, so each count sees only its own pass.
        val marker = "Pətək təkrar ${Slugs.of(action.id)} {pass}"
        val id = stepId(action, "idempotency")
        steps +=
            step(
                id = id,
                phase = StepPhase.MAIN,
                actor = single(role),
                action =
                    StepAction.Do(
                        "$open səhifəsini aç, mətn sahələrinə '$marker' yaz və '${site(action.name)}' düyməsini tez-tez iki dəfə sıx",
                    ),
                assertions = listOf(AssertionSpec.Count("${Selectors.testId(item)}:has-text(\"$marker\")", 1)),
            )
        return Outcome.Covered(prerequisites + id)
    }

    private fun boundary(action: ActionModel): Outcome =
        if (action.kind == ActionKind.LOGIN || action.kind == ActionKind.REGISTER) {
            Outcome.Skipped("sign-up and sign-in are done by the setup run functions of every generated campaign")
        } else {
            Outcome.Skipped(
                "the site's input rules (required fields, maximum length) were not observed; add the expected validation " +
                    "message to test empty and too long input for '${site(action.name)}'",
            )
        }

    /**
     * The step that creates an object with [action] (made once per action; the first per resource is its creator),
     * after the steps that create the object its page shows, if any. Null when that object cannot be created.
     */
    private fun creator(
        action: ActionModel,
        page: PageModel,
        role: Role,
    ): Creator? {
        creators[action.id]?.let { return it }
        if (!composing.add(action.id)) return null
        try {
            val (open, prerequisites) = objectPage(page) ?: return null
            val resource = Resources.created(action, page)
            val event = Slugs.firstFree(Slugs.identifier(resource, "item") + "_created", "_") { it !in idSources && it !in usedEvents() }
            // `{pass}`: a later wave or the account swap creates a new text, never one the pages show already.
            val marker = "Pətək yoxlaması ${Slugs.of(action.id)} {pass}"
            val assertions = mutableListOf<AssertionSpec>(AssertionSpec.VisibleText(marker, settings.visibleWithin))
            val idSource =
                if (testApi && servedByTestApi(action, resource)) {
                    idSources[event] = IdSource.OracleField("/test/$resource/latest?by={self.email}", "id")
                    assertions += AssertionSpec.Oracle("/test/$resource/{last_id}", field = null, equals = null, contains = marker)
                    null
                } else {
                    action.trial
                        ?.urlPatternAfter
                        ?.let(::urlRegexOf)
                        ?.let(IdSource::UrlRegex)
                }
            val id = stepId(action, "happy")
            steps +=
                step(
                    id = id,
                    phase = StepPhase.MAIN,
                    actor = single(role),
                    action =
                        StepAction.Do(
                            "$open səhifəsini aç və '${site(action.name)}' ilə yeni qeyd yarat; mətn sahələrinə '$marker' yaz",
                        ),
                    // The form's request is the write the receivers' delivery latency is measured from (Faza 24.10).
                    emits = EmitSpec(event, idSource, requestOf(action)),
                    assertions = assertions,
                )
            return Creator(id, event, marker, prerequisites, open).also {
                creators[action.id] = it
                creatorsByResource.putIfAbsent(resource, it)
            }
        } finally {
            composing.remove(action.id)
        }
    }

    /**
     * Whether the site's test API serves [resource] (Faza 25.2): the trial touch of [action] saw it answer with the object
     * it created, or the frame names the resource ([ScenarioSettings.oracleResources]); never a list assumed of a site.
     */
    private fun servedByTestApi(
        action: ActionModel,
        resource: String,
    ): Boolean = action.trial?.testApi == true || resource in settings.oracleResources

    /**
     * Where a step about [page] opens it: the page itself, or, when the page shows one object (`/tickets/{id}`), that
     * object created earlier in the draft, e.g. `/tickets/{event.tickets_created.id}`, after the steps creating it.
     * Null when the page needs an object that no observed action creates, or more than one object (only one can be
     * referred to).
     */
    private fun objectPage(page: PageModel): Pair<String, List<String>>? =
        when (page.urlPattern.split('/').count { it == UrlPatterns.ID }) {
            0 -> page.urlPattern to emptyList()
            1 -> creatorFor(Resources.objectOf(page.urlPattern))?.let { withObject(page.urlPattern, it.event) to it.steps }
            else -> null
        }

    private fun creatorFor(resource: String): Creator? {
        creatorsByResource[resource]?.let { return it }
        for (candidate in model.actions) {
            if (candidate.kind != ActionKind.CREATE) continue
            val role = actorRole(candidate) ?: continue
            val page = model.page(candidate.pageId) ?: continue
            if (Resources.created(candidate, page) != resource) continue
            creator(candidate, page, role)?.let { return it }
        }
        return null
    }

    /** `http_status` for a role that must be refused: the action's form path, with the created object when it needs one. */
    private fun httpCheck(
        action: ActionModel,
        page: PageModel,
    ): AssertionSpec.HttpStatus? {
        val path = action.httpPath ?: return null
        val method = action.httpMethod?.uppercase()?.takeIf { it in WRITE_METHODS } ?: return null
        val resolved =
            when (path.split('/').count { it == UrlPatterns.ID }) {
                0 -> path
                1 -> creatorFor(Resources.objectOf(path))?.let { withObject(path, it.event) } ?: return null
                else -> return null
            }
        return AssertionSpec.HttpStatus(resolved, method, settings.forbiddenStatus)
    }

    /**
     * The request an action sends, as its form showed it, any object id in its path (`POST /tickets/[^/]+/approve`): it
     * decides a race and marks when a created object reached the target. Null when the action submits no form the
     * explorer saw.
     */
    private fun requestOf(action: ActionModel): RequestPattern? {
        val path = action.httpPath ?: return null
        val method = action.httpMethod?.uppercase()?.takeIf { it in RequestPattern.MUTATING_METHODS } ?: return null
        return RequestPattern(method, path.split('/').joinToString("/") { if (it == UrlPatterns.ID) "[^/]+" else escapeRegex(it) })
    }

    private fun withObject(
        pattern: String,
        event: String,
    ): String = pattern.replaceFirst(UrlPatterns.ID, "{event.$event.id}")

    /** `/tickets/{id}` -> regex capturing the id from the address the browser shows after creating. */
    private fun urlRegexOf(pattern: String): String? {
        if (pattern.split('/').count { it == UrlPatterns.ID } != 1) return null
        val (before, after) = pattern.split(UrlPatterns.ID, limit = 2)
        return escapeRegex(before) + "([^/?#]+)" + escapeRegex(after)
    }

    private fun escapeRegex(text: String): String = text.map { if (it in REGEX_META) "\\$it" else "$it" }.joinToString("")

    /** Campaign templates read braces as placeholders: a selector with braces would make the whole draft invalid. */
    private fun literal(selector: String): Boolean = '{' !in selector && '}' !in selector

    private fun braces(selector: String): Outcome =
        Outcome.Skipped("its selector ${site(selector)} contains braces, which campaign templates would read as a placeholder")

    private fun actorRole(action: ActionModel): Role? = campaignRoles(action.allowedRoles).firstOrNull { settings.team.count(it) > 0 }

    private fun campaignRoles(names: Collection<String>): List<Role> {
        val roles = names.mapNotNull(Role::fromKey).toSet()
        if (settings.tenant == Tenant.NONE) return settings.team.roles.filter { it in roles }
        return PREFERENCE.filter { it in roles }
    }

    /** The company's admin is one person; any other role (every role of a site without companies) is picked by index. */
    private fun single(role: Role): String = if (role == Role.ADMIN && settings.tenant == Tenant.COMPANY) "admin" else "${role.key}[n=1]"

    private fun everyone(role: Role): String = if (role == Role.ADMIN && settings.tenant == Tenant.COMPANY) "admin" else "${role.key}[*]"

    private fun noRole(action: ActionModel): Outcome =
        Outcome.Skipped(
            "no campaign role (${settings.team.roles.joinToString { it.key }}) was seen using '${site(action.name)}' " +
                "(seen: ${action.allowedRoles.sorted().joinToString().ifEmpty { "nobody" }})",
        )

    private fun noCreator(
        action: ActionModel,
        page: PageModel,
    ): Outcome =
        if (page.urlPattern.split('/').count { it == UrlPatterns.ID } > 1) {
            Outcome.Skipped("'${site(action.name)}' works on nested objects of ${page.urlPattern}; a draft can refer to one object only")
        } else {
            Outcome.Skipped(
                "'${site(action.name)}' works on one object of ${page.urlPattern}, but no observed action creates such an object",
            )
        }

    private fun stepId(
        action: ActionModel,
        suffix: String,
    ): String {
        val base = "${Slugs.of(action.id).ifEmpty { "action" }}-$suffix"
        return Slugs.firstFree(base) { stepIds.add(it) }
    }

    private fun usedEvents(): Set<String> = creators.values.map { it.event }.toSet()

    /** Site text as step text: whitespace collapsed, cut, and never a template placeholder. */
    private fun site(text: String): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim().take(MAX_SITE_TEXT)
        return if (templates.placeholders(collapsed).isNotEmpty() || '{' in collapsed || '}' in collapsed) {
            collapsed.replace('{', '(').replace('}', ')')
        } else {
            collapsed
        }
    }

    private fun step(
        id: String,
        phase: StepPhase,
        actor: String,
        action: StepAction,
        emits: EmitSpec? = null,
        waitFor: WaitForSpec? = null,
        parallel: Boolean = false,
        assertions: List<AssertionSpec> = emptyList(),
    ): ScenarioStep = step(id, phase, actors.parse(actor), action, emits, waitFor, parallel, assertions)

    private fun step(
        id: String,
        phase: StepPhase,
        actor: ActorExpression,
        action: StepAction,
        emits: EmitSpec? = null,
        waitFor: WaitForSpec? = null,
        parallel: Boolean = false,
        assertions: List<AssertionSpec> = emptyList(),
    ): ScenarioStep {
        stepIds += id
        return ScenarioStep(
            id = id,
            phase = phase,
            actors = actor,
            action = action,
            emits = emits,
            waitFor = waitFor,
            parallel = parallel,
            assertions = assertions,
            onFail = null,
            line = 0,
        )
    }

    private companion object {
        /** Which campaign role acts when several may: the least privileged first. */
        val PREFERENCE = listOf(Role.EMPLOYEE, Role.MANAGER, Role.ADMIN)
        val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        const val ITEM_SUFFIX = "-item"

        /** The most pages one group of site-wide checks visits. */
        const val MAX_SITE_PAGES = 20
        const val SESSION = "session"
        const val SLOW = "slow"

        /** `site_health`'s page timing: never fails a step, compared between releases (Faza 14). */
        const val PERF = "perf"
        const val LISTS = "lists"
        const val SHARE = "share"
        const val SHARE_WORK = "work"
        const val DEVICES = "devices"
        const val ALL_DEVICES = "phone,tablet,desktop"

        /** The `site_health` check of each site-wide pattern. */
        val HEALTH_CHECKS: Map<TestPattern, String> =
            mapOf(
                TestPattern.BROKEN_LINKS to "links",
                TestPattern.CONSOLE_ERRORS to "console",
                TestPattern.SLOW_ENDPOINTS to SLOW,
                TestPattern.BACK_BUTTON to "back",
                TestPattern.MOBILE_VIEWPORT to "mobile",
                TestPattern.SESSION_EXPIRY to SESSION,
            )

        /** The `page_checks` check of each site-wide pattern (Faza 19). */
        val PAGE_CHECKS: Map<TestPattern, String> =
            mapOf(
                TestPattern.PAGE_ANCHORS to "anchors",
                TestPattern.BROKEN_IMAGES to "images",
                TestPattern.IMAGE_ALT to "alt",
                TestPattern.PAGE_META to "meta",
                TestPattern.OUTBOUND_LINKS to "outbound",
                TestPattern.LANGUAGE_MIRRORS to "mirrors",
                TestPattern.EMPTY_LISTS to LISTS,
            )

        /** The order checks are written in, so a draft reads the same whatever the ideas' order. */
        val CHECK_ORDER =
            listOf(
                "console",
                SLOW,
                PERF,
                "links",
                "back",
                "mobile",
                SESSION,
                "anchors",
                "images",
                "alt",
                "meta",
                "outbound",
                "mirrors",
                LISTS,
            )
        const val MAX_SITE_TEXT = 60
        const val REGEX_META = ".[]{}()*+?^$|\\"
    }
}
