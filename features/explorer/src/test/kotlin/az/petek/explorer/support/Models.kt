package az.petek.explorer.support

import az.petek.browser.domain.RealtimeTransport
import az.petek.core.ids.ArtifactId
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ActionModel
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.FieldModel
import az.petek.explorer.domain.FormModel
import az.petek.explorer.domain.PageModel
import az.petek.explorer.domain.Provenance
import az.petek.explorer.domain.RealtimeObservation
import az.petek.explorer.domain.RoleModel
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.TrialOutcome
import az.petek.explorer.domain.TrialTouch
import az.petek.explorer.domain.Unknown
import az.petek.explorer.domain.UrlPatterns
import java.net.URI
import java.time.Instant

/** Small builders for site models in tests; defaults describe a KadroHR-like site. */
object Models {
    val TARGET: URI = URI("https://kadro.test")
    val AT: Instant = Instant.parse("2026-01-01T10:00:00Z")

    fun field(
        name: String,
        type: String = "text",
        required: Boolean = false,
        options: List<String> = emptyList(),
    ) = FieldModel(name.replaceFirstChar(Char::uppercase), name, type, required, "f-$name", "[data-testid=\"f-$name\"]", options)

    fun form(
        kind: ActionKind,
        submitTestId: String,
        actionPath: String?,
        vararg fields: FieldModel,
        method: String = "POST",
    ) = FormModel(
        "${kind.name.lowercase()} form",
        kind,
        fields.toList(),
        "[data-testid=\"$submitTestId\"]",
        method,
        actionPath,
        Provenance.OBSERVED,
        listOf(ArtifactId("art_form")),
    )

    fun page(
        pattern: String,
        vararg forms: FormModel,
        reachableBy: Set<String> = setOf("anonymous"),
        title: String = pattern,
        purpose: String = "",
        testIds: List<String> = emptyList(),
    ) = PageModel(
        id = UrlPatterns.pageId(pattern),
        urlPattern = pattern,
        title = title,
        purpose = purpose,
        reachableBy = reachableBy,
        forms = forms.toList(),
        testIds = testIds,
        linkCount = 3,
        loadMs = 120,
        provenance = Provenance.OBSERVED,
        evidence = listOf(ArtifactId("art_${UrlPatterns.pageId(pattern)}")),
    )

    fun action(
        id: String,
        kind: ActionKind,
        pagePattern: String,
        name: String = id,
        allowed: Set<String> = setOf("manager"),
        forbidden: Set<String> = emptySet(),
        realtime: Boolean? = null,
        httpPath: String? = null,
        httpMethod: String? = httpPath?.let { "POST" },
        trial: TrialTouch? = null,
        provenance: Provenance = Provenance.OBSERVED,
    ) = ActionModel(
        id = id,
        name = name,
        kind = kind,
        pageId = UrlPatterns.pageId(pagePattern),
        selector = "[data-testid=\"$id\"]",
        allowedRoles = allowed,
        forbiddenRoles = forbidden,
        triggersRealtime = realtime,
        httpMethod = httpMethod,
        httpPath = httpPath,
        trial = trial,
        provenance = provenance,
        evidence = listOf(ArtifactId("art_$id")),
    )

    fun trial(
        seenLiveBy: Set<String>,
        urlPatternAfter: String? = null,
        role: String = "employee",
    ) = TrialTouch(
        role,
        TrialOutcome.ACCEPTED,
        "Pətək sınaq x-1",
        emptyList(),
        urlPatternAfter,
        seenLiveBy,
        listOf(ArtifactId("art_trial")),
    )

    fun model(
        pages: List<PageModel>,
        actions: List<ActionModel>,
        version: Int = 1,
        roles: List<String> = listOf("anonymous", "admin", "manager", "employee"),
        realtime: List<RealtimeObservation> = emptyList(),
        unknowns: List<Unknown> = emptyList(),
        explorationId: ExplorationId = ExplorationId("exp_$version"),
    ) = SiteModel(
        version = version,
        explorationId = explorationId,
        target = TARGET,
        createdAt = AT,
        pages = pages,
        actions = actions,
        roles = roles.map { RoleModel(it, it == "anonymous", emptySet(), emptySet(), Provenance.OBSERVED, emptyList()) },
        realtime = realtime,
        unknowns = unknowns,
    )

    /**
     * The fake KadroHR as a full model: tickets (employees create, managers approve/reject/assign), announcements
     * (only the admin creates, delivered live), sign-in pages.
     */
    fun kadro(version: Int = 1): SiteModel {
        val loggedIn = setOf("admin", "manager", "employee")
        val pages =
            listOf(
                page("/login", form(ActionKind.LOGIN, "login-submit", "/login", field("email", "email"), field("password", "password"))),
                page(
                    "/tickets",
                    form(
                        ActionKind.CREATE,
                        "ticket-submit",
                        "/tickets",
                        field("title", required = true),
                        field("description", "textarea"),
                        field("department", "select", options = listOf("IT", "HR")),
                    ),
                    reachableBy = loggedIn,
                    testIds = listOf("ticket-create", "ticket-item", "ticket-list"),
                ),
                page("/tickets/{id}", reachableBy = loggedIn, testIds = listOf("ticket-status")),
                page(
                    "/announcements",
                    form(ActionKind.CREATE, "announcement-submit", "/announcements", field("title"), field("body", "textarea")),
                    reachableBy = loggedIn,
                    testIds = listOf("announcement-item"),
                ),
            )
        val actions =
            listOf(
                action("login-submit", ActionKind.LOGIN, "/login", name = "Daxil ol", allowed = setOf("anonymous"), httpPath = "/login"),
                action(
                    "ticket-submit",
                    ActionKind.CREATE,
                    "/tickets",
                    name = "Göndər",
                    allowed = loggedIn,
                    httpPath = "/tickets",
                    trial = trial(setOf("manager"), urlPatternAfter = "/tickets/{id}"),
                    realtime = true,
                ),
                action(
                    "ticket-approve",
                    ActionKind.APPROVE,
                    "/tickets/{id}",
                    name = "Təsdiqlə",
                    allowed = setOf("manager"),
                    forbidden = setOf("employee"),
                    httpPath = "/tickets/{id}/approve",
                ),
                action(
                    "ticket-reject",
                    ActionKind.REJECT,
                    "/tickets/{id}",
                    name = "Rədd et",
                    allowed = setOf("manager"),
                    forbidden = setOf("employee"),
                ),
                action(
                    "announcement-submit",
                    ActionKind.CREATE,
                    "/announcements",
                    name = "Dərc et",
                    allowed = setOf("admin"),
                    forbidden = setOf("manager", "employee"),
                    httpPath = "/announcements",
                    trial = trial(setOf("employee", "manager"), role = "admin"),
                    realtime = true,
                ),
            )
        return model(
            pages,
            actions,
            version,
            realtime =
                listOf(
                    RealtimeObservation(RealtimeTransport.SSE, "SSE /events", setOf("tickets"), loggedIn, Provenance.OBSERVED, emptyList()),
                ),
        )
    }
}
