package az.petek.explorer.domain

import az.petek.browser.domain.RealtimeTransport
import az.petek.core.ids.ArtifactId
import java.net.URI
import java.time.Instant

/** One page load as the crawler saw it, from one viewpoint ([role] is `anonymous` or a role name). */
data class PageObservation(
    val role: String,
    val urlPattern: String,
    val url: URI,
    val title: String,
    val purpose: String?,
    val forms: List<FormModel>,
    val testIds: List<String>,
    val linkCount: Int,
    val loadMs: Long?,
    val evidence: List<ArtifactId>,
)

/**
 * Grows the [SiteModel] while the explorer crawls, merging what every viewpoint saw: a page is one entry per URL
 * pattern whatever role loaded it, an action is one entry per element (a `data-testid` identifies it site-wide, other
 * selectors per page). Observed facts win over inferred ones: a kind or name found by code is never replaced by the
 * LLM's reading, which only fills gaps. [build] infers each action's forbidden roles by comparing roles.
 * Not thread-safe: one exploration uses one accumulator from one coroutine.
 */
class SiteModelAccumulator(
    private val target: URI,
) {
    private val roles = LinkedHashMap<String, RoleBuilder>()
    private val pages = LinkedHashMap<String, PageBuilder>()
    private val actionIdsByIdentity = LinkedHashMap<String, String>()
    private val actions = LinkedHashMap<String, ActionBuilder>()
    private val realtime = LinkedHashMap<RealtimeTransport, RealtimeBuilder>()
    private val unknowns = mutableListOf<Unknown>()
    private val examples = LinkedHashMap<Pair<String, String>, URI>()

    fun role(
        name: String,
        anonymous: Boolean,
    ) {
        roles.getOrPut(name) { RoleBuilder(name, anonymous) }
    }

    /** The page id a pattern has (or will get) in this model. */
    fun pageIdOf(urlPattern: String): String = pages[urlPattern]?.id ?: uniquePageId(urlPattern)

    fun page(urlPattern: String): PageModel? = pages[urlPattern]?.build()

    /** Pages as they stand, in discovery order. */
    fun pages(): List<PageModel> = pages.values.map { it.build() }

    /** A concrete address at which [role] loaded the page with [urlPattern], to go back to it later. */
    fun exampleUrl(
        urlPattern: String,
        role: String,
    ): URI? = examples[urlPattern to role]

    /** Merges one page load; returns the page as it stands now. */
    fun recordPage(observation: PageObservation): PageModel {
        val page = pages.getOrPut(observation.urlPattern) { PageBuilder(uniquePageId(observation.urlPattern), observation.urlPattern) }
        page.merge(observation)
        examples.putIfAbsent(observation.urlPattern to observation.role, observation.url)
        roleOf(observation.role).apply {
            pageIds += page.id
            evidence.addCapped(observation.evidence)
        }
        return page.build()
    }

    /** [role] asked for a page with [urlPattern] and was refused (401/403 or sent elsewhere). */
    fun recordDenied(
        role: String,
        urlPattern: String,
    ) {
        roleOf(role).deniedPatterns += urlPattern
    }

    /** Merges an action [role] was offered on page [pageId]; returns it when it is new to the model, else null. */
    fun recordAction(
        role: String,
        pageId: String,
        candidate: ActionCandidate,
        evidence: List<ArtifactId>,
    ): ActionModel? {
        val identity = Selectors.testIdOf(candidate.selector)?.let { "testid:$it" } ?: "$pageId|${candidate.selector}"
        val existingId = actionIdsByIdentity[identity]
        if (existingId != null) {
            actions.getValue(existingId).merge(role, candidate, evidence)
            return null
        }
        val id = uniqueActionId(pageId, candidate)
        actionIdsByIdentity[identity] = id
        val builder = ActionBuilder(id, pageId, candidate, role, evidence)
        actions[id] = builder
        return builder.build(emptySet())
    }

    fun recordTrial(
        actionId: String,
        trial: TrialTouch,
    ) {
        val action = actions[actionId] ?: return
        action.trial = trial
        if (trial.seenLiveBy.isNotEmpty()) action.triggersRealtime = true
    }

    /** Records a live-update transport seen on page [pageId]; returns true when the transport is new to the model. */
    fun recordRealtime(
        transport: RealtimeTransport,
        details: List<String>,
        pageId: String,
        role: String,
        evidence: List<ArtifactId>,
    ): Boolean {
        val isNew = transport !in realtime
        realtime.getOrPut(transport) { RealtimeBuilder(transport) }.apply {
            this.details.addAll(details.take(MAX_DETAILS))
            pages += pageId
            roles += role
            this.evidence.addCapped(evidence)
        }
        return isNew
    }

    /** Adds a question for the owner unless the same question was asked before; returns it when added. */
    fun recordUnknown(
        question: String,
        context: String,
        pageId: String?,
        provenance: Provenance,
        evidence: List<ArtifactId>,
    ): Unknown? {
        val normalized = normalize(question)
        if (normalized.isEmpty() || unknowns.any { normalize(it.question) == normalized }) return null
        val unknown = Unknown("u${unknowns.size + 1}", question.trim(), context.trim(), pageId, provenance, evidence.take(MAX_EVIDENCE))
        unknowns += unknown
        return unknown
    }

    /** Actions as they stand, with forbidden roles inferred so far. */
    fun actions(): List<ActionModel> = actions.values.map { it.build(forbidden(it)) }

    fun counts(findings: Int): ModelCounts =
        ModelCounts(
            pages = pages.size,
            forms = pages.values.sumOf { it.formCount },
            actions = actions.size,
            realtime = realtime.size,
            unknowns = unknowns.size,
            findings = findings,
        )

    fun build(
        version: Int,
        explorationId: ExplorationId,
        createdAt: Instant,
        partial: Boolean = false,
    ): SiteModel =
        SiteModel(
            version = version,
            explorationId = explorationId,
            target = target,
            createdAt = createdAt,
            pages = pages.values.map { it.build() },
            actions = actions(),
            roles = roles.values.map { it.build() },
            realtime = realtime.values.map { it.build() },
            unknowns = unknowns.toList(),
            partial = partial,
        )

    /**
     * Logged-in roles that loaded the action's page (or were refused it) without being offered the action.
     * Anonymous visitors are never listed: nearly everything is closed to them, which says nothing about permissions.
     */
    private fun forbidden(action: ActionBuilder): Set<String> {
        val page = pages.values.firstOrNull { it.id == action.pageId } ?: return emptySet()
        return roles.values
            .filter { !it.anonymous && it.name !in action.allowedRoles }
            .filter { it.name in page.reachableBy || page.pattern in it.deniedPatterns }
            .mapTo(LinkedHashSet()) { it.name }
    }

    private fun roleOf(name: String): RoleBuilder = roles.getOrPut(name) { RoleBuilder(name, name == ANONYMOUS) }

    private fun uniquePageId(pattern: String): String {
        val base = UrlPatterns.pageId(pattern)
        val taken = pages.values.map { it.id }.toSet()
        return Slugs.firstFree(base) { it !in taken }
    }

    private fun uniqueActionId(
        pageId: String,
        candidate: ActionCandidate,
    ): String {
        val base =
            Selectors.testIdOf(candidate.selector)?.let(Slugs::of)?.takeIf { it.isNotEmpty() }
                ?: "$pageId.${Slugs.of(candidate.name).ifEmpty { candidate.kind.name.lowercase() }}"
        return Slugs.firstFree(base) { it !in actions }
    }

    private class RoleBuilder(
        val name: String,
        val anonymous: Boolean,
    ) {
        val pageIds = LinkedHashSet<String>()
        val deniedPatterns = LinkedHashSet<String>()
        val evidence = LinkedHashSet<ArtifactId>()

        fun build() = RoleModel(name, anonymous, pageIds.toSet(), deniedPatterns.toSet(), Provenance.OBSERVED, evidence.toList())
    }

    private class PageBuilder(
        val id: String,
        val pattern: String,
    ) {
        private var title = ""
        private var purpose = ""
        val reachableBy = LinkedHashSet<String>()
        private val forms = LinkedHashMap<String, FormModel>()
        private val testIds = LinkedHashSet<String>()
        private var linkCount = 0
        private var loadMs: Long? = null
        private val evidence = LinkedHashSet<ArtifactId>()

        val formCount: Int get() = forms.size

        fun merge(observation: PageObservation) {
            if (title.isBlank()) title = observation.title
            if (purpose.isBlank()) purpose = observation.purpose.orEmpty()
            reachableBy += observation.role
            observation.forms.forEach { form ->
                forms.merge(form.key, form) { old, new ->
                    val known = old.fields.map { it.key }.toSet()
                    old.copy(
                        fields = old.fields + new.fields.filter { it.key !in known },
                        evidence = (old.evidence + new.evidence).distinct().take(MAX_EVIDENCE),
                    )
                }
            }
            testIds.addAll(observation.testIds.take(MAX_TEST_IDS - testIds.size.coerceAtMost(MAX_TEST_IDS)))
            linkCount = maxOf(linkCount, observation.linkCount)
            if (loadMs == null) loadMs = observation.loadMs
            evidence.addCapped(observation.evidence)
        }

        fun build() =
            PageModel(
                id = id,
                urlPattern = pattern,
                title = title,
                purpose = purpose,
                reachableBy = reachableBy.toSet(),
                forms = forms.values.toList(),
                testIds = testIds.toList(),
                linkCount = linkCount,
                loadMs = loadMs,
                provenance = Provenance.OBSERVED,
                evidence = evidence.toList(),
            )
    }

    private class ActionBuilder(
        val id: String,
        val pageId: String,
        first: ActionCandidate,
        role: String,
        evidence: List<ArtifactId>,
    ) {
        private var candidate = first
        val allowedRoles = linkedSetOf(role)
        private val evidence = LinkedHashSet<ArtifactId>().apply { addCapped(evidence) }
        var triggersRealtime: Boolean? = null
        var trial: TrialTouch? = null

        fun merge(
            role: String,
            other: ActionCandidate,
            evidence: List<ArtifactId>,
        ) {
            allowedRoles += role
            this.evidence.addCapped(evidence)
            if (candidate.provenance == Provenance.INFERRED && other.provenance == Provenance.OBSERVED) candidate = other
        }

        fun build(forbiddenRoles: Set<String>) =
            ActionModel(
                id = id,
                name = candidate.name,
                kind = candidate.kind,
                pageId = pageId,
                selector = candidate.selector,
                allowedRoles = allowedRoles.toSet(),
                forbiddenRoles = forbiddenRoles,
                triggersRealtime = triggersRealtime,
                httpMethod = candidate.httpMethod,
                httpPath = candidate.httpPath,
                trial = trial,
                provenance = candidate.provenance,
                evidence = evidence.toList(),
            )
    }

    private class RealtimeBuilder(
        val transport: RealtimeTransport,
    ) {
        val details = LinkedHashSet<String>()
        val pages = LinkedHashSet<String>()
        val roles = LinkedHashSet<String>()
        val evidence = LinkedHashSet<ArtifactId>()

        fun build() =
            RealtimeObservation(
                transport = transport,
                detail = details.take(MAX_DETAILS).joinToString("; "),
                pages = pages.toSet(),
                roles = roles.toSet(),
                provenance = Provenance.OBSERVED,
                evidence = evidence.toList(),
            )
    }

    companion object {
        /** The viewpoint name of the crawl without a session. */
        const val ANONYMOUS = "anonymous"

        const val MAX_EVIDENCE = 20
        private const val MAX_TEST_IDS = 200
        private const val MAX_DETAILS = 5

        private fun normalize(question: String): String =
            question
                .lowercase()
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd('?', '.', '!')

        private fun LinkedHashSet<ArtifactId>.addCapped(ids: List<ArtifactId>) {
            ids.forEach { if (size < MAX_EVIDENCE) add(it) }
        }
    }
}
