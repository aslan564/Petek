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

package az.petek.dashboard.demo

import az.petek.dashboard.domain.ActionNodeView
import az.petek.dashboard.domain.ExplorationFindingView
import az.petek.dashboard.domain.ExplorationPhase
import az.petek.dashboard.domain.FieldNodeView
import az.petek.dashboard.domain.FindingSeverity
import az.petek.dashboard.domain.FormNodeView
import az.petek.dashboard.domain.ModelChangeKind
import az.petek.dashboard.domain.ModelChangeView
import az.petek.dashboard.domain.PageNodeView
import az.petek.dashboard.domain.Provenance
import az.petek.dashboard.domain.RealtimeView
import az.petek.dashboard.domain.TestIdeaView
import az.petek.dashboard.domain.UnknownView
import az.petek.dashboard.testing.FakeScreens.Page

/** What the demo explorer "finds" on a KadroHR-like site, and the scenarios and runs the demo panel starts with. */
object DemoContent {
    private val OBSERVED = Provenance.OBSERVED
    private val INFERRED = Provenance.INFERRED

    private fun field(
        label: String,
        type: String,
        required: Boolean = true,
    ) = FieldNodeView(label, type, required)

    private fun action(
        id: String,
        name: String,
        kind: String,
        allowed: List<String>,
        forbidden: List<String> = emptyList(),
        realtime: Boolean? = null,
        provenance: Provenance = OBSERVED,
    ) = ActionNodeView(id, name, kind, provenance, allowed, forbidden, realtime)

    val PAGES: List<PageNodeView> =
        listOf(
            PageNodeView(
                "p_login",
                "/login",
                "Daxil ol",
                "E-poçt və parolla giriş; uğurlu girişdən sonra /announcements açılır.",
                listOf("anonymous"),
                OBSERVED,
                listOf(FormNodeView("Giriş formu", OBSERVED, listOf(field("E-poçt", "email"), field("Parol", "password")))),
                listOf(action("a_login", "Daxil ol", "LOGIN", listOf("anonymous"))),
            ),
            PageNodeView(
                "p_register",
                "/register",
                "Qeydiyyat",
                "Şirkət sahibinin qeydiyyatı; e-poçta 6 rəqəmli kod gəlir.",
                listOf("anonymous"),
                OBSERVED,
                listOf(
                    FormNodeView(
                        "Sahibin qeydiyyatı",
                        OBSERVED,
                        listOf(
                            field("Ad, soyad", "text"),
                            field("E-poçt", "email"),
                            field("Parol", "password"),
                            field("Şirkət adı", "text"),
                        ),
                    ),
                ),
                listOf(action("a_register", "Şirkət yarat", "REGISTER", listOf("anonymous"))),
            ),
            PageNodeView(
                "p_join",
                "/join",
                "Şirkətə qoşul",
                "Dəvət linki və ya şirkət kodu ilə qoşulma. Rol sahəsi yoxdur: kodla qoşulan işçi olur.",
                listOf("anonymous"),
                OBSERVED,
                listOf(
                    FormNodeView(
                        "Kodla qoşulma",
                        OBSERVED,
                        listOf(field("Şirkət kodu", "text"), field("E-poçt", "email"), field("Telefon", "tel", false)),
                    ),
                ),
                listOf(action("a_join", "Qoşul", "REGISTER", listOf("anonymous"))),
            ),
            PageNodeView(
                "p_announcements",
                "/announcements",
                "Elanlar",
                "Şirkət elanları; yeni elan açıq olan bütün ekranlara canlı gəlir.",
                listOf("admin", "manager", "employee"),
                OBSERVED,
                emptyList(),
                listOf(
                    action("a_announce", "Elan yarat", "CREATE", listOf("admin", "manager"), listOf("employee"), true, INFERRED),
                    action("a_read", "Elanı oxu", "NAVIGATE", listOf("admin", "manager", "employee")),
                ),
            ),
            PageNodeView(
                "p_announcement_new",
                "/announcements/new",
                "Yeni elan",
                "Başlıq, mətn və hədəf şöbə ilə elan dərc etmək.",
                listOf("admin", "manager"),
                OBSERVED,
                listOf(
                    FormNodeView(
                        "Elan formu",
                        OBSERVED,
                        listOf(field("Başlıq", "text"), field("Mətn", "textarea"), field("Şöbə", "select", false)),
                    ),
                ),
                listOf(action("a_publish", "Dərc et", "SUBMIT", listOf("admin", "manager"), emptyList(), true)),
            ),
            PageNodeView(
                "p_tickets",
                "/tickets",
                "Ticketlər",
                "Şöbəyə müraciətlər; status: açıq, icrada, bağlı.",
                listOf("admin", "manager", "employee"),
                OBSERVED,
                listOf(
                    FormNodeView(
                        "Yeni ticket",
                        OBSERVED,
                        listOf(field("Şöbə", "select"), field("Mövzu", "text"), field("Təsvir", "textarea", false)),
                    ),
                ),
                listOf(action("a_ticket", "Yeni ticket", "CREATE", listOf("admin", "manager", "employee"), emptyList(), true)),
            ),
            PageNodeView(
                "p_ticket",
                "/tickets/{id}",
                "Ticket",
                "Ticketin detalları; menecer icraya götürür, təyin edir və təsdiqləyir.",
                listOf("admin", "manager", "employee"),
                OBSERVED,
                emptyList(),
                listOf(
                    action("a_take", "İcraya götür", "UPDATE", listOf("manager")),
                    action("a_assign", "Təyin et", "ASSIGN", listOf("manager", "admin")),
                    action("a_approve", "Təsdiqlə", "APPROVE", listOf("manager"), listOf("employee"), true, INFERRED),
                ),
            ),
            PageNodeView(
                "p_employees",
                "/employees",
                "İşçilər",
                "Şirkət işçiləri, şöbələr və dəvətlər.",
                listOf("admin", "manager"),
                OBSERVED,
                listOf(FormNodeView("Dəvət", INFERRED, listOf(field("E-poçt", "email"), field("Rol", "select"), field("Şöbə", "select")))),
                listOf(action("a_invite", "Dəvət göndər", "CREATE", listOf("admin"), listOf("manager", "employee"), null, INFERRED)),
            ),
        )

    val REALTIME =
        listOf(RealtimeView("sse", "GET /api/events — elan və ticket statusları canlı gəlir", listOf("/announcements", "/tickets")))

    /** The walk, in order: which page, as whom, what the screen showed, how it answered. */
    val VISITS: List<DemoVisit> =
        listOf(
            DemoVisit(ExplorationPhase.ANONYMOUS, "/", "KadroHR — Ana səhifə", "anonymous", Page.LOGIN, 200, 640),
            DemoVisit(ExplorationPhase.ANONYMOUS, "/login", "Daxil ol", "anonymous", Page.LOGIN, 200, 410),
            DemoVisit(ExplorationPhase.ANONYMOUS, "/register", "Qeydiyyat", "anonymous", Page.JOIN, 200, 530),
            DemoVisit(ExplorationPhase.ANONYMOUS, "/join", "Şirkətə qoşul", "anonymous", Page.JOIN, 200, 380),
            DemoVisit(ExplorationPhase.ANONYMOUS, "/help", "Kömək", "anonymous", Page.LOGIN, 404, 120),
            DemoVisit(ExplorationPhase.ANONYMOUS, "/privacy", "Məxfilik", "anonymous", Page.LOGIN, 200, 290),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/announcements", "Elanlar", "admin", Page.ANNOUNCEMENTS, 200, 820),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/announcements/new", "Yeni elan", "admin", Page.NEW_ANNOUNCEMENT, 200, 450),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/employees", "İşçilər", "admin", Page.TASKS, 200, 1_240),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/employees/export", "İxrac", "admin", Page.TASKS, 500, 2_110),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/tickets", "Ticketlər", "admin", Page.TASKS, 200, 4_820),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/announcements", "Elanlar", "manager", Page.ANNOUNCEMENTS, 200, 760),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/tickets", "Ticketlər", "manager", Page.TASKS, 200, 3_950),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/tickets/57", "Ticket #57", "manager", Page.TASKS, 200, 610),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/employees", "İşçilər", "manager", Page.TASKS, 200, 980),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/notifications", "Bildirişlər", "manager", Page.NOTIFICATIONS, 200, 520),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/announcements", "Elanlar", "employee", Page.ANNOUNCEMENTS, 200, 700),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/tickets", "Ticketlər", "employee", Page.TASKS, 200, 3_700),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/tickets/57", "Ticket #57", "employee", Page.TASKS, 200, 580),
            DemoVisit(ExplorationPhase.ROLE_BASED, "/notifications", "Bildirişlər", "employee", Page.NOTIFICATIONS, 200, 470),
            DemoVisit(ExplorationPhase.TRIAL_TOUCH, "/announcements/new", "Yeni elan (sınaq)", "admin", Page.NEW_ANNOUNCEMENT, 200, 560),
            DemoVisit(ExplorationPhase.TRIAL_TOUCH, "/tickets", "Yeni ticket (sınaq)", "employee", Page.TASKS, 201, 690),
        )

    val FINDINGS: List<Pair<String, ExplorationFindingView>> =
        listOf(
            "/employees/export" to
                ExplorationFindingView(
                    "HTTP_ERROR",
                    FindingSeverity.HIGH,
                    "https://staging.kadrohr.az/employees/export",
                    "GET /api/employees/export → 500 Internal Server Error",
                    emptyList(),
                ),
            "/tickets" to
                ExplorationFindingView(
                    "SLOW_PAGE",
                    FindingSeverity.MEDIUM,
                    "https://staging.kadrohr.az/tickets",
                    "Səhifə 4,8 s-də yükləndi (hədd 3 s); 212 sorğu",
                    emptyList(),
                ),
            "/announcements/new" to
                ExplorationFindingView(
                    "ACCESSIBILITY",
                    FindingSeverity.MEDIUM,
                    "https://staging.kadrohr.az/announcements/new",
                    "'Mətn' sahəsinin əlçatan adı yoxdur (label bağlı deyil)",
                    emptyList(),
                ),
            "/announcements" to
                ExplorationFindingView(
                    "CONSOLE_ERROR",
                    FindingSeverity.LOW,
                    "https://staging.kadrohr.az/announcements",
                    "Uncaught TypeError: Cannot read properties of undefined (reading 'avatar')",
                    emptyList(),
                ),
            "/help" to
                ExplorationFindingView(
                    "BROKEN_LINK",
                    FindingSeverity.LOW,
                    "https://staging.kadrohr.az/help",
                    "Altbilgidəki 'Kömək' keçidi 404 qaytarır",
                    emptyList(),
                ),
        )

    val UNKNOWNS: List<UnknownView> =
        listOf(
            UnknownView(
                "u_1",
                "Menecer başqa şöbənin ticketini təsdiqləyə bilərmi?",
                "IT meneceri HR şöbəsinin ticketində də 'Təsdiqlə' düyməsini gördü; qayda saytdan aydın deyil.",
                null,
            ),
            UnknownView(
                "u_2",
                "Elan yalnız seçilmiş şöbəyə gedir, yoxsa hamıya?",
                "Formda 'Şöbə' seçimi var, amma default 'Hamısı'-dır və seçim elanlar siyahısında görünmür.",
                "Hamıya gedir; şöbə yalnız siyahıda filtr üçündür.",
            ),
            UnknownView(
                "u_3",
                "Test şirkətində ticket silmək olarmı?",
                "Silmə düyməsi yalnız admin-də görünür; sınaq toxunuşu heç nə silmir.",
                null,
            ),
        )

    val IDEAS: List<TestIdeaView> =
        listOf(
            TestIdeaView("REALTIME", "Elan yarat", "Admin elanı dərc edir → bütün işçilər 5 s ərzində canlı görməlidir (SSE).", 1),
            TestIdeaView("RACE", "Təsdiqlə", "İki menecer eyni ticketi eyni anda təsdiqləyir; yalnız biri uğurlu olmalıdır.", 1),
            TestIdeaView("PERMISSION", "Təsdiqlə", "İşçi təsdiq düyməsini görməməli, API 403 qaytarmalıdır.", 1),
            TestIdeaView("HAPPY_PATH", "Yeni ticket", "İşçi IT-yə ticket yazır, menecer icraya götürür və təyin edir.", 2),
            TestIdeaView("BOUNDARY", "Elan yarat", "Boş və 5 000 simvolluq başlıq: server doğrulaması və səhv mesajı.", 2),
            TestIdeaView("IDEMPOTENCY", "Dərc et", "'Dərc et'-ə iki dəfə basmaq iki elan yaratmamalıdır.", 3),
            TestIdeaView("PERMISSION", "Dəvət göndər", "Menecer dəvət göndərə bilməməlidir (yalnız admin).", 3),
        )

    val MODEL_CHANGES: List<ModelChangeView> =
        listOf(
            ModelChangeView(ModelChangeKind.ADDED, "PAGE", "/employees/export", "Yeni ixrac səhifəsi (hələ 500 qaytarır)"),
            ModelChangeView(ModelChangeKind.CHANGED, "ACTION", "Yeni ticket", "Düymənin mətni 'Müraciət yarat' idi"),
            ModelChangeView(ModelChangeKind.ADDED, "FIELD", "Yeni ticket → Təsvir", "Könüllü mətn sahəsi"),
            ModelChangeView(ModelChangeKind.REMOVED, "ACTION", "Ticketi sil (işçi)", "İşçi artıq ticket silə bilmir"),
        )

    val DRAFT_YAML: String =
        """
        # Kəşfiyyatçının modelindən kodla yığılıb (model v4). Təsdiqdən əvvəl yoxlayın.
        campaign:
          name: kadrohr-explored
          target: https://staging.kadrohr.az
          testers: 30
          roles: {admin: 1, manager: 5, employee: 24}
          departments: [IT, HR, Satış, Maliyyə, Əməliyyat]
          registration: {invite: 15, company_code: 14}

        setup:
          - id: owner_signup
            actor: admin
            do: "Qeydiyyatdan keç, e-poçt kodunu təsdiqlə və şirkət yarat"
          - id: join
            actor: employee[*] | manager[*]
            run: register_and_login

        steps:
          # REALTIME (prioritet 1): Elan yarat
          - id: announce
            actor: admin
            do: "Elan yarat: 'Sabah 10:00 ümumi iclas'"
            emits: announcement_created
          - id: read_announce
            actor: employee[*]
            wait_for: announcement_created
            do: "Bildirişləri aç və yeni elanı oxu"
            assert:
              - visible_text: {text: "Sabah 10:00 ümumi iclas", within_s: 5}
          # RACE (prioritet 1): Təsdiqlə
          - id: race
            actor: ["manager[IT]", "manager[HR]"]
            parallel: true
            do: "Eyni ticketi təsdiqlə"
            assert:
              - only_one_succeeds: true
          # PERMISSION (prioritet 1): Təsdiqlə
          - id: forbidden
            actor: employee[dept=IT, n=2]
            do: "Ticketi təsdiqləməyə çalış"
            assert:
              - not_visible: {selector: "[data-testid=\"ticket-approve\"]"}
              - http_status: {path: "/api/tickets/{last_id}/approve", method: POST, equals: 403}
        """.trimIndent()

    private val CORE_V1 =
        """
        campaign:
          name: kadrohr-core
          target: https://staging.kadrohr.az
          testers: 30
          roles: {admin: 1, manager: 5, employee: 24}
          departments: [IT, HR, Satış, Maliyyə, Əməliyyat]
          registration: {invite: 15, company_code: 14}
          budget: {max_steps_per_agent: 60, max_minutes: 40}

        setup:
          - id: owner_signup
            actor: admin
            do: "Qeydiyyatdan keç, email kodunu təsdiqlə, 'Pətək Test MMC' adlı şirkət yarat"
          - id: seed
            actor: admin
            run: seed_company
          - id: join
            actor: employee[*] | manager[*]
            run: register_and_login

        steps:
          - id: announce
            actor: admin
            do: "Elan yarat: 'Sabah 10:00 ümumi iclas'"
            emits: announcement_created
          - id: read_announce
            actor: employee[*]
            wait_for: announcement_created
            do: "Bildirişləri aç və yeni elanı oxu"
            assert:
              - visible_text: {text: "Sabah 10:00 ümumi iclas", within_s: 10}
          - id: ticket
            actor: employee[dept=IT, n=1]
            do: "IT departamentinə ticket yaz: 'Noutbuk işləmir'"
            emits: ticket_created
        """.trimIndent()

    private val CORE_V2 =
        CORE_V1
            .replace(
                "within_s: 10}",
                "within_s: 5}\n      - latency_max: {ms: 5000}\n      - oracle: {path: \"/test/announcements/{last_id}/receipts\", contains: \"{self.email}\"}",
            ).replace(
                "    emits: ticket_created",
                """
                |    emits: ticket_created
                |  - id: ticket_flow
                |    actor: manager[IT]
                |    wait_for: ticket_created
                |    do: "Ticketi in-progress et, sonra HR menecerinə assign et"
                |  - id: race
                |    actor: ["manager[IT]", "manager[HR]"]
                |    parallel: true
                |    do: "Eyni ticketi approve et"
                |    assert:
                |      - only_one_succeeds: true
                |  - id: forbidden
                |    actor: employee[dept=IT, n=2]
                |    do: "Ticketi approve etməyə çalış"
                |    assert:
                |      - not_visible: {selector: "[data-testid=\"ticket-approve\"]"}
                |      - http_status: {path: "/api/tickets/{last_id}/approve", method: POST, equals: 403}
                """.trimMargin(),
            )

    private val CORE_V3 =
        CORE_V2
            .replace(
                "do: \"IT departamentinə ticket yaz: 'Noutbuk işləmir'\"",
                "do: \"'Müraciət yarat' ilə IT-yə müraciət yaz: 'Noutbuk işləmir'\"",
            ).replace("    wait_for: announcement_created\n", "    wait_for: announcement_created\n    timeout_s: 15\n")

    private val SIGNUP_V1 =
        """
        campaign:
          name: kadrohr-qeydiyyat
          target: https://staging.kadrohr.az
          testers: 10
          roles: {admin: 1, manager: 1, employee: 8}
          departments: [IT, HR]
          registration: {invite: 5, company_code: 4}

        setup:
          - id: owner_signup
            actor: admin
            do: "Qeydiyyatdan keç və şirkət yarat"

        steps:
          - id: join
            actor: employee[*] | manager[*]
            run: register_and_login
        """.trimIndent()

    private val SIGNUP_V2 =
        SIGNUP_V1.replace(
            "    run: register_and_login",
            "    run: register_and_login\n    assert:\n      - oracle: {path: \"/test/companies/{company_id}/members\", count: 9}",
        )

    /** name, version, status, source, parent version, note, days ago, yaml. */
    val SCENARIOS: List<DemoScenario> =
        listOf(
            DemoScenario("kadrohr-core", 1, "FROZEN", "USER", null, "İlk əl ilə yazılmış versiya; 3 run-da sabit keçdi.", 12, CORE_V1),
            DemoScenario(
                "kadrohr-core",
                2,
                "APPROVED",
                "USER",
                1,
                "Yarış və icazə addımları əlavə olundu; elan 5 s ərzində görünməlidir.",
                5,
                CORE_V2,
            ),
            DemoScenario(
                "kadrohr-core",
                3,
                "DRAFT",
                "TRIAGE",
                2,
                "Triaj təklifi: 'Yeni ticket' düyməsi 'Müraciət yarat' oldu (model boşluğu); gözləmə 15 s.",
                1,
                CORE_V3,
            ),
            DemoScenario("kadrohr-qeydiyyat", 1, "SUPERSEDED", "USER", null, "Yalnız qeydiyyat axını.", 20, SIGNUP_V1),
            DemoScenario("kadrohr-qeydiyyat", 2, "FROZEN", "USER", 1, "Üzvlərin sayı oracle ilə yoxlanır.", 9, SIGNUP_V2),
        )
}

/** One page the demo explorer opens. */
data class DemoVisit(
    val phase: ExplorationPhase,
    val path: String,
    val title: String,
    val visitedAs: String,
    val screen: Page,
    val httpStatus: Int,
    val loadMs: Long,
)

data class DemoScenario(
    val name: String,
    val version: Int,
    val status: String,
    val source: String,
    val parentVersion: Int?,
    val note: String,
    val daysAgo: Long,
    val yaml: String,
)
